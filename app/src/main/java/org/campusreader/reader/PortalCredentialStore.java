package org.campusreader.reader;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.campusreader.config.CampusSite;
import org.campusreader.notices.SummaryInput;
import org.json.JSONObject;

/** Device-local encrypted credentials. Never stored in preferences, backups, or exported source. */
final class PortalCredentialStore {
    interface KeyProvider { SecretKey get(String alias,boolean create)throws Exception; void delete(String alias)throws Exception; }
    static final class Credentials {
        final String account,password,binding;
        Credentials(String account,String password,String binding){this.account=account;this.password=password;this.binding=binding;}
    }
    private static final Object LOCK=new Object();
    private final AtomicFile file;
    private final String owner,alias;
    private final KeyProvider keys;
    PortalCredentialStore(Context context,String profile){this(context,profile,new AndroidKeys());}
    PortalCredentialStore(Context context,String profile,KeyProvider keys){
        if(profile==null||!profile.matches("campus:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid profile");
        owner=SummaryInput.hash(CampusSite.scopeId()+"\0"+profile);alias="campus-reader-portal-"+owner;this.keys=keys;
        file=new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(),"portal-"+owner+".bin"));
    }
    boolean exists(){synchronized(LOCK){return file.getBaseFile().exists();}}
    void save(String account,String password,String target)throws Exception {synchronized(LOCK){
        String binding=PortalLoginPolicy.binding(target);PortalLoginPolicy.password(password);
        if(account==null||account.isEmpty()||account.length()>128)throw new IllegalArgumentException("Invalid account");
        JSONObject plain=new JSONObject().put("account",account).put("password",password).put("binding",binding);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keys.get(alias,true));cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
        JSONObject encoded=new JSONObject().put("version",1).put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .put("data",Base64.encodeToString(cipher.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
        FileOutputStream stream=null;
        try{stream=file.startWrite();stream.write(encoded.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}
        catch(Exception failure){if(stream!=null)file.failWrite(stream);throw failure;}
    }}
    Credentials load(String target,String expectedAccount)throws Exception {synchronized(LOCK){
        if(!exists())return null;String binding=PortalLoginPolicy.binding(target);
        if(file.getBaseFile().length()>16384)throw new IOException("Invalid credential file");
        byte[] data;
        try(InputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int count;while((count=in.read(buffer))!=-1){if(out.size()+count>16384)throw new IOException("Invalid credential file");out.write(buffer,0,count);}data=out.toByteArray();
        }
        JSONObject encoded=new JSONObject(new String(data,StandardCharsets.UTF_8));if(encoded.getInt("version")!=1)throw new IOException("Unsupported credential version");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,keys.get(alias,false),new GCMParameterSpec(128,Base64.decode(encoded.getString("iv"),Base64.NO_WRAP)));cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
        JSONObject plain=new JSONObject(new String(cipher.doFinal(Base64.decode(encoded.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
        if(!binding.equals(plain.getString("binding"))||!expectedAccount.equals(plain.getString("account")))return null;
        return new Credentials(expectedAccount,PortalLoginPolicy.password(plain.getString("password")),binding);
    }}
    void clear()throws Exception {synchronized(LOCK){file.delete();keys.delete(alias);}}
    private static final class AndroidKeys implements KeyProvider {
        public SecretKey get(String alias,boolean create)throws Exception {
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
            if(!create)throw new IllegalStateException("Missing device key");
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
        }
        public void delete(String alias)throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(alias);}
    }
}
