package org.campusreader.reader;

import android.app.Application;
import android.content.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.campusreader.notices.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class PolicyLibraryTest {
    private Context context;private File directory;private String profile;private PolicyLibrary library;
    @Before public void before()throws Exception{
        directory=Files.createTempDirectory("cr-pol-").toFile();profile="campus:"+SummaryInput.hash(UUID.randomUUID().toString());
        context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getNoBackupFilesDir(){return directory;}
            @Override public File getDatabasePath(String name){return new File(directory,name);}
        };library=new PolicyLibrary(context,profile);
    }
    @After public void after()throws Exception{if(directory!=null)try(java.util.stream.Stream<Path> files=Files.walk(directory.toPath())){files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException ignored){}});}}
    private PolicyLibrary.Metadata metadata(String title){return new PolicyLibrary.Metadata(title,"资料发布方","2026版","2026-01-01","2026-12-31","本科生","待核验","synthetic.txt");}
    @Test public void storageIsLocalImmutableAndScoped()throws Exception{
        List<String> pages=new ArrayList<>(Arrays.asList("第一条：志愿活动。","第二条：申请要求。"));PolicyLibrary.Document added=library.add(metadata("合成活动政策"),pages);pages.set(0,"changed");
        PolicyLibrary.Snapshot snapshot=library.snapshot();assertEquals(1,snapshot.revision);assertEquals("第一条：志愿活动。",snapshot.documents.get(0).pages.get(0));
        try{snapshot.documents.clear();fail();}catch(UnsupportedOperationException expected){}
        try{added.pages.add("injected");fail();}catch(UnsupportedOperationException expected){}
        assertEquals(0,new PolicyLibrary(context,"local").snapshot().documents.size());
        File saved=new File(directory,"policies-"+SummaryInput.hash(profile)+".json");assertTrue(saved.exists());assertTrue(new String(Files.readAllBytes(saved.toPath()),java.nio.charset.StandardCharsets.UTF_8).contains("第一条"));
        assertTrue(library.isCurrent(snapshot.revision));library.update(added.id,metadata("修订合成活动政策"));assertFalse(library.isCurrent(snapshot.revision));
        assertNotEquals(added.contentHash,library.snapshot().documents.get(0).contentHash);assertEquals(added.pages,library.snapshot().documents.get(0).pages);
    }
    @Test public void addingEditingDeletingAndClearingInvalidateSummaryAndLateWrites(){
        NoticeKey key=CampusNoticeStore.summaryKey("synthetic","id:policy-test");CampusNotice notice=new CampusNotice(key,"合成活动通知",10,"<p>活动报名条件。</p>","https://portal.example.org/notice/1",Collections.emptyList(),Collections.emptyList());
        try(CampusNoticeStore store=new CampusNoticeStore(context,profile)){
            store.upsert(Collections.singletonList(notice),100);store.setRead(key,true);store.setLater(key,true);NoticeTag tag=store.createTag("人工标签");store.addTag(key,tag.id);
            String config=SummaryInput.hash("test");SummaryInput original=store.summaryInput(key);assertTrue(store.saveSummaryIfCurrent(key,original.contentHash,original.cacheGeneration,config,"{}",200));
            long settingsBefore=new AiSettings(context,profile).load().revision;PolicyLibrary.Document added=library.add(metadata("合成政策"),Collections.singletonList("条件以原通知为准。"));
            assertEquals(0,store.summaryCount());assertTrue(new AiSettings(context,profile).load().revision>settingsBefore);assertFalse(store.saveSummaryIfCurrent(key,original.contentHash,original.cacheGeneration,config,"{}",300));
            for(Runnable mutation:Arrays.<Runnable>asList(()->library.update(added.id,metadata("修订标题")),()->library.delete(added.id),()->library.clear())){
                SummaryInput now=store.summaryInput(key);assertTrue(store.saveSummaryIfCurrent(key,now.contentHash,now.cacheGeneration,config,"{}",400));long revision=library.revision();mutation.run();assertTrue(library.revision()>revision);assertEquals(0,store.summaryCount());assertFalse(store.saveSummaryIfCurrent(key,now.contentHash,now.cacheGeneration,config,"{}",500));
            }
            assertTrue(store.find(key).read);assertTrue(store.find(key).later);assertEquals("人工标签",store.tags(key).get(0).name);
        }
    }
    @Test public void limitsDuplicatesAndMalformedMetadataFailWithoutMutation(){
        PolicyLibrary.Document original=library.add(metadata("合成政策"),Collections.singletonList("原文。"));long revision=library.revision();
        try{library.add(metadata("合成政策"),Collections.singletonList("原文。"));fail();}catch(IllegalArgumentException expected){}
        try{library.add(metadata("空政策"),Collections.singletonList("  "));fail();}catch(IllegalArgumentException expected){}
        try{library.add(metadata("太多页"),Collections.nCopies(41,"文字"));fail();}catch(IllegalArgumentException expected){}
        char[] huge=new char[100001];Arrays.fill(huge,'x');try{library.add(metadata("超限"),Collections.singletonList(new String(huge)));fail();}catch(IllegalArgumentException expected){}
        try{new PolicyLibrary.Metadata("日期错误","","","2026-02-30","","","待核验","");fail();}catch(IllegalArgumentException expected){}
        try{new PolicyLibrary.Metadata("日期倒置","","","2026-12-01","2026-01-01","","待核验","");fail();}catch(IllegalArgumentException expected){}
        try{new PolicyLibrary.Metadata("未知状态","","","","","","官方已验证","");fail();}catch(IllegalArgumentException expected){}
        assertEquals(revision,library.revision());assertEquals(original.contentHash,library.snapshot().documents.get(0).contentHash);
        for(int i=1;i<20;i++)library.add(metadata("合成政策"+i),Collections.singletonList("原文"+i));
        try{library.add(metadata("第21份"),Collections.singletonList("文字"));fail();}catch(IllegalArgumentException expected){}
        assertEquals(20,library.snapshot().documents.size());
    }
    @Test public void totalCharacterLimitRejectsWholeDocument(){
        char[] chars=new char[100000];Arrays.fill(chars,'a');String page=new String(chars);for(int i=0;i<10;i++)library.add(metadata("合成大资料"+i),Collections.singletonList(page));
        long revision=library.revision();try{library.add(metadata("超总量"),Collections.singletonList("x"));fail();}catch(IllegalArgumentException expected){}
        assertEquals(revision,library.revision());assertEquals(10,library.snapshot().documents.size());
    }
    @Test public void freshProcessCanReadPersistedPagesAndMetadata()throws Exception{
        PolicyLibrary.Document doc=library.add(metadata("持久化合成资料"),Arrays.asList("第1页。","","第3页。"));
        java.lang.reflect.Field field=PolicyLibrary.class.getDeclaredField("CACHE");field.setAccessible(true);synchronized(AiSettings.LOCK){((Map<?,?>)field.get(null)).clear();}
        PolicyLibrary.Snapshot reloaded=new PolicyLibrary(context,profile).snapshot();assertEquals(1,reloaded.revision);assertEquals(doc.contentHash,reloaded.documents.get(0).contentHash);assertEquals(3,reloaded.documents.get(0).pages.size());
    }
    @Test public void profilesRejectPathsAndMalformedIdentifiers(){for(String invalid:new String[]{null,"../local","campus:abc","other"})try{new PolicyLibrary(context,invalid);fail();}catch(IllegalArgumentException expected){}}
    @Test public void cancellationAfterDiskCommitNeverLeavesOldPolicySnapshotCached()throws Exception{
        PolicyLibrary.Document old=library.add(metadata("旧政策"),Collections.singletonList("旧条件。"));long before=library.revision();
        java.lang.reflect.Field field=PolicyLibrary.class.getDeclaredField("file");field.setAccessible(true);android.util.AtomicFile original=(android.util.AtomicFile)field.get(library);
        field.set(library,new android.util.AtomicFile(original.getBaseFile()){
            @Override public void finishWrite(FileOutputStream output){super.finishWrite(output);Thread.currentThread().interrupt();}
        });
        try{library.delete(old.id);fail();}catch(IllegalStateException expected){}finally{Thread.interrupted();}
        PolicyLibrary.Snapshot actual=library.snapshot();assertEquals(before+1,actual.revision);assertTrue(actual.documents.isEmpty());assertFalse(library.isCurrent(before));
    }
    @Test public void processCacheRetainsAtMostThreeProfiles()throws Exception{
        java.lang.reflect.Field field=PolicyLibrary.class.getDeclaredField("CACHE");field.setAccessible(true);
        for(int i=0;i<6;i++)new PolicyLibrary(context,"campus:"+SummaryInput.hash("profile-cache-"+i)).snapshot();
        synchronized(AiSettings.LOCK){assertTrue(((Map<?,?>)field.get(null)).size()<=3);}
    }
}
