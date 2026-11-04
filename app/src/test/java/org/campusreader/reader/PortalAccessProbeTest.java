package org.campusreader.reader;

import android.app.Application;
import android.webkit.CookieManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.campusreader.config.BuptPreset;
import org.campusreader.config.CampusSite;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic version of the on-device anonymous entry -> session callback -> CAS redirect.
 * No requests leave this test and no real session identifiers or credentials are used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class PortalAccessProbeTest {
    private static final String CALLBACK="http://my.bupt.edu.cn/system/resource/code/auth/clogin.jsp?owner=1664271694";
    @Before public void before(){CampusSite.install(BuptPreset.profile());PortalAccess.invalidate();CookieManager.getInstance().removeAllCookies(null);}
    @After public void after(){CookieManager.getInstance().removeAllCookies(null);CampusSite.clear();PortalAccess.invalidate();}

    @Test public void anonymousCallbackKeepsItsTemporaryCookieWithoutUsingTheWebViewSession()throws Exception{
        CookieManager.getInstance().setCookie(BuptPreset.LIST,"logged_in=private-existing; Path=/");
        String before=CookieManager.getInstance().getCookie(BuptPreset.LIST);
        final List<SyntheticConnection> opened=new ArrayList<>();
        PortalAccess.Route result=PortalAccess.probeAnonymous(url->{
            SyntheticConnection connection;
            if(opened.isEmpty()){
                assertEquals(BuptPreset.LIST,url.toString());
                connection=new SyntheticConnection(url,302,CALLBACK,"JSESSIONID=anonymous-probe; Path=/; HttpOnly",false);
            }else{
                assertEquals(1,opened.size());assertEquals(CALLBACK,url.toString());
                // Without the cookie the actual callback responds with an unrecognized HTTP
                // 200 system page. Only carrying the temporary cookie reaches the CAS redirect.
                connection=new SyntheticConnection(url,302,BuptPreset.LOGIN,null,true);
            }
            opened.add(connection);return connection;
        });
        assertEquals(PortalAccess.Route.DIRECT,result);assertEquals(2,opened.size());
        assertNull(opened.get(0).getRequestProperty("Cookie"));
        String callbackCookie=opened.get(1).getRequestProperty("Cookie");
        assertTrue(callbackCookie.contains("JSESSIONID=anonymous-probe"));assertFalse(callbackCookie.contains("private-existing"));
        assertEquals(before,CookieManager.getInstance().getCookie(BuptPreset.LIST));
        for(SyntheticConnection connection:opened)assertTrue(connection.disconnected);
    }

    @Test public void eachNetworkProbeStartsWithANewAnonymousCookieJar()throws Exception{
        final List<SyntheticConnection> entries=new ArrayList<>();
        for(int attempt=0;attempt<2;attempt++){
            final int[] count={0};
            assertEquals(PortalAccess.Route.DIRECT,PortalAccess.probeAnonymous(url->{
                if(count[0]++==0){SyntheticConnection entry=new SyntheticConnection(url,302,CALLBACK,"JSESSIONID=anonymous-probe; Path=/",false);entries.add(entry);return entry;}
                return new SyntheticConnection(url,302,BuptPreset.LOGIN,null,true);
            }));
        }
        assertEquals(2,entries.size());for(SyntheticConnection entry:entries)assertNull(entry.getRequestProperty("Cookie"));
    }

    @Test public void unknownHostIsNeverOpenedOrGivenEvenAParentDomainCookie()throws Exception{
        final List<URL> opened=new ArrayList<>();
        assertEquals(PortalAccess.Route.UNKNOWN,PortalAccess.probeAnonymous(url->{
            opened.add(url);return new SyntheticConnection(url,302,"https://other.bupt.edu.cn/collect","probe=temporary; Domain=.bupt.edu.cn; Path=/",false);
        }));
        assertEquals(1,opened.size());assertEquals("my.bupt.edu.cn",opened.get(0).getHost());
        assertEquals("门户返回未知认证地址",PortalAccess.problem());
    }

    @Test public void knownCasRedirectDoesNotReceiveAnyPortalCookie()throws Exception{
        final List<URL> opened=new ArrayList<>();
        assertEquals(PortalAccess.Route.DIRECT,PortalAccess.probeAnonymous(url->{
            opened.add(url);return new SyntheticConnection(url,302,BuptPreset.LOGIN,"probe=temporary; Domain=.bupt.edu.cn; Path=/",false);
        }));
        assertEquals(1,opened.size());assertEquals("my.bupt.edu.cn",opened.get(0).getHost());
    }

    @Test public void secureCookieIsNotSentToHttpCallback()throws Exception{
        final List<SyntheticConnection> opened=new ArrayList<>();
        assertEquals(PortalAccess.Route.UNKNOWN,PortalAccess.probeAnonymous(url->{
            SyntheticConnection connection=opened.isEmpty()
                ?new SyntheticConnection(url,302,CALLBACK,"JSESSIONID=anonymous-probe; Path=/; Secure",false)
                :new SyntheticConnection(url,302,BuptPreset.LOGIN,null,true);
            opened.add(connection);return connection;
        }));
        assertEquals(2,opened.size());String value=opened.get(1).getRequestProperty("Cookie");
        assertTrue(value==null||!value.contains("anonymous-probe"));
    }

    @Test public void missingAnonymousCookieStillDoesNotTreatUnknownPageAsAuthenticated()throws Exception{
        final int[] count={0};
        assertEquals(PortalAccess.Route.UNKNOWN,PortalAccess.probeAnonymous(url->count[0]++==0
            ?new SyntheticConnection(url,302,CALLBACK,null,false)
            :new SyntheticConnection(url,302,BuptPreset.LOGIN,null,true)));
        assertEquals(2,count[0]);assertEquals("返回页面尚未识别，请稍后重试",PortalAccess.problem());
    }

    private static final class SyntheticConnection extends HttpURLConnection {
        private final int status;private final String location,cookie;private final boolean requiresCookie;
        boolean disconnected;
        SyntheticConnection(URL url,int status,String location,String cookie,boolean requiresCookie){super(url);this.status=status;this.location=location;this.cookie=cookie;this.requiresCookie=requiresCookie;}
        private boolean accepted(){String sent=getRequestProperty("Cookie");return !requiresCookie||(sent!=null&&sent.contains("JSESSIONID=anonymous-probe"));}
        @Override public int getResponseCode(){return accepted()?status:200;}
        @Override public String getHeaderField(String name){return "Location".equalsIgnoreCase(name)&&accepted()?location:null;}
        @Override public Map<String,List<String>> getHeaderFields(){Map<String,List<String>> headers=new HashMap<>();if(cookie!=null)headers.put("Set-Cookie",Collections.singletonList(cookie));return headers;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream("<html><head><title>系统提示</title></head><body></body></html>".getBytes(StandardCharsets.UTF_8));}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
    }
}
