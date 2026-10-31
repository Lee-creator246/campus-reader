package org.campusreader.reader;

import org.campusreader.config.CampusSite;
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
    static Response get(String address,int byteLimit,boolean campusOnly) throws IOException {
        URL current=checked(address,campusOnly);
        for(int redirects=0;redirects<=5;redirects++) {
            HttpURLConnection connection=(HttpURLConnection)current.openConnection();
            try {
                connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false); connection.setRequestProperty("User-Agent",USER_AGENT);
                connection.setRequestProperty("Accept-Encoding","identity");
                if(campusOnly) {
                    String cookie=CookieManager.getInstance().getCookie(current.toString());
                    if(cookie!=null&&!cookie.isEmpty()) connection.setRequestProperty("Cookie",cookie);
                }
                int status=connection.getResponseCode();
                if(campusOnly) for(Map.Entry<String,List<String>> h:connection.getHeaderFields().entrySet())
                    if("set-cookie".equalsIgnoreCase(h.getKey())) for(String value:h.getValue()) CookieManager.getInstance().setCookie(current.toString(),value);
                String mime=connection.getContentType(); if(mime==null) mime="";
                if(status==301||status==302||status==303||status==307||status==308) {
                    String location=connection.getHeaderField("Location");
                    if(location==null) return new Response(current.toString(),status,mime,new byte[0]);
                    URL next=new URL(current,location);
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
                    while((count=stream.read(buffer))!=-1) {total+=count;if(total>byteLimit)throw new IOException("Response exceeds size limit");output.write(buffer,0,count);}
                    return new Response(current.toString(),status,mime,output.toByteArray());
                }
            } finally {connection.disconnect();}
        }
        throw new IOException("Too many redirects");
    }
    static boolean isCampus(String value) {try {URL url=new URL(value);return CampusSite.PORTAL_HOST.equalsIgnoreCase(url.getHost())&&url.getUserInfo()==null&&(url.getPort()==-1||url.getPort()==80||url.getPort()==443);}catch(Exception e){return false;}}
    static URL checked(String value,boolean campusOnly) throws IOException {
        URL url=new URL(value); String protocol=url.getProtocol();
        if(url.getUserInfo()!=null||!("https".equals(protocol)||"http".equals(protocol))) throw new IOException("Unsupported URL");
        if(campusOnly&&!isCampus(value)) throw new IOException("Portal origin changed");
        if(!"https".equals(protocol)&&!isCampus(value)) throw new IOException("Public subscriptions require HTTPS");
        return url;
    }
}
