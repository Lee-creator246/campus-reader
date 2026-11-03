package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.campusreader.notices.SummaryInput;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class AiSettingsTest {
    private Context context;private AiSettings settings;private SharedPreferences prefs;
    @Before public void before(){context=RuntimeEnvironment.getApplication();prefs=context.getSharedPreferences("reader-ai-"+SummaryInput.hash("local"),0);prefs.edit().clear().commit();settings=new AiSettings(context,"local");}
    @Test public void defaultIsOffAndSettingsDoNotContainPlainKey(){assertFalse(settings.enabled());assertFalse(settings.load().enabled);assertFalse(settings.hasKey());assertTrue(prefs.getAll().isEmpty());}
    @Test public void changingDestinationCannotReuseExistingKeyEvenWhileDisabled(){
        prefs.edit().putString("endpoint","https://a.example.org/v1/chat/completions").putString("model","model").putString("encrypted_key","synthetic-ciphertext").putString("key_iv","synthetic-iv").commit();
        try{settings.save(false,"https://b.example.org/v1/chat/completions","model","");fail();}catch(IllegalArgumentException expected){}
        assertEquals("https://a.example.org/v1/chat/completions",settings.endpoint());assertEquals("synthetic-ciphertext",prefs.getString("encrypted_key",""));
    }
    @Test public void eachExplicitAttemptConsumesQuotaAndDisableInvalidatesOldConfig(){
        prefs.edit().putBoolean("enabled",true).putString("endpoint","https://api.example.org/v1/chat/completions").putString("model","model").commit();AiSettings.Config snapshot=settings.load();
        for(int i=0;i<20;i++)assertTrue(settings.reserve(snapshot));assertFalse(settings.reserve(snapshot));assertEquals(20,settings.usedToday());
        settings.disable();assertFalse(settings.isCurrent(snapshot));assertFalse(settings.reserve(snapshot));assertEquals(20,settings.usedToday());
    }
    @Test public void cacheClearingInvalidationAndServiceSettingsDoNotResetQuota(){
        prefs.edit().putBoolean("enabled",true).putString("endpoint","https://api.example.org/v1/chat/completions").putString("model","model").commit();AiSettings.Config snapshot=settings.load();assertTrue(settings.reserve(snapshot));
        settings.invalidateRequests();assertFalse(settings.isCurrent(snapshot));assertEquals(1,settings.usedToday());settings.save(false,settings.endpoint(),settings.model(),"");assertEquals(1,settings.usedToday());
    }
}
