package org.campusreader.reader;

import android.app.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.campusreader.notices.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AiUiTest.ShortPathApplication.class,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AiCoreCacheTest {
    private AiUiTest.ShortPathApplication app;private NoticeKey key;private AiSettings settings;private SummaryInput source;
    @Before public void before(){app=(AiUiTest.ShortPathApplication)RuntimeEnvironment.getApplication();app.getSharedPreferences("reader-local",0).edit().putString(ReaderPreferences.lastProfileKey(),"local").commit();app.getSharedPreferences("reader-ai-"+SummaryInput.hash("local"),0).edit().clear().commit();settings=new AiSettings(app,"local");key=CampusNoticeStore.summaryKey("synthetic-core","one");try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){store.upsert(Collections.singletonList(notice("报名志愿活动，11月5日截止。")),100);source=store.summaryInput(key);}}
    @After public void after(){AiSummaryPresentation.invalidateCache();app.dispose();}
    private CampusNotice notice(String body){return new CampusNotice(key,"合成测试通知",10,"<p>"+body+"</p>","https://portal.example.org/core",Collections.emptyList(),Collections.emptyList());}
    private AiBriefSummary summary(AiEvidence.Snapshot context)throws Exception{
        AiEvidence.Unit unit=context.unit("N2");JSONObject raw=new JSONObject().put("schema",3).put("overview",new JSONObject().put("text","志愿活动报名，11月5日截止。").put("refs",new JSONArray().put(new JSONObject().put("id",unit.id).put("quote",unit.text))))
            .put("points",new JSONArray()).put("tags",new JSONArray().put("志愿实践")).put("policy_links",new JSONArray());
        return AiBriefSummary.parse(raw.toString(),context);
    }
    private void save(boolean core)throws Exception{
        AiEvidence.Snapshot context=AiAutoSummaries.context(source,new PolicyLibrary(app,"local").snapshot(),false);AiBriefSummary result=summary(context);
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){assertTrue(store.saveSummaryIfCurrent(key,source.contentHash,source.cacheGeneration,AiAutoSummaries.cacheHash(context,settings.cacheConfig()),result.json,200));if(core)assertTrue(AiCoreCache.save(store,key,source,settings.cacheConfig(),result,200));}
    }
    @Test public void policyEditPreservesNoticeOnlyOverviewAndCitationWithoutNewRequest()throws Exception{
        save(true);new PolicyLibrary(app,"local").add(new PolicyLibrary.Metadata("志愿活动办法","","","","","学生","待核验","local.txt"),Collections.singletonList("志愿活动报名及资料审核办法。"));
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){assertEquals(0,store.summaryCount());}
        AiSummaryPresentation.Cached cached=AiSummaryPresentation.loadCached(app,"local",key);assertNotNull(cached);assertTrue(cached.coreOnly);assertEquals("志愿活动报名，11月5日截止。",cached.snippet);assertTrue(cached.summary.links.isEmpty());assertEquals(0,cached.context.policyCount());assertEquals(0,settings.usedToday());
    }
    @Test public void previousV9FullResultIsPreservedBeforePolicyInvalidation()throws Exception{
        save(false);new PolicyLibrary(app,"local").add(new PolicyLibrary.Metadata("志愿活动办法","","","","","学生","待核验","local.txt"),Collections.singletonList("志愿活动报名及资料审核办法。"));assertNotNull(AiSummaryPresentation.loadCached(app,"local",key));
    }
    @Test public void changedBodyDeletesCoreAndCannotShowOldSummary()throws Exception{
        save(true);assertNotNull(AiSummaryPresentation.loadCached(app,"local",key));try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){store.upsert(Collections.singletonList(notice("活动取消，请勿报名。")),300);assertNull(AiCoreCache.find(store,key,store.summaryInput(key),settings.cacheConfig()));}assertNull(AiSummaryPresentation.loadCached(app,"local",key));
    }
    @Test public void explicitClearingRemovesCoreWhilePreservingReadingFlags()throws Exception{
        save(true);try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){store.setLater(key,true);store.clearSummaries();assertEquals(1,AiCoreCache.clear(store));assertTrue(store.find(key).later);assertFalse(store.find(key).read);}assertNull(AiSummaryPresentation.loadCached(app,"local",key));
    }
    @Test public void bodyCacheReclamationAlsoDeletesCoreReferences()throws Exception{
        save(true);try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){android.content.ContentValues values=new android.content.ContentValues();values.put("body_cached",0);store.getWritableDatabase().update("campus_notices",values,"source=? AND stable_id=?",new String[]{key.source,key.stableId});assertEquals(0,android.database.DatabaseUtils.queryNumEntries(store.getReadableDatabase(),"ai_core_summaries"));assertNull(store.summaryInput(key));}assertNull(AiSummaryPresentation.loadCached(app,"local",key));
    }
    @Test public void attachedPreviewRefreshesAfterAutomaticCompletionAndUnregistersOnDetach()throws Exception{
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();Activity activity=controller.get();View preview=AiSummaryPresentation.view(activity,"local",key);activity.setContentView(preview);controller.visible();shadowOf(Looper.getMainLooper()).idle();await(preview);
        assertTrue(flatten(preview).contains("暂无摘要"));save(true);AiAutoSummaries.resultUpdated(app);shadowOf(Looper.getMainLooper()).idle();await(preview);assertTrue(flatten(preview).contains("志愿活动报名，11月5日截止。"));
        activity.setContentView(new LinearLayout(activity));assertFalse((Boolean)ReflectionHelpers.getField(preview,"registered"));controller.pause().stop().destroy();
    }
    private void await(View view)throws Exception{Future<?> task=ReflectionHelpers.getField(view,"load");assertNotNull(task);task.get(10,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();}
    private String flatten(View v){StringBuilder text=new StringBuilder();if(v instanceof TextView)text.append(((TextView)v).getText());if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)text.append(flatten(((ViewGroup)v).getChildAt(i)));return text.toString();}
}
