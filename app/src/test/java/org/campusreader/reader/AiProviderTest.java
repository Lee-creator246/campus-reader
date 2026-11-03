package org.campusreader.reader;

import android.app.Application;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class AiProviderTest {
    @Test public void deepSeekPresetUsesOfficialHostAndExplicitNonThinkingJson()throws Exception{
        assertEquals("https://api.deepseek.com/chat/completions",AiProvider.DEEPSEEK.defaultEndpoint);
        assertEquals("deepseek-flash",AiProvider.DEEPSEEK.defaultModel);
        assertEquals(AiProvider.DEEPSEEK.defaultEndpoint,AiProvider.DEEPSEEK.endpoint(AiProvider.DEEPSEEK.defaultEndpoint));
        JSONObject request=new JSONObject();AiProvider.DEEPSEEK.configure(request);
        assertEquals("json_object",request.getJSONObject("response_format").getString("type"));
        assertEquals("disabled",request.getJSONObject("thinking").getString("type"));
    }
    @Test public void customAdapterDoesNotGuessVendorCapabilities()throws Exception{
        JSONObject request=new JSONObject();AiProvider.CUSTOM.configure(request);
        assertFalse(request.has("thinking"));assertFalse(request.has("reasoning_effort"));
        assertEquals("json_object",request.getJSONObject("response_format").getString("type"));
        assertEquals("https://models.example.org/v1/chat/completions",AiProvider.CUSTOM.endpoint("https://models.example.org/v1/chat/completions"));
    }
    @Test public void officialPresetRejectsLookalikeProxyAndUnexpectedPaths(){
        for(String endpoint:new String[]{"https://api.deepseek.com.example.org/chat/completions","https://api.deepseek.com:8443/chat/completions","https://models.example.org/chat/completions","https://api.deepseek.com/other/chat/completions","http://api.deepseek.com/chat/completions","https://api.deepseek.com/chat/completions?key=secret"}){
            try{AiProvider.DEEPSEEK.endpoint(endpoint);fail(endpoint);}catch(IllegalArgumentException expected){}
        }
        assertEquals("https://api.deepseek.com/v1/chat/completions",AiProvider.DEEPSEEK.endpoint("https://api.deepseek.com/v1/chat/completions"));
    }
    @Test public void providerChangesInvalidateCachedResultsEvenForSameEndpoint(){
        AiSettings.Config custom=new AiSettings.Config(false,AiProvider.DEEPSEEK.defaultEndpoint,AiProvider.DEEPSEEK.defaultModel,"",1);
        AiSettings.Config nativeConfig=new AiSettings.Config(false,AiProvider.DEEPSEEK.defaultEndpoint,AiProvider.DEEPSEEK.defaultModel,"",1,AiProvider.DEEPSEEK);
        assertNotEquals(custom.configHash,nativeConfig.configHash);
        assertEquals(AiProvider.CUSTOM,custom.provider);assertEquals(AiProvider.DEEPSEEK,AiProvider.fromId("deepseek"));
        try{AiProvider.fromId("untrusted");fail();}catch(IllegalArgumentException expected){}
    }
}
