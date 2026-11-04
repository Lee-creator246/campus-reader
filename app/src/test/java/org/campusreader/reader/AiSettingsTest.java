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
    @Test public void providerSelectionPersistsWithoutEnablingOrCreatingKey(){
        settings.save(false,AiProvider.DEEPSEEK.defaultEndpoint,AiProvider.DEEPSEEK.defaultModel,"",AiProvider.DEEPSEEK);
        AiSettings.Config saved=settings.load();assertFalse(saved.enabled);assertFalse(settings.hasKey());assertEquals(AiProvider.DEEPSEEK,saved.provider);
        assertEquals(AiProvider.DEEPSEEK.defaultModel,saved.model);assertEquals(0,settings.usedToday());
    }
    @Test public void oldNonOfficialSettingsRemainCustomWithoutSecretOrNetworkChanges(){
        prefs.edit().putString("endpoint","https://models.example.org/v1/chat/completions").putString("model","my-model").commit();
        assertEquals(AiProvider.CUSTOM,settings.provider());assertEquals("my-model",settings.load().model);assertFalse(prefs.contains("provider"));
    }
    @Test public void legacyOfficialEndpointsInferDeepSeekWithoutChangingStoredAccountData(){
        for(String endpoint:new String[]{"https://api.deepseek.com/chat/completions","https://api.deepseek.com/v1/chat/completions","https://api.deepseek.com:443/v1/chat/completions"}){
            prefs.edit().clear().putBoolean("enabled",false).putString("endpoint",endpoint).putString("model","deepseek-flash")
                    .putString("encrypted_key","synthetic-ciphertext").putString("key_iv","synthetic-iv").putLong("revision",17).putInt("request_count",4).commit();
            java.util.Map<String,?> original=new java.util.HashMap<>(prefs.getAll());
            AiSettings.Config migrated=settings.load();assertEquals(AiProvider.DEEPSEEK,migrated.provider);assertEquals(endpoint,migrated.endpoint);assertEquals("deepseek-flash",migrated.model);
            assertEquals(17,migrated.revision);assertFalse(migrated.enabled);assertEquals(original,prefs.getAll());assertFalse(prefs.contains("provider"));
            assertNotEquals(new AiSettings.Config(false,endpoint,"deepseek-flash","",17,AiProvider.CUSTOM).configHash,migrated.configHash);
        }
    }
    @Test public void explicitCustomSelectionOnOfficialEndpointIsPreserved(){
        prefs.edit().putString("provider",AiProvider.CUSTOM.id).putString("endpoint",AiProvider.DEEPSEEK.defaultEndpoint).putString("model","deepseek-flash").commit();
        assertEquals(AiProvider.CUSTOM,settings.provider());assertEquals(AiProvider.CUSTOM,settings.load().provider);
    }
    @Test public void legacyOfficialHostLookalikesAndUnsafeUrlsNeverInferDeepSeek(){
        for(String endpoint:new String[]{"http://api.deepseek.com/chat/completions","https://api.deepseek.com.evil.example/chat/completions", "https://api.deepseek.com:8443/chat/completions",
                "https://api.deepseek.com/proxy/chat/completions","https://api.deepseek.com/v1/../chat/completions","https://api.deepseek.com/chat/completions?key=synthetic", "https://user@api.deepseek.com/chat/completions", "https://models.example.org/v1/chat/completions", ""}){
            prefs.edit().clear().putString("endpoint",endpoint).commit();assertEquals(endpoint,AiProvider.CUSTOM,settings.provider());assertFalse(prefs.contains("provider"));
        }
    }
    @Test public void unknownExplicitProviderDoesNotSilentlyMigrate(){
        prefs.edit().putString("provider","unknown-provider").putString("endpoint",AiProvider.DEEPSEEK.defaultEndpoint).commit();
        try{settings.provider();fail();}catch(IllegalArgumentException expected){}
        assertEquals("unknown-provider",prefs.getString("provider",""));
    }
    @Test public void selectingDeepSeekCannotCarryPreviousDestinationKey(){
        prefs.edit().putString("endpoint","https://models.example.org/v1/chat/completions").putString("encrypted_key","synthetic-ciphertext").putString("key_iv","synthetic-iv").commit();
        try{settings.save(false,AiProvider.DEEPSEEK.defaultEndpoint,AiProvider.DEEPSEEK.defaultModel,"",AiProvider.DEEPSEEK);fail();}catch(IllegalArgumentException expected){}
        assertEquals(AiProvider.CUSTOM,settings.provider());assertEquals("https://models.example.org/v1/chat/completions",settings.endpoint());
        assertEquals("synthetic-ciphertext",prefs.getString("encrypted_key",""));assertEquals(0,prefs.getLong("revision",0));
    }
    @Test public void invalidDeepSeekDestinationLeavesSavedConfigurationUntouched(){
        settings.save(false,AiProvider.DEEPSEEK.defaultEndpoint,AiProvider.DEEPSEEK.defaultModel,"",AiProvider.DEEPSEEK);long revision=settings.load().revision;
        try{settings.save(false,"https://evil.example.org/chat/completions","model","",AiProvider.DEEPSEEK);fail();}catch(IllegalArgumentException expected){}
        assertEquals(AiProvider.DEEPSEEK.defaultEndpoint,settings.endpoint());assertEquals(revision,settings.load().revision);
    }
    @Test public void dailyLimitCanChangeWithoutResettingAttemptsOrInvalidatingService(){
        prefs.edit().putBoolean("enabled",true).putString("endpoint","https://api.example.org/v1/chat/completions").putString("model","model").commit();AiSettings.Config config=settings.load();assertEquals(20,settings.dailyLimit());
        settings.setDailyLimit(1);assertTrue(settings.reserve(config));assertFalse(settings.reserve(config));settings.setDailyLimit(3);assertEquals(1,settings.usedToday());assertTrue(settings.isCurrent(config));assertTrue(settings.reserve(config));assertEquals(2,settings.usedToday());
        settings.setDailyLimit(1);assertFalse(settings.reserve(config));assertEquals(2,settings.usedToday());
    }
    @Test public void invalidDailyLimitDoesNotChangeSavedValue(){for(int invalid:new int[]{0,-1,101})try{settings.setDailyLimit(invalid);fail();}catch(IllegalArgumentException expected){}assertEquals(20,settings.dailyLimit());}
}
