package org.campusreader.reader;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import org.campusreader.auth.CampusAccount;
import org.campusreader.auth.CampusApplication;
import org.campusreader.config.CampusSite;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/** Portal identity, reachability and VPN truth are deliberately independent. */
public final class PortalAccess {
    public enum Route { UNKNOWN, DIRECT, VPN, UNAVAILABLE }
    public static final Object ACCOUNT_LOCK=new Object();
    private static volatile Route route=Route.UNKNOWN;
    private static volatile String problem="",scope="";
    private static volatile Network observedNetwork,selectedNetwork;
    private static volatile long checkedAt;
    private static final AtomicLong revision=new AtomicLong(),probeSequence=new AtomicLong(),accountSequence=new AtomicLong();
    private PortalAccess(){}
    private static Context context(){return CampusApplication.context();}
    public static String account(Context c){
        if(c==null||!CampusSite.isConfigured())return "";
        String saved=c.getSharedPreferences("portal-account",0).getString("account:"+CampusSite.scopeId(),"");
        if(!saved.isEmpty())return saved;
        // Keep the old account/scope exactly: no database or key migration on a route change.
        try{return CampusApplication.auth().getProfileId();}catch(RuntimeException unavailable){return "";}
    }
    public static String currentProfile(Context c){return MediaSafety.readerProfile(account(c));}
    public static String currentProfile(){return currentProfile(context());}
    public static String account(){return account(context());}
    public static String loginUrl(){return org.campusreader.config.BuptPreset.matches(CampusSite.requireConfigured())?org.campusreader.config.BuptPreset.LOGIN:CampusSite.loginUrl();}
    public static long revision(){return revision.get();}
    public static synchronized void invalidate(){route=Route.UNKNOWN;checkedAt=0;selectedNetwork=null;revision.incrementAndGet();}
    public static String problem(){return problem;}
    public static boolean hasNetwork(Context c){try{ConnectivityManager m=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);return m!=null&&m.getAllNetworks().length>0;}catch(RuntimeException unavailable){return false;}}
    public static String routeLabel(Context c){
        if(vpnReady())return "VPN 通道";
        if(isReady(c)){
            try{ConnectivityManager m=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);NetworkCapabilities caps=m==null?null:m.getNetworkCapabilities(active(c));
                if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "现有 VPN 通道";
            }catch(RuntimeException unavailable){}
            return "门户直连";
        }
        return route==Route.UNAVAILABLE?"暂不可达":"正在检查连接";
    }
    private static boolean vpnReady(){try{return CampusApplication.auth().isAccessReady();}catch(RuntimeException absent){return false;}}
    private static Network active(Context c){try{ConnectivityManager m=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);return m==null?null:m.getActiveNetwork();}catch(RuntimeException denied){return null;}}
    public static boolean isReady(Context c){
        if(c==null||!CampusSite.isConfigured())return false;
        if(vpnReady())return true;
        if(route!=Route.DIRECT||!scope.equals(CampusSite.scopeId()))return false;
        if(!java.util.Objects.equals(active(c),observedNetwork)){invalidate();return false;}
        return SystemClock.elapsedRealtime()-checkedAt<5*60_000L;
    }
    public static boolean isReady(){return isReady(context());}
    /** Clear old session before publishing the selected account. Main-thread callback. */
    public static void selectAccount(Context c,String input){selectAccount(c,input,()->{});}
    public static void selectAccount(Context c,String input,Runnable ready){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Account selection requires main thread");
        String next=CampusAccount.profileId(input);
        if(next.length()>128)throw new IllegalArgumentException("学工号过长");
        if(next.equals(account(c))){ready.run();return;}
        final long selection=accountSequence.incrementAndGet();final String selectionScope=CampusSite.scopeId();
        new ReaderPreferences(c).clearPortalSession();revision.incrementAndGet();
        PortalLoginLaunch.clear();
        CookieManager.getInstance().removeAllCookies(done->{
            if(selection!=accountSequence.get()||!selectionScope.equals(CampusSite.scopeId()))return;
            CookieManager.getInstance().flush();android.webkit.WebStorage.getInstance().deleteAllData();
            android.webkit.WebViewDatabase.getInstance(c).clearHttpAuthUsernamePassword();
            android.webkit.WebView.clearClientCertPreferences(()->{
                if(selection!=accountSequence.get()||!selectionScope.equals(CampusSite.scopeId()))return;
                synchronized(ACCOUNT_LOCK){
                    if(selection!=accountSequence.get()||!selectionScope.equals(CampusSite.scopeId()))return;
                    c.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),next).apply();
                    ReaderNotifications.clearAll(c);
                }
                ready.run();
            });
        });
    }
    /** Bounded anonymous reachability probe. No password, session Cookie or SDK calls. */
    public static Route probe(Context c){
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Reachability requires worker thread");
        long probeId=probeSequence.incrementAndGet(),startRevision=revision.get();String startScope=CampusSite.scopeId();
        if(vpnReady()){route=Route.VPN;problem="";return route;}
        Network initial=active(c);
        Route result=probeNetwork(null);Network chosen=null;
        // A campus Wi-Fi may expose the portal without public Internet validation while mobile
        // data is the system default. Scope explicit binding to portal HTTP/media requests only.
        if(result==Route.UNAVAILABLE){
            try{ConnectivityManager m=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
                if(m!=null)for(Network n:m.getAllNetworks()){
                    NetworkCapabilities caps=m.getNetworkCapabilities(n);
                    if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!n.equals(initial)){
                        Route attempt=probeNetwork(n);if(attempt!=Route.UNAVAILABLE){result=attempt;chosen=n;}break;
                    }
                }
            }catch(RuntimeException unavailable){}
        }
        if(probeId!=probeSequence.get()||startRevision!=revision.get()||!startScope.equals(CampusSite.scopeId()))return Route.UNKNOWN;
        scope=startScope;route=result;selectedNetwork=chosen;observedNetwork=initial;checkedAt=SystemClock.elapsedRealtime();
        return result;
    }
    private static Route probeNetwork(Network n){
        URL current;
        try{current=new URL(CampusSite.portalListUrl());}catch(Exception invalid){problem="配置地址不可用";return Route.UNKNOWN;}
        long deadline=SystemClock.elapsedRealtime()+12_000L;
        try{
            for(int redirects=0;redirects<4;redirects++){
                if(SystemClock.elapsedRealtime()>=deadline)throw new java.net.SocketTimeoutException();
                if(Thread.currentThread().isInterrupted())return Route.UNKNOWN;
                HttpURLConnection connection=(HttpURLConnection)(n==null?current.openConnection():n.openConnection(current));
                try{
                    connection.setConnectTimeout(4500);connection.setReadTimeout(4500);connection.setInstanceFollowRedirects(false);
                    connection.setUseCaches(false);connection.setRequestProperty("User-Agent",ReaderHttp.USER_AGENT);
                    int code=connection.getResponseCode();
                    if(code>=500){problem="门户服务暂时异常";return Route.UNKNOWN;}
                    if(code==401||code==403){problem="门户需要登录或访问权限";return Route.DIRECT;}
                    if(code>=300&&code<400){
                        String location=connection.getHeaderField("Location");
                        if(location==null){problem="门户返回未知跳转";return Route.UNKNOWN;}
                        URL next=new URL(current,location);
                        if(CampusSite.allowsPortalUrl(next.toString())){current=next;continue;}
                        if(!isKnownLogin(next.toString())){problem="门户返回未知认证地址";return Route.UNKNOWN;}
                        problem="";return Route.DIRECT;
                    }
                    if(code!=200){problem="门户服务返回异常状态";return Route.UNKNOWN;}
                    byte[] bytes=new byte[32768];int used=0,count;
                    try(InputStream in=connection.getInputStream()){while(used<bytes.length&&(count=in.read(bytes,used,bytes.length-used))>0){used+=count;if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=deadline)throw new java.net.SocketTimeoutException();}}
                    String text=new String(bytes,0,used,StandardCharsets.UTF_8);
                    if(text.contains("wbnewsid")||text.contains("wbtreeid")||text.contains("authserver/login")||text.contains("casLoginForm")||text.contains("loginForm")){
                        problem="";return Route.DIRECT;
                    }
                    problem="返回页面尚未识别，请稍后重试";return Route.UNKNOWN;
                }finally{connection.disconnect();}
            }
            problem="认证跳转过多";return Route.UNKNOWN;
        }catch(javax.net.ssl.SSLException rejected){problem="门户证书校验失败";return Route.UNKNOWN;}
        catch(java.io.IOException unavailable){problem="当前网络无法直连门户";return Route.UNAVAILABLE;}
    }
    static boolean isKnownLogin(String address){
        try{java.net.URI u=java.net.URI.create(address);
            if(!CampusSite.allowsLoginUrl(address)||!"https".equalsIgnoreCase(u.getScheme())||u.getUserInfo()!=null||u.getFragment()!=null)return false;
            if(org.campusreader.config.BuptPreset.matches(CampusSite.requireConfigured()))
                return "auth.bupt.edu.cn".equalsIgnoreCase(u.getHost())&&"/authserver/login".equals(u.getPath());
            java.net.URI configured=java.net.URI.create(CampusSite.loginUrl());
            return u.getHost().equalsIgnoreCase(configured.getHost())&&java.util.Objects.equals(u.getPath(),configured.getPath());
        }catch(RuntimeException invalid){return false;}
    }
    static HttpURLConnection openConnection(URL url)throws java.io.IOException{
        Network selected=selectedNetwork;
        return (HttpURLConnection)(selected!=null&&route==Route.DIRECT&&CampusSite.allowsPortalUrl(url.toString())?selected.openConnection(url):url.openConnection());
    }
}
