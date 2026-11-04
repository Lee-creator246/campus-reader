package org.campusreader.reader;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import org.campusreader.auth.CampusApplication;
import org.campusreader.auth.CampusConfiguration;
import org.campusreader.notices.CachedNotice;
import org.campusreader.notices.CampusNotice;
import org.campusreader.notices.CampusNoticeStore;
import org.campusreader.notices.NoticeKey;
import org.campusreader.notices.SummaryInput;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Host UI smoke checks with synthetic local data; no device or AI service is contacted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AiUiTest.ShortPathApplication.class,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AiUiTest {
    /** Keep native SQLite paths short even when Robolectric names its sandbox after a test. */
    public static class ShortPathApplication extends CampusApplication {
        private File databases;
        private File privateFiles;
        @Override public ShortPathApplication getApplicationContext(){return this;}
        @Override public synchronized File getDatabasePath(String name){
            if(databases==null)try{databases=Files.createTempDirectory("cr-ai-ui-").toFile();}
            catch(IOException failure){throw new AssertionError("Cannot create isolated test database directory",failure);}
            return new File(databases,name);
        }
        @Override public synchronized File getNoBackupFilesDir(){
            if(privateFiles==null)try{privateFiles=Files.createTempDirectory("cr-ai-private-").toFile();}
            catch(IOException failure){throw new AssertionError(failure);}
            return privateFiles;
        }
        void dispose(){
            if(databases==null)return;
            File[] files=databases.listFiles();
            if(files!=null)for(File file:files)if(file.isFile())file.delete();
            databases.delete();
            if(privateFiles!=null){File[] policyFiles=privateFiles.listFiles();if(policyFiles!=null)for(File file:policyFiles)if(file.isFile())file.delete();privateFiles.delete();}
        }
    }

    private ShortPathApplication app;
    private ActivityController<? extends Activity> controller;
    private final NoticeKey key=CampusNoticeStore.summaryKey("synthetic-ui","notice-1");

    @Before public void before(){
        app=(ShortPathApplication)RuntimeEnvironment.getApplication();
        // Pure-module tests may clear CampusSite independently of the Android loader's static flag.
        // Install this test's own default configuration; never rely on another sandbox's startup.
        app.getSharedPreferences("campus-configuration",0).edit().clear().commit();
        for(String field:new String[]{"loaded","consent","changing","restartRequired"})ReflectionHelpers.setStaticField(CampusConfiguration.class,field,false);
        org.campusreader.config.CampusSite.clear();CampusConfiguration.load(app);
        app.getSharedPreferences("portal-account",0).edit().clear().commit();PortalAccess.invalidate();
        app.getSharedPreferences("reader-local",0).edit().clear()
                .putString(ReaderPreferences.lastProfileKey(),"local").commit();
        app.getSharedPreferences("reader-ai-"+SummaryInput.hash("local"),0).edit().clear().commit();
        assertTrue(CampusConfiguration.isConfigured());
        assertFalse(CampusConfiguration.hasConsent());
        assertFalse(CampusApplication.auth().isSdkInitialized());
    }
    @After public void after(){
        if(controller!=null)controller.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();
        if(app!=null)app.dispose();
        org.campusreader.config.CampusSite.clear();
        ReflectionHelpers.setStaticField(CampusConfiguration.class,"loaded",false);
    }

    @Test public void settingsOpenDisabledAndBlankWithoutStartingConnections(){
        ActivityController<AiSettingsActivity> opened=Robolectric.buildActivity(AiSettingsActivity.class,
                new Intent(app,AiSettingsActivity.class).putExtra(AiSettingsActivity.EXTRA_PROFILE,"local")).setup();
        controller=opened;
        Activity activity=opened.get();
        Switch optIn=(Switch)findText(activity,"启用 AI 服务");
        assertNotNull(optIn);assertFalse(optIn.isChecked());
        List<EditText> fields=new ArrayList<>();collectEditors(activity.findViewById(android.R.id.content),fields);
        assertEquals(3,fields.size());for(EditText field:fields)assertEquals("",field.getText().toString());
        assertNoAttemptOrCampusConnection();
        assertTrue(app.getSharedPreferences("reader-ai-"+SummaryInput.hash("local"),0).getAll().isEmpty());
    }

    @Test public void cachedSummaryRemainsReadableWhileOffAndPreservesReadingState()throws Exception{
        seedCachedSummary(false);
        AiSummaryActivity activity=openSummary();
        assertNotNull(findText(activity,"合成测试通知"));
        assertNotNull(findText(activity,"报名志愿活动，11月5日截止。"));
        assertNotNull(findText(activity,"查看原文依据 · 正文第 1 段"));
        Button generate=(Button)findText(activity,"重新生成（需要再次确认）");
        assertNotNull(generate);assertFalse(generate.isEnabled());
        assertNotNull(findText(activity,"AI 生成已关闭；可在服务设置启用。已有缓存仍可查看。"));
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){
            CachedNotice unchanged=store.find(key);assertFalse(unchanged.read);assertTrue(unchanged.later);
            assertEquals(1,store.summaryCount());
        }
        assertNoAttemptOrCampusConnection();
    }

    @Test public void acceptingSuggestedTagAppendsAndPreservesManualTagAndReadingFlags()throws Exception{
        seedCachedSummary(false);AiSummaryActivity activity=openSummary();
        Button accept=(Button)findText(activity,"采纳标签：志愿实践");assertNotNull(accept);accept.performClick();awaitTask(activity);
        assertNotNull(findText(activity,"已采纳：志愿实践"));
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){
            CachedNotice saved=store.find(key);assertFalse(saved.read);assertTrue(saved.later);
            List<String> names=new ArrayList<>();for(org.campusreader.notices.NoticeTag tag:store.tags(key))names.add(tag.name);
            assertTrue(names.contains("个人标签"));assertTrue(names.contains("志愿实践"));assertEquals(2,names.size());
        }
        assertNoAttemptOrCampusConnection();
    }

    @Test public void policyReferenceHasSourcePositionsAndIsNotPresentedAsFormalBasis()throws Exception{
        seedCachedSummary(true);AiSummaryActivity activity=openSummary();
        assertNotNull(findText(activity,"模型建议关联，适用性待核对；不构成通知正式引用或政策效力判断。"));
        Button quote=(Button)findText(activity,"核对政策依据 · 第 1 页 · 第 1 段");assertNotNull(quote);quote.performClick();awaitTask(activity);
        android.app.AlertDialog dialog=ReflectionHelpers.getField(activity,"openEvidenceDialog");assertNotNull(dialog);assertTrue(dialog.isShowing());
        assertNotNull(findText(dialog.getWindow().getDecorView(),"志愿活动材料提交说明。参加志愿活动需提供申请材料。"));
        assertNoAttemptOrCampusConnection();
    }

    @Test public void changingPolicyClearsVisibleCacheAndClosesOpenEvidence()throws Exception{
        seedCachedSummary(true);AiSummaryActivity activity=openSummary();
        ((Button)findText(activity,"核对政策依据 · 第 1 页 · 第 1 段")).performClick();awaitTask(activity);
        android.app.AlertDialog dialog=ReflectionHelpers.getField(activity,"openEvidenceDialog");assertNotNull(dialog);
        new PolicyLibrary(app,"local").clear();
        shadowOf(Looper.getMainLooper()).idleFor(500,TimeUnit.MILLISECONDS);awaitTask(activity);
        assertFalse(dialog.isShowing());assertNull(findText(activity,"采纳标签：志愿实践"));
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){assertEquals(0,store.summaryCount());assertTrue(store.find(key).later);}
        assertNoAttemptOrCampusConnection();
    }

    @Test public void changedNoticeCannotApplyOldSuggestedTag()throws Exception{
        seedCachedSummary(false);AiSummaryActivity activity=openSummary();
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){store.upsert(Collections.singletonList(notice("<p>活动取消，请勿报名。</p>")),300);}
        ((Button)findText(activity,"采纳标签：志愿实践")).performClick();awaitTask(activity);
        assertNotNull(findText(activity,"资料已变化或标签未能保存，请返回后重新检查。"));
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){assertEquals(1,store.tags(key).size());assertEquals("个人标签",store.tags(key).get(0).name);}
        assertNoAttemptOrCampusConnection();
    }

    @Test public void metadataOnlyNoticeExplainsMissingBodyWithoutGenerating()throws Exception{
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){
            store.upsertMetadata(Collections.singletonList(notice("")),100);
        }
        AiSummaryActivity activity=openSummary();
        assertNotNull(findText(activity,"正文未缓存或没有可用文字"));
        assertNotNull(findText(activity,"请返回通知重新获取正文。图片通知和附件不参与本版摘要。"));
        Button generate=(Button)findText(activity,"生成摘要");assertNotNull(generate);assertFalse(generate.isEnabled());
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){
            assertFalse(store.find(key).read);assertEquals(0,store.summaryCount());
        }
        assertNoAttemptOrCampusConnection();
    }

    @Test public void leavingDuringGenerationExplainsCancellationWithoutRetryOrStateLoss()throws Exception{
        seedCachedSummary(false);AiSummaryActivity activity=openSummary();AiTransport.Cancellation cancel=new AiTransport.Cancellation();
        ReflectionHelpers.setField(activity,"busy",true);ReflectionHelpers.setField(activity,"cancellation",cancel);
        controller.pause().stop();
        try{cancel.check();fail("Leaving must cancel the in-flight call");}catch(java.io.IOException expected){assertEquals("CANCELLED",expected.getMessage());}
        controller.start().resume();awaitTask(activity);
        assertNotNull(findText(activity,AiSummaryActivity.LEAVING_NOTICE));assertNotNull(findText(activity,"报名志愿活动，11月5日截止。"));
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){assertFalse(store.find(key).read);assertTrue(store.find(key).later);assertEquals(1,store.tags(key).size());assertEquals(1,store.summaryCount());}
        assertNoAttemptOrCampusConnection();
    }

    private CampusNotice notice(String body){return new CampusNotice(key,"合成测试通知",10,body,
            "https://portal.example.org/synthetic/notice-1",Collections.emptyList(),Collections.emptyList());}
    private void seedCachedSummary(boolean policy)throws Exception{
        PolicyLibrary library=new PolicyLibrary(app,"local");
        if(policy)library.add(new PolicyLibrary.Metadata("志愿活动办法","合成发布方","测试版","","","全体学生","待核验","合成资料.txt"),
                Collections.singletonList("志愿活动材料提交说明。参加志愿活动需提供申请材料。"));
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){
            store.upsert(Collections.singletonList(notice("<p>报名志愿活动，11月5日截止。</p>")),100);store.setLater(key,true);
            store.addTag(key,store.createTag("个人标签").id);SummaryInput input=store.summaryInput(key);
            AiEvidence.Snapshot evidence=AiEvidence.build(input,library.snapshot(),Collections.singletonList("个人标签"));
            JSONObject fields=new JSONObject();for(String name:AiGroundedSummary.FIELDS)fields.put(name,new JSONArray());
            fields.put("purpose",new JSONArray().put("N2"));
            JSONArray links=new JSONArray();if(policy){AiEvidence.Unit found=null;for(AiEvidence.Unit unit:evidence.units)if(unit.kind.equals("policy")){found=unit;break;}assertNotNull(found);
                links.put(new JSONObject().put("notice","N2").put("policy",found.id));}
            JSONObject raw=new JSONObject().put("schema",2).put("fields",fields).put("tags",new JSONArray().put(new JSONObject().put("name","志愿实践").put("evidence",new JSONArray().put("N2")))).put("policy_links",links);
            AiGroundedSummary parsed=AiGroundedSummary.parse(raw.toString(),evidence);
            assertTrue(store.saveSummaryIfCurrent(key,input.contentHash,input.cacheGeneration,evidence.cacheHash(new AiSettings(app,"local").load().configHash),parsed.json,200));
        }
    }
    private AiSummaryActivity openSummary()throws Exception{
        Intent intent=new Intent(app,AiSummaryActivity.class).putExtra(AiSummaryActivity.EXTRA_PROFILE,"local")
                .putExtra(AiSummaryActivity.EXTRA_SOURCE,key.source).putExtra(AiSummaryActivity.EXTRA_STABLE_ID,key.stableId);
        ActivityController<AiSummaryActivity> opened=Robolectric.buildActivity(AiSummaryActivity.class,intent).setup();
        controller=opened;AiSummaryActivity activity=opened.get();assertFalse(activity.isFinishing());
        awaitTask(activity);return activity;
    }
    private void awaitTask(AiSummaryActivity activity)throws Exception{
        Future<?> load=ReflectionHelpers.getField(activity,"task");assertNotNull(load);load.get(10,TimeUnit.SECONDS);
        // Drain only currently due work; the lifecycle monitor intentionally reschedules after 500 ms.
        shadowOf(Looper.getMainLooper()).idle();
    }
    private void assertNoAttemptOrCampusConnection(){
        AiSettings settings=new AiSettings(app,"local");assertFalse(settings.enabled());assertEquals(0,settings.usedToday());
        assertFalse(settings.hasKey());assertEquals("",settings.endpoint());assertFalse(CampusApplication.auth().isSdkInitialized());
    }
    private static TextView findText(Activity activity,String value){return findText(activity.findViewById(android.R.id.content),value);}
    private static TextView findText(View view,String value){
        if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){
            TextView match=findText(group.getChildAt(i),value);if(match!=null)return match;
        }}return null;
    }
    private static void collectEditors(View view,List<EditText> fields){
        if(view instanceof EditText)fields.add((EditText)view);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectEditors(group.getChildAt(i),fields);}
    }
}
