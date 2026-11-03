package org.campusreader.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.webkit.CookieManager;
import android.widget.EditText;
import org.campusreader.auth.*;
import org.campusreader.config.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CampusApplication.class,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SchoolPickerTest {
    private ActivityController<CampusConfigActivity> controller;
    private Context context;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();}
    @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();CampusSite.clear();ReflectionHelpers.setStaticField(CampusConfiguration.class,"consent",false);}
    private CampusSite.Profile current(){CampusSite.Profile site=CampusSite.create("campus.example.edu","https://vpn.campus.example.edu/","https://portal.campus.example.edu/notices",null);CampusSite.install(site);ReflectionHelpers.setStaticField(CampusConfiguration.class,"consent",true);return site;}
    @Test public void unchangedSavePreservesSessionAndDoesNotStartSdk(){
        CampusSite.Profile site=current();CookieManager.getInstance().setCookie(site.portalListUrl,"session=synthetic; Path=/");
        controller=Robolectric.buildActivity(CampusConfigActivity.class).setup();CampusConfigActivity screen=controller.get();
        assertTrue(CampusConfiguration.isUnchanged(site));ReflectionHelpers.callInstanceMethod(screen,"saveConfiguration");
        assertEquals(Activity.RESULT_CANCELED,Shadows.shadowOf(screen).getResultCode());assertTrue(screen.isFinishing());
        assertFalse(CampusConfiguration.isChanging());assertFalse(CampusConfiguration.requiresRestart());assertFalse(CampusApplication.auth().isSdkInitialized());
        assertTrue(CookieManager.getInstance().getCookie(site.portalListUrl).contains("session=synthetic"));
    }
    @Test public void cancellingSchoolSelectionKeepsCurrentFormAndConsent(){
        CampusSite.Profile site=current();controller=Robolectric.buildActivity(CampusConfigActivity.class).setup();CampusConfigActivity screen=controller.get();
        SchoolDirectory.Entry alternative=SchoolDirectory.parse(SchoolDirectoryTest.DOCUMENT.replace("campus.example.edu","other.example.edu")).schools.get(0);
        ReflectionHelpers.callInstanceMethod(screen,"selectSchool",ReflectionHelpers.ClassParameter.from(SchoolDirectory.Entry.class,alternative));
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        EditText domain=ReflectionHelpers.getField(screen,"domain");assertEquals(site.domain,domain.getText().toString());assertEquals(site.scopeId,CampusSite.scopeId());assertFalse(CampusApplication.auth().isSdkInitialized());
    }
    @Test public void offlineDirectorySurvivesReopenAndInvalidImportPreservesIt()throws Exception{
        current();SchoolDirectoryStore store=new SchoolDirectoryStore(context);store.save(SchoolDirectoryTest.DOCUMENT);
        assertEquals("示例大学",new SchoolDirectoryStore(context).load().schools.get(0).name);
        try{store.save("{\"password\":\"dummy\"}");fail();}catch(IllegalArgumentException expected){}
        assertEquals(1,store.load().schools.size());assertEquals("campus.example.edu",CampusSite.domain());assertFalse(CampusApplication.auth().isSdkInitialized());
    }
    @Test public void cancelledLegacyImportDoesNotInstallNewSite(){
        CampusSite.Profile site=current();controller=Robolectric.buildActivity(CampusConfigActivity.class).setup();CampusConfigActivity screen=controller.get();
        CampusSite.Profile other=CampusSite.create("other.example.edu","https://vpn.other.example.edu","https://portal.other.example.edu/notices",null);
        ReflectionHelpers.callInstanceMethod(screen,"previewImport",ReflectionHelpers.ClassParameter.from(CampusSite.Profile.class,other));
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals(site.scopeId,CampusSite.scopeId());assertFalse(CampusApplication.auth().isSdkInitialized());
    }
}
