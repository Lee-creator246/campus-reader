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
        @Override public ShortPathApplication getApplicationContext(){return this;}
        @Override public synchronized File getDatabasePath(String name){
            if(databases==null)try{databases=Files.createTempDirectory("cr-ai-ui-").toFile();}
            catch(IOException failure){throw new AssertionError("Cannot create isolated test database directory",failure);}
            return new File(databases,name);
        }
        void dispose(){
            if(databases==null)return;
            File[] files=databases.listFiles();
            if(files!=null)for(File file:files)if(file.isFile())file.delete();
            databases.delete();
        }
    }

    private ShortPathApplication app;
    private ActivityController<? extends Activity> controller;
    private final NoticeKey key=CampusNoticeStore.summaryKey("synthetic-ui","notice-1");

    @Before public void before(){
        app=(ShortPathApplication)RuntimeEnvironment.getApplication();
        app.getSharedPreferences("reader-local",0).edit().clear()
                .putString(ReaderPreferences.lastProfileKey(),"local").commit();
        app.getSharedPreferences("reader-ai-"+SummaryInput.hash("local"),0).edit().clear().commit();
        assertFalse(CampusConfiguration.isConfigured());
        assertFalse(CampusApplication.auth().isSdkInitialized());
    }
    @After public void after(){
        if(controller!=null)controller.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();
        if(app!=null)app.dispose();
    }

    @Test public void settingsOpenDisabledAndBlankWithoutStartingConnections(){
        ActivityController<AiSettingsActivity> opened=Robolectric.buildActivity(AiSettingsActivity.class,
                new Intent(app,AiSettingsActivity.class).putExtra(AiSettingsActivity.EXTRA_PROFILE,"local")).setup();
        controller=opened;
        Activity activity=opened.get();
        Switch optIn=(Switch)findText(activity,"启用手动 AI 摘要");
        assertNotNull(optIn);assertFalse(optIn.isChecked());
        List<EditText> fields=new ArrayList<>();collectEditors(activity.findViewById(android.R.id.content),fields);
        assertEquals(3,fields.size());for(EditText field:fields)assertEquals("",field.getText().toString());
        assertNoAttemptOrCampusConnection();
        assertTrue(app.getSharedPreferences("reader-ai-"+SummaryInput.hash("local"),0).getAll().isEmpty());
    }

    @Test public void cachedSummaryRemainsReadableWhileOffAndPreservesReadingState()throws Exception{
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){
            store.upsert(Collections.singletonList(notice("<p>报名志愿活动，11月5日截止。</p>")),100);
            store.setLater(key,true);
            SummaryInput input=store.summaryInput(key);
            JSONObject json=new JSONObject();
            for(String field:new String[]{"purpose","audience","actions","deadline","location"})
                json.put(field,new JSONObject().put("value",field.equals("purpose")?"报名志愿活动":"")
                        .put("quote",field.equals("purpose")?"报名志愿活动":""));
            assertTrue(store.saveSummaryIfCurrent(key,input.contentHash,input.cacheGeneration,
                    new AiSettings(app,"local").load().configHash,json.toString(),200));
        }
        AiSummaryActivity activity=openSummary();
        assertNotNull(findText(activity,"合成测试通知"));
        assertNotNull(findText(activity,"报名志愿活动"));
        assertNotNull(findText(activity,"原文依据：报名志愿活动"));
        Button generate=(Button)findText(activity,"重新生成（需要再次确认）");
        assertNotNull(generate);assertFalse(generate.isEnabled());
        assertNotNull(findText(activity,"AI 生成已关闭；可在服务设置启用。已有缓存仍可查看。"));
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){
            CachedNotice unchanged=store.find(key);assertFalse(unchanged.read);assertTrue(unchanged.later);
            assertEquals(1,store.summaryCount());
        }
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

    private CampusNotice notice(String body){return new CampusNotice(key,"合成测试通知",10,body,
            "https://portal.example.org/synthetic/notice-1",Collections.emptyList(),Collections.emptyList());}
    private AiSummaryActivity openSummary()throws Exception{
        Intent intent=new Intent(app,AiSummaryActivity.class).putExtra(AiSummaryActivity.EXTRA_PROFILE,"local")
                .putExtra(AiSummaryActivity.EXTRA_SOURCE,key.source).putExtra(AiSummaryActivity.EXTRA_STABLE_ID,key.stableId);
        ActivityController<AiSummaryActivity> opened=Robolectric.buildActivity(AiSummaryActivity.class,intent).setup();
        controller=opened;AiSummaryActivity activity=opened.get();assertFalse(activity.isFinishing());
        Future<?> load=ReflectionHelpers.getField(activity,"task");assertNotNull(load);load.get(5,TimeUnit.SECONDS);
        // Drain only currently due work; the lifecycle monitor intentionally reschedules after 500 ms.
        shadowOf(Looper.getMainLooper()).idle();return activity;
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
