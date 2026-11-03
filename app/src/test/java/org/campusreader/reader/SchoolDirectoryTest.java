package org.campusreader.reader;

import org.campusreader.config.CampusSite;
import org.campusreader.config.SchoolDirectory;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class SchoolDirectoryTest {
    public static final String ENTRY="{\"id\":\"sample\",\"name\":\"示例大学\",\"connection\":\"sangfor\",\"portalAdapter\":\"campus-html-v1\",\"domain\":\"campus.example.edu\",\"vpnUrl\":\"https://vpn.campus.example.edu/\",\"portalListUrl\":\"https://portal.campus.example.edu/notices\"}";
    public static final String DOCUMENT="{\"format\":\"campus-reader-schools\",\"version\":1,\"schools\":["+ENTRY+"]}";
    private static void rejected(String document){try{SchoolDirectory.parse(document);fail("Unsafe directory accepted");}catch(IllegalArgumentException expected){}}
    @Test public void validDirectorySearchesWithoutChangingCurrentSite()throws Exception{
        CampusSite.clear();SchoolDirectory directory=SchoolDirectory.parse(DOCUMENT);
        assertEquals(1,directory.search("大学").size());assertEquals(1,directory.search("CAMPUS.EXAMPLE").size());assertEquals(0,directory.search("missing").size());assertFalse(CampusSite.isConfigured());
        assertEquals("示例大学",SchoolDirectory.parse(SchoolDirectory.readText(new ByteArrayInputStream(DOCUMENT.getBytes(StandardCharsets.UTF_8)))).schools.get(0).name);
        assertEquals(directory.schools.get(0).profile.portalListUrl,directory.schools.get(0).profile.loginUrl);
    }
    @Test public void rejectsSchemaChangesAndDuplicateKeys(){
        rejected(DOCUMENT.replace("\"version\":1","\"version\":2"));rejected(DOCUMENT.replace("\"version\":1","\"version\":\"1\""));
        rejected(DOCUMENT.replace("\"version\":1","\"version\":1,\"version\":1"));rejected(DOCUMENT.replace("\"name\":","\"name\":\"Fake\",\"name\":"));
        rejected(DOCUMENT.replace("\"version\":1","\"version\":1,\"script\":\"run\""));rejected(DOCUMENT.replace("\"id\":","\"password\":\"dummy\",\"id\":"));
    }
    @Test public void rejectsUnsupportedAdaptersAndMalformedLabels(){
        rejected(DOCUMENT.replace("sangfor","generic-vpn"));rejected(DOCUMENT.replace("campus-html-v1","download-script"));
        rejected(DOCUMENT.replace("示例大学","\\u202e大学"));rejected(DOCUMENT.replace("示例大学","\\ud800"));rejected(DOCUMENT.replace("\"sample\"","\"../sample\""));
    }
    @Test public void rejectsDuplicateSchoolsAndOversizedCollections(){
        rejected(DOCUMENT.replace(ENTRY,ENTRY+","+ENTRY));StringBuilder entries=new StringBuilder();
        for(int i=0;i<101;i++){if(i>0)entries.append(',');entries.append(ENTRY.replace("sample","sample"+i));}
        rejected(DOCUMENT.replace(ENTRY,entries.toString()));rejected(DOCUMENT.replace(ENTRY,""));
    }
    @Test public void siteRulesApplyToEveryEntry(){
        rejected(DOCUMENT.replace("https://vpn.","http://vpn."));rejected(DOCUMENT.replace("https://portal.campus.example.edu/notices","https://evil.example.org/notices"));
        rejected(DOCUMENT.replace("/notices","/notices?password=dummy"));
    }
    @Test public void rejectsInvalidUtf8AndOversizedStream()throws Exception{
        try{SchoolDirectory.readText(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}));fail();}catch(IllegalArgumentException expected){}
        try{SchoolDirectory.readText(new ByteArrayInputStream(new byte[SchoolDirectory.MAX_BYTES+1]));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void downloadPolicyRejectsCredentialsRedirectTargetsAndLocalLiteralAddresses(){
        assertEquals("https://directory.example.org/schools.json",SchoolDirectory.downloadUrl("https://directory.example.org/schools.json"));
        for(String value:new String[]{"http://directory.example.org/s.json","https://user:dummy@directory.example.org/s.json","https://directory.example.org/s.json?token=dummy","https://directory.example.org/#secret","https://127.0.0.1/s.json","https://localhost/s.json","https://host.local/s.json","https://directory.example.org:444/s.json"}){
            try{SchoolDirectory.downloadUrl(value);fail("Untrusted download URL accepted");}catch(IllegalArgumentException expected){}
        }
    }
}
