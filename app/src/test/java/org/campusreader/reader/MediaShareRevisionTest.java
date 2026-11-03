package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class MediaShareRevisionTest {
    private static final class SimulatedProcessDeath extends Error {}
    @Test public void interruptionAfterDataReplacementCannotReuseOldMetadata()throws Exception{
        org.campusreader.config.CampusSite.clear();
        Context c=RuntimeEnvironment.getApplication();String profile=MediaTransfer.hash("local"),id=MediaTransfer.hash("https://files.example.edu/interrupted");
        File dir=new File(MediaTransfer.root(c),profile);assertTrue(dir.mkdirs());
        File data=new File(dir,id+".data"),meta=new File(dir,id+".json");
        Files.write(data.toPath(),"old bytes".getBytes(StandardCharsets.UTF_8));
        Files.write(meta.toPath(),new JSONObject().put("name","document.txt").put("mime","text/plain").put("revision","11111111-1111-1111-1111-111111111111").toString().getBytes(StandardCharsets.UTF_8));
        Uri old=MediaShareProvider.uri(c,MediaTransfer.read(c,profile,id));
        File temporary=new File(dir,"replacement.part"){
            @Override public boolean renameTo(File target){if(super.renameTo(target))throw new SimulatedProcessDeath();return false;}
        };
        Files.write(temporary.toPath(),"new bytes".getBytes(StandardCharsets.UTF_8));
        java.lang.reflect.Method commit=MediaTransfer.class.getDeclaredMethod("commit",Context.class,String.class,String.class,String.class,File.class,String.class,String.class);commit.setAccessible(true);
        try{commit.invoke(null,c,"local",profile,id,temporary,"document.txt","text/plain");fail("interruption expected");}
        catch(java.lang.reflect.InvocationTargetException failure){assertTrue(failure.getCause() instanceof SimulatedProcessDeath);}
        assertEquals("new bytes",new String(Files.readAllBytes(data.toPath()),StandardCharsets.UTF_8));
        assertFalse(meta.exists());assertNull(MediaTransfer.read(c,profile,id));
        MediaShareProvider provider=Robolectric.buildContentProvider(MediaShareProvider.class).create().get();
        try{provider.openFile(old,"r");fail("old metadata must not expose replacement bytes");}catch(FileNotFoundException expected){}
    }
    @Test public void oldShareUriCannotResolveReplacementAtTheSameDownloadAddress()throws Exception{
        Context c=RuntimeEnvironment.getApplication();String profile=MediaTransfer.hash("synthetic-profile"),id=MediaTransfer.hash("https://files.example.edu/document");
        File dir=new File(MediaTransfer.root(c),profile);assertTrue(dir.mkdirs());
        File data=new File(dir,id+".data"),meta=new File(dir,id+".json");
        Files.write(data.toPath(),"first version".getBytes(StandardCharsets.UTF_8));
        JSONObject value=new JSONObject().put("name","document.txt").put("mime","text/plain").put("revision","11111111-1111-1111-1111-111111111111");
        Files.write(meta.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));
        MediaShareProvider provider=Robolectric.buildContentProvider(MediaShareProvider.class).create().get();
        Uri old=MediaShareProvider.uri(c,MediaTransfer.read(c,profile,id));
        try(android.os.ParcelFileDescriptor fd=provider.openFile(old,"r")){assertNotNull(fd);}
        synchronized(MediaTransfer.class){
            assertTrue(data.delete());Files.write(data.toPath(),"second version".getBytes(StandardCharsets.UTF_8));
            value.put("revision","22222222-2222-2222-2222-222222222222");Files.write(meta.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));
        }
        try{provider.openFile(old,"r");fail("old URI must not authorize new bytes");}catch(FileNotFoundException expected){}
        Uri next=MediaShareProvider.uri(c,MediaTransfer.read(c,profile,id));assertNotEquals(old,next);
        try(android.os.ParcelFileDescriptor fd=provider.openFile(next,"r")){assertNotNull(fd);}
        assertEquals(0,provider.query(old,null,null,null,null).getCount());
        Uri legacy=old.buildUpon().path("/"+profile+"/"+id).build();
        try{provider.openFile(legacy,"r");fail("unversioned grants are not accepted");}catch(FileNotFoundException expected){}
    }
}
