package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import android.webkit.CookieManager;
import org.campusreader.auth.CampusConfiguration;
import org.campusreader.config.BuptPreset;
import org.campusreader.config.CampusSite;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** No network or SDK calls: verifies fixed configuration and independent portal identity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class PortalAccessTest {
    /** Chromium is a deep proxy in Robolectric and never runs this completion callback.
     * Keep it explicitly pending so the test also verifies the real publication barrier. */
    @org.robolectric.annotation.Implements(android.webkit.WebView.class)
    public static class ControlledClientCertificates extends org.robolectric.shadows.ShadowWebView {
        static Runnable completion;
        @org.robolectric.annotation.Implementation protected static void clearClientCertPreferences(Runnable done){completion=done;}
    }
    private Context app;
    @Before public void before()throws Exception{
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("campus-configuration",0).edit().clear().commit();
        app.getSharedPreferences("portal-account",0).edit().clear().commit();
        for(String name:new String[]{"loaded","consent","changing","restartRequired"}){
            java.lang.reflect.Field f=CampusConfiguration.class.getDeclaredField(name);f.setAccessible(true);f.setBoolean(null,false);
        }
        PortalAccess.invalidate();CampusSite.clear();
    }
    @After public void after(){CampusSite.clear();PortalAccess.invalidate();PortalLoginLaunch.clear();}
    @Test public void firstInstallHasPublicCampusConfigurationWithoutSdkConsent(){
        CampusConfiguration.load(app);
        assertTrue(CampusConfiguration.isConfigured());assertFalse(CampusConfiguration.hasConsent());
        assertEquals("my.bupt.edu.cn",CampusSite.requireConfigured().portalHost);
        assertTrue(CampusSite.loginUrl().startsWith("https://auth.bupt.edu.cn/authserver/login?"));
        try{CampusConfiguration.requireConnectionAllowed();fail();}catch(IllegalStateException expected){}
    }
    @Test public void existingCampusScopeIsPreservedAcrossUpgrade(){
        CampusSite.Profile old=CampusSite.create(BuptPreset.DOMAIN,BuptPreset.VPN,BuptPreset.LIST,"http://my.bupt.edu.cn/system/resource/code/auth/clogin.jsp?owner=1664271694");
        app.getSharedPreferences("campus-configuration",0).edit().putString("domain",old.domain).putString("vpn_url",old.vpnUrl)
            .putString("portal_list_url",old.portalListUrl).putString("login_url",old.loginUrl).putBoolean("connection_sdk_consent",true).commit();
        CampusConfiguration.load(app);assertEquals(old.scopeId,CampusSite.scopeId());
        assertTrue(CampusConfiguration.hasConsent());
    }
    @Test public void consentSurvivesRestartAndDoesNotChangeCacheScope()throws Exception{
        CampusConfiguration.load(app);String scope=CampusSite.scopeId();CampusConfiguration.acceptConnectionConsent(app);
        java.lang.reflect.Field loaded=CampusConfiguration.class.getDeclaredField("loaded");loaded.setAccessible(true);loaded.setBoolean(null,false);
        CampusConfiguration.load(app);assertTrue(CampusConfiguration.hasConsent());assertEquals(scope,CampusSite.scopeId());
    }
    @Test public void persistedPortalAccountWorksWithoutVpnAndCannotClaimReachability(){
        CampusSite.install(BuptPreset.profile());
        app.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),"student-one").putString("route","DIRECT").putBoolean("ready",true).commit();
        assertEquals("student-one",PortalAccess.account(app));
        String profile=PortalAccess.currentProfile(app);assertTrue(profile.startsWith("campus:"));assertFalse(PortalAccess.isReady(app));
        PortalAccess.invalidate();assertEquals(profile,PortalAccess.currentProfile(app));
        app.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),"student-two").commit();
        assertNotEquals(profile,PortalAccess.currentProfile(app));
    }
    @Test public void selectingSameAccountKeepsSuccessfulLoginCookies(){
        CampusSite.install(BuptPreset.profile());
        app.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),"student-one").commit();
        CookieManager.getInstance().setCookie(BuptPreset.LIST,"session=synthetic");
        final boolean[] ready={false};PortalAccess.selectAccount(app,"student-one",()->ready[0]=true);
        assertTrue(ready[0]);assertTrue(CookieManager.getInstance().getCookie(BuptPreset.LIST).contains("synthetic"));
    }
    @Test @Config(shadows=ControlledClientCertificates.class) public void accountSwitchClearsSessionBeforePublishingNewIdentity(){
        CampusSite.install(BuptPreset.profile());
        app.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),"student-one").commit();
        CookieManager.getInstance().setCookie(BuptPreset.LIST,"session=old-account");
        ReaderPreferences prefs=new ReaderPreferences(app);prefs.markPortalSession(PortalAccess.currentProfile(app));long generation=prefs.getPortalGeneration();
        final boolean[] ready={false};PortalAccess.selectAccount(app,"student-two",()->{
            ready[0]=true;String cookie=CookieManager.getInstance().getCookie(BuptPreset.LIST);
            assertTrue(cookie==null||!cookie.contains("old-account"));assertEquals("student-two",PortalAccess.account(app));
        });
        assertFalse("Identity waits for Chromium certificate cleanup",ready[0]);
        assertEquals("student-one",PortalAccess.account(app));
        assertNotNull(ControlledClientCertificates.completion);
        ControlledClientCertificates.completion.run();ControlledClientCertificates.completion=null;
        assertTrue(ready[0]);assertEquals("",prefs.getPortalProfile());assertTrue(prefs.getPortalGeneration()>generation);
    }
    @Test public void onlyTransportFailuresCanRequestVpnFallback(){
        assertTrue(PortalLoginActivity.isTransportFailure(new java.net.UnknownHostException()));
        assertTrue(PortalLoginActivity.isTransportFailure(new java.net.ConnectException()));
        assertTrue(PortalLoginActivity.isTransportFailure(new java.net.SocketTimeoutException()));
        assertFalse(PortalLoginActivity.isTransportFailure(new javax.net.ssl.SSLException("synthetic")));
        assertFalse(PortalLoginActivity.isTransportFailure(new java.io.IOException("synthetic")));
        assertFalse(PortalLoginActivity.isTransportFailure(new IllegalArgumentException("synthetic")));
    }
    @Test public void onlyRecognizedHttpsAuthenticationCanProveLoginReachability(){
        CampusSite.install(BuptPreset.profile());
        assertTrue(PortalAccess.isKnownLogin(BuptPreset.LOGIN));
        assertFalse(PortalAccess.isKnownLogin("http://auth.bupt.edu.cn/authserver/login"));
        assertFalse(PortalAccess.isKnownLogin("https://auth.bupt.edu.cn:444/authserver/login"));
        assertFalse(PortalAccess.isKnownLogin("https://auth.bupt.edu.cn.evil.invalid/authserver/login"));
        assertFalse(PortalAccess.isKnownLogin("https://unrelated.bupt.edu.cn/login"));
        assertTrue(CampusSite.allowsPortalUrl("http://my.bupt.edu.cn/system/resource/code/auth/clogin.jsp?owner=1664271694"));
    }
    @Test public void oneUsePasswordHandoffCannotLeakToAnotherProfile(){
        PortalLoginLaunch.put("campus:one","student-one","synthetic-secret",true);
        assertNull(PortalLoginLaunch.take("campus:two"));assertNull(PortalLoginLaunch.take("campus:one"));
        PortalLoginLaunch.put("campus:one","student-one","synthetic-secret",true);
        assertEquals("synthetic-secret",PortalLoginLaunch.take("campus:one").password);assertNull(PortalLoginLaunch.take("campus:one"));
    }
    @Test public void portalHomeResolvesToVerifiedMobileFeedWithoutChangingCacheScope(){
        CampusSite.install(BuptPreset.profile());String scope=CampusSite.scopeId();
        assertEquals("http://my.bupt.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1154",PortalAccess.noticeListUrl());
        assertEquals(BuptPreset.LIST,CampusSite.portalListUrl());assertEquals(scope,CampusSite.scopeId());
    }
    @Test public void existingMobileFeedKeepsItsAddressAndAccountScope(){
        String list="https://my.bupt.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1154";
        CampusSite.install(CampusSite.create(BuptPreset.DOMAIN,BuptPreset.VPN,list,BuptPreset.LOGIN));String scope=CampusSite.scopeId();
        assertEquals(list,PortalAccess.noticeListUrl());assertEquals(scope,CampusSite.scopeId());
    }
    @Test public void homeResolutionPreservesHttpsAndDoesNotRewriteOtherCampusPages(){
        CampusSite.install(CampusSite.create(BuptPreset.DOMAIN,BuptPreset.VPN,BuptPreset.LIST.replace("http:","https:"),BuptPreset.LOGIN));
        assertTrue(PortalAccess.noticeListUrl().startsWith("https://my.bupt.edu.cn/list.jsp?"));
        String list="https://portal.campus.example.edu/xs_index.jsp?wbtreeid=1000";
        CampusSite.install(CampusSite.create("campus.example.edu","https://vpn.campus.example.edu",list,"https://auth.campus.example.edu/login"));
        assertEquals(list,PortalAccess.noticeListUrl());
    }
}
