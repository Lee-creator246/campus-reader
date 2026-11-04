package org.campusreader.reader;

import org.campusreader.config.CampusSite;
import org.campusreader.auth.CampusApplication;
import org.campusreader.auth.CampusConfiguration;
import android.webkit.CookieManager;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.*;

/** Bounded requests on a worker thread. Platform certificate checks remain enabled. */
final class ReaderHttp {
    static final String USER_AGENT="Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36";
    static final class Response {
        final String url,mime; final int status; final byte[] bytes;
        Response(String url,int status,String mime,byte[] bytes) {this.url=url;this.status=status;this.mime=mime;this.bytes=bytes;}
        String text() {
            String encoding="UTF-8";
            Matcher m=Pattern.compile("charset=([A-Za-z0-9_-]+)",Pattern.CASE_INSENSITIVE).matcher(mime);
            if(m.find()) encoding=m.group(1);
            else {
                String head=new String(bytes,0,Math.min(2048,bytes.length),Charset.forName("ISO-8859-1"));
                m=Pattern.compile("charset\\s*=\\s*['\"]?([A-Za-z0-9_-]+)",Pattern.CASE_INSENSITIVE).matcher(head);
                if(m.find()) encoding=m.group(1);
            }
            try{return new String(bytes,Charset.forName(encoding));}catch(Exception e){return new String(bytes,Charset.forName("UTF-8"));}
        }
    }
    static final class RequestGuard {
        private final String profile;private final boolean campusOnly;private final Thread owner;private final long accessRevision;
        private final java.util.function.BooleanSupplier cancelled;
        RequestGuard(String profile,boolean campusOnly,Thread owner,java.util.function.BooleanSupplier cancelled){this.accessRevision=PortalAccess.revision();this.profile=profile;this.campusOnly=campusOnly;this.owner=owner;this.cancelled=cancelled;}
        void check()throws IOException{if(owner.isInterrupted()||cancelled.getAsBoolean())throw new IOException("Request cancelled");if(campusOnly){if(accessRevision!=PortalAccess.revision())throw new IOException("Request cancelled");requireCampusSession(profile);}}
    }
    static Response get(String address,int byteLimit,boolean campusOnly)throws IOException{return get(address,byteLimit,campusOnly,()->false);}
    static Response get(String address,int byteLimit,boolean campusOnly,java.util.function.BooleanSupplier cancelled) throws IOException {
        String profile=campusOnly?MediaActions.activeProfile():"";
        RequestGuard guard=new RequestGuard(profile,campusOnly,Thread.currentThread(),cancelled);guard.check();
        URL current=checked(address,campusOnly);
        for(int redirects=0;redirects<=5;redirects++) {
            guard.check();
            current=checked(current.toString(),campusOnly);
            HttpURLConnection connection=PortalAccess.openConnection(current);
            try {
                connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false); connection.setRequestProperty("User-Agent",USER_AGENT);
                connection.setRequestProperty("Accept-Encoding","identity");
                if(campusOnly) {
                    guard.check();
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Cache-Control","no-cache");
                    String cookie=CookieManager.getInstance().getCookie(current.toString());
                    if(cookie!=null&&!cookie.isEmpty()) connection.setRequestProperty("Cookie",cookie);
                }
                int status=connection.getResponseCode();
                guard.check();
                if(campusOnly) for(Map.Entry<String,List<String>> h:connection.getHeaderFields().entrySet())
                    if("set-cookie".equalsIgnoreCase(h.getKey())) for(String value:h.getValue()) {
                        persistCookie(guard,current.toString(),value);
                    }
                String mime=connection.getContentType(); if(mime==null) mime="";
                if(status==301||status==302||status==303||status==307||status==308) {
                    String location=connection.getHeaderField("Location");
                    if(location==null) return new Response(current.toString(),status,mime,new byte[0]);
                    URL next=new URL(current,location);
                    if("https".equals(current.getProtocol())&&!"https".equals(next.getProtocol())) throw new MediaSafety.Rejected("HTTPS_DOWNGRADE");
                    // Report a portal SSO redirect to the parser; never send campus cookies to another origin.
                    if(campusOnly&&!isCampus(next.toString())) return new Response(next.toString(),status,mime,new byte[0]);
                    current=checked(next.toString(),campusOnly); continue;
                }
                String declaredLength=connection.getHeaderField("Content-Length");
                if(declaredLength!=null)try{if(Long.parseLong(declaredLength)>byteLimit)throw new IOException("Response exceeds size limit");}catch(NumberFormatException ignored){}
                InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
                if(input==null) return new Response(current.toString(),status,mime,new byte[0]);
                try(InputStream stream=input; ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192]; int count,total=0;
                    while((count=stream.read(buffer))!=-1) {guard.check();total+=count;if(total>byteLimit)throw new IOException("Response exceeds size limit");output.write(buffer,0,count);}
                    guard.check();
                    return new Response(current.toString(),status,mime,output.toByteArray());
                }
            } finally {connection.disconnect();}
        }
        throw new IOException("Too many redirects");
    }
    private static void requireCampusSession(String profile) throws IOException {
        if(!CampusConfiguration.isConfigured())throw new MediaSafety.Rejected("ACCOUNT_CHANGED");
        if(!profile.startsWith("campus:")||!MediaActions.currentProfile(profile))throw new MediaSafety.Rejected("ACCOUNT_CHANGED");
        if(!PortalAccess.isReady())throw new MediaSafety.Rejected("CAMPUS_OFFLINE");
    }
    /** Await the WebView cookie write before following a renewal redirect or issuing another request. */
    static void persistCookie(RequestGuard guard,String url,String value)throws IOException {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IOException("Cookie update requires worker thread");
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<IOException> failed=new java.util.concurrent.atomic.AtomicReference<>();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
            try{guard.check();CookieManager.getInstance().setCookie(url,value,accepted->{
                // Rejected expiry/deletion cookies are not a reason to copy cookie values elsewhere.
                done.countDown();
            });}catch(Exception error){failed.set(new IOException("Cookie update unavailable"));done.countDown();}
        });
        try{if(!done.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Cookie update timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Cookie update cancelled");}
        if(failed.get()!=null)throw failed.get();guard.check();CookieManager.getInstance().flush();
    }
    static boolean isCampus(String value) {return CampusSite.allowsPortalUrl(value);}
    static URL checked(String value,boolean campusOnly) throws IOException {
        return MediaSafety.checked(value,campusOnly);
    }
}
