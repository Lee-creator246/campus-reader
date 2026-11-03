package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.campusreader.config.CampusSite;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class PortalCredentialStoreTest {
    private final String a="campus:"+repeat('a'),b="campus:"+repeat('b');
    private Context context;private Keys keys;private PortalCredentialStore store;
    private static String repeat(char c){StringBuilder b=new StringBuilder();for(int i=0;i<64;i++)b.append(c);return b.toString();}
    static final class Keys implements PortalCredentialStore.KeyProvider {
        final HashMap<String,SecretKey> values=new HashMap<>();
        public SecretKey get(String alias,boolean create)throws Exception {if(!values.containsKey(alias)&&create){KeyGenerator g=KeyGenerator.getInstance("AES");g.init(256);values.put(alias,g.generateKey());}if(!values.containsKey(alias))throw new IllegalStateException();return values.get(alias);}
        public void delete(String alias){values.remove(alias);}
    }
    @Before public void setup(){context=RuntimeEnvironment.getApplication();CampusSite.install(CampusSite.create("example.edu","https://vpn.example.edu","https://portal.example.edu/notices","https://sso.example.edu/authserver/login"));keys=new Keys();store=new PortalCredentialStore(context,a,keys);}
    @After public void cleanup()throws Exception{store.clear();CampusSite.clear();}
    @Test public void secretIsEncryptedInNoBackupAndOnlyMatchingDestinationCanDecrypt()throws Exception {
        store.save("student-test","synthetic-password-123","https://sso.example.edu/authserver/login?service=x");
        assertTrue(store.exists());assertEquals("synthetic-password-123",store.load("https://sso.example.edu/authserver/login?service=y","student-test").password);
        assertNull(store.load("https://different.example.edu/authserver/login","student-test"));assertNull(store.load("https://sso.example.edu/authserver/login","another-test"));
        File[] files=context.getNoBackupFilesDir().listFiles((dir,name)->name.startsWith("portal-")&&name.endsWith(".bin"));assertNotNull(files);assertEquals(1,files.length);
        String encoded=new String(Files.readAllBytes(files[0].toPath()),StandardCharsets.UTF_8);assertFalse(encoded.contains("synthetic-password"));assertFalse(encoded.contains("student-test"));assertFalse(encoded.contains("sso.example"));
    }
    @Test public void profilesAndSchoolConfigurationAreIsolated()throws Exception {
        store.save("student-test","synthetic-password-123","https://sso.example.edu/authserver/login");
        assertFalse(new PortalCredentialStore(context,b,keys).exists());
        CampusSite.install(CampusSite.create("other.edu","https://vpn.other.edu","https://portal.other.edu/notices","https://sso.other.edu/authserver/login"));
        assertFalse(new PortalCredentialStore(context,a,keys).exists());
    }
    @Test public void tamperingAndMissingDeviceKeyFailClosed()throws Exception {
        store.save("student-test","synthetic-password-123","https://sso.example.edu/authserver/login");keys.values.clear();
        try{store.load("https://sso.example.edu/authserver/login","student-test");fail();}catch(Exception expected){}
        store.save("student-test","synthetic-password-123","https://sso.example.edu/authserver/login");
        File[] files=context.getNoBackupFilesDir().listFiles((dir,name)->name.endsWith(".bin"));assertNotNull(files);Files.write(files[0].toPath(),"{}".getBytes(StandardCharsets.UTF_8));
        try{store.load("https://sso.example.edu/authserver/login","student-test");fail();}catch(Exception expected){}
    }
    @Test public void forgetRemovesFileAndKeyAndHttpCannotBeSaved()throws Exception {
        try{store.save("student-test","synthetic-password-123","http://sso.example.edu/authserver/login");fail();}catch(IllegalArgumentException expected){}
        assertFalse(store.exists());store.save("student-test","synthetic-password-123","https://sso.example.edu/authserver/login");store.clear();assertFalse(store.exists());assertTrue(keys.values.isEmpty());
    }
}
