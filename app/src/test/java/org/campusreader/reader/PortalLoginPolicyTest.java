package org.campusreader.reader;

import android.app.Application;
import org.campusreader.config.CampusSite;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class PortalLoginPolicyTest {
    private static final String PAGE="https://sso.example.edu/authserver/login";
    private boolean accepts(String page,String action,String id,String method,int users,int passwords,boolean execution,boolean event){return PortalLoginPolicy.accepts(page,action,id,method,users,passwords,execution,event);}
    @After public void cleanup(){CampusSite.clear();}
    @Test public void recognizedCasFormRequiresExactHttpsHostPathAndStructure(){
        assertTrue(accepts(PAGE,PAGE+"?service=test","loginForm","post",1,1,true,true));
        assertTrue(accepts(PAGE,PAGE,"casLoginForm","POST",1,1,true,true));
        assertFalse(accepts(PAGE,"https://evil.example.edu/authserver/login","loginForm","post",1,1,true,true));
        assertFalse(accepts(PAGE,"https://sso.example.edu/other/login","loginForm","post",1,1,true,true));
        assertFalse(accepts(PAGE.replace("https:","http:"),PAGE,"loginForm","post",1,1,true,true));
        assertFalse(accepts(PAGE,PAGE,"arbitrary","post",1,1,true,true));
        assertFalse(accepts(PAGE,PAGE,"loginForm","get",1,1,true,true));
        assertFalse(accepts(PAGE,PAGE,"loginForm","post",2,1,true,true));
        assertFalse(accepts(PAGE,PAGE,"loginForm","post",1,1,false,true));
        assertFalse(accepts(PAGE,PAGE,"loginForm","post",1,1,true,false));
    }
    @Test public void rejectedBindingsIncludeUserInfoPortsFragmentsAndNonLoginPages(){
        for(String value:new String[]{"http://sso.example.edu/authserver/login","https://user@sso.example.edu/authserver/login","https://sso.example.edu:444/authserver/login",PAGE+"#test","https://sso.example.edu/notices","javascript:alert(1)"}){
            try{PortalLoginPolicy.binding(value);fail(value);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void upgradeOnlyKnownCampusLoginNavigation(){
        CampusSite.install(CampusSite.create("example.edu","https://vpn.example.edu","http://portal.example.edu/notices",null));
        assertEquals(PAGE+"?service=http://portal.example.edu/",PortalLoginActivity.upgradeLoginUrl("http://sso.example.edu/authserver/login?service=http://portal.example.edu/"));
        assertEquals("http://evil.org/login",PortalLoginActivity.upgradeLoginUrl("http://evil.org/login"));
        assertEquals("http://sso.example.edu:8080/login",PortalLoginActivity.upgradeLoginUrl("http://sso.example.edu:8080/login"));
        assertEquals("http://portal.example.edu/notices",PortalLoginActivity.upgradeLoginUrl("http://portal.example.edu/notices"));
    }
    @Test public void portalAccountCannotSilentlyChangeCurrentCampusIdentity(){
        assertEquals("student-test",PortalLoginPolicy.account(" student-test ","student-test"));
        try{PortalLoginPolicy.account("other-test","student-test");fail();}catch(IllegalArgumentException expected){}
    }
}
