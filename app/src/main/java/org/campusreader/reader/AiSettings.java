package org.campusreader.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.campusreader.notices.SummaryInput;

/** Explicit opt-in, profile-local service settings. API keys never appear in plaintext preferences. */
public final class AiSettings {
    public static final Object LOCK=new Object();
    private final SharedPreferences prefs;
    private final String owner,alias;
    public static final class Config {
        public final boolean enabled;
        public final String endpoint,model,key,configHash;
        public final long revision;
        Config(boolean enabled,String endpoint,String model,String key,long revision){this.enabled=enabled;this.endpoint=endpoint;this.model=model;this.key=key;this.revision=revision;this.configHash=AiPolicy.configHash(endpoint,model);}
    }
    public AiSettings(Context context,String profile){
        if(profile==null||!(profile.equals("local")||profile.matches("campus:[a-f0-9]{64}")))throw new IllegalArgumentException("Invalid profile");
        owner=SummaryInput.hash(profile);alias="campus-reader-ai-"+owner;
        prefs=context.getApplicationContext().getSharedPreferences("reader-ai-"+owner,Context.MODE_PRIVATE);
    }
    public Config load(){synchronized(LOCK){
        boolean enabled=prefs.getBoolean("enabled",false);String key="";
        if(enabled){try{key=decrypt();}catch(Exception failure){throw new IllegalStateException("API Key 无法解密，请在设置重新填写；没有发送请求");}}
        return new Config(enabled,prefs.getString("endpoint",""),prefs.getString("model",""),key,prefs.getLong("revision",0));
    }}
    public String endpoint(){return prefs.getString("endpoint","");}
    public String model(){return prefs.getString("model","");}
    public boolean enabled(){return prefs.getBoolean("enabled",false);}
    public boolean hasKey(){return prefs.contains("encrypted_key");}
    public void save(boolean enabled,String endpoint,String model,String newKey){synchronized(LOCK){
        String url=endpoint==null?"":endpoint.trim(),name=model==null?"":model.trim();
        if(enabled||!url.isEmpty())url=AiPolicy.endpoint(url);
        if(enabled||!name.isEmpty())name=AiPolicy.model(name);
        if(AiPolicy.requiresNewKey(prefs.getString("endpoint",""),url,hasKey(),newKey))throw new IllegalArgumentException("更换 API 地址时须重新填写对应服务的 API Key，或先删除原密钥；不会沿用旧服务密钥");
        SharedPreferences.Editor edit=prefs.edit();
        if(newKey!=null&&!newKey.trim().isEmpty()){
            String value=AiPolicy.key(newKey);
            try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,encryptionKey(true));cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
                edit.putString("encrypted_key",Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
                edit.putString("key_iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));
            }catch(Exception failure){throw new IllegalStateException("系统安全存储不可用，密钥未保存");}
        }else if(enabled){try{AiPolicy.key(decrypt());}catch(Exception unavailable){throw new IllegalArgumentException("请填写可用的 API Key");}}
        if(!edit.putBoolean("enabled",enabled).putString("endpoint",url).putString("model",name).putLong("revision",prefs.getLong("revision",0)+1).commit())throw new IllegalStateException("设置未能保存，请重试");
    }}
    public void disable(){synchronized(LOCK){if(!prefs.edit().putBoolean("enabled",false).putLong("revision",prefs.getLong("revision",0)+1).commit())throw new IllegalStateException("设置未保存");}}
    public void invalidateRequests(){synchronized(LOCK){if(!prefs.edit().putLong("revision",prefs.getLong("revision",0)+1).commit())throw new IllegalStateException("未能停止旧摘要请求，请重试");}}
    public void deleteKey(){synchronized(LOCK){
        if(!prefs.edit().putBoolean("enabled",false).remove("encrypted_key").remove("key_iv").putLong("revision",prefs.getLong("revision",0)+1).commit())throw new IllegalStateException("密钥未能移除");
        try{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(alias);}catch(Exception failure){throw new IllegalStateException("本机 API Key 已移除；系统加密条目清理未完成");}
    }}
    public boolean isCurrent(Config config){synchronized(LOCK){return config!=null&&config.enabled&&prefs.getBoolean("enabled",false)&&prefs.getLong("revision",0)==config.revision;}}
    public int usedToday(){synchronized(LOCK){return day().equals(prefs.getString("request_day",""))?prefs.getInt("request_count",0):0;}}
    /** Reserve immediately before one confirmed attempt. Failures/cancellation do not refund attempts. */
    public boolean reserve(Config config){synchronized(LOCK){
        if(!isCurrent(config))return false;String today=day();int used=today.equals(prefs.getString("request_day",""))?prefs.getInt("request_count",0):0;if(used>=AiPolicy.DAILY_LIMIT)return false;
        return prefs.edit().putString("request_day",today).putInt("request_count",used+1).commit();
    }}
    private static String day(){return new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date());}
    private String decrypt()throws Exception{
        String encoded=prefs.getString("encrypted_key",""),iv=prefs.getString("key_iv","");if(encoded.isEmpty()||iv.isEmpty())return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,encryptionKey(false),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.decode(encoded,Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    private SecretKey encryptionKey(boolean create)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
        if(!create)throw new IllegalStateException("Missing key");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
