package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.campusreader.notices.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class AiSummaryStoreTest {
    private DatabaseTestContext context;private CampusNoticeStore store;private String profile;private NoticeKey key;
    private static final String CONFIG=SummaryInput.hash("synthetic-service-model-prompt");
    @Before public void before()throws Exception{context=new DatabaseTestContext(RuntimeEnvironment.getApplication());profile="summary-test-"+UUID.randomUUID();store=new CampusNoticeStore(context,profile);store.setRetentionPolicy(new RetentionPolicy(300,20L<<20,-1));key=CampusNoticeStore.summaryKey("fixture","id:1");store.upsert(Collections.singletonList(notice("活动报名","<p>11月5日截止报名，地点：中心。</p>")),100);}
    private CampusNotice notice(String title,String body){return new CampusNotice(key,title,10,body,"https://portal.example.org/notice/1",Collections.emptyList(),Collections.emptyList());}
    private boolean save(SummaryInput input){return store.saveSummaryIfCurrent(key,input.contentHash,input.cacheGeneration,CONFIG,"{\"synthetic\":true}",200);}
    @After public void after(){if(store!=null)store.close();if(context!=null)context.dispose();}
    @Test public void cachedSourceContainsNoMetadataAndDoesNotChangeReadingFlags(){
        NoticeTag tag=store.createTag("关注");store.addTag(key,tag.id);store.setLater(key,true);store.setRead(key,true);
        SummaryInput input=store.summaryInput(key);assertFalse(input.bodyText.contains("<p>"));assertFalse(input.bodyText.contains("https://"));assertTrue(save(input));assertEquals(1,store.summaryCount());
        assertNotNull(store.findSummary(key,input.contentHash,CONFIG));assertNull(store.findSummary(key,input.contentHash,SummaryInput.hash("other-model")));
        CachedNotice unchanged=store.find(key);assertTrue(unchanged.read);assertTrue(unchanged.later);assertEquals(tag.id,unchanged.tags.get(0).id);
    }
    @Test public void unchangedRefreshRetainsSummaryButTitleOrBodyChangesInvalidate(){
        SummaryInput original=store.summaryInput(key);assertTrue(save(original));store.upsert(Collections.singletonList(notice("活动报名","<p>11月5日截止报名，地点：中心。</p>")),300);assertEquals(1,store.summaryCount());
        store.upsertMetadata(Collections.singletonList(notice("活动更名","")),400);assertEquals(0,store.summaryCount());assertFalse(save(original));
        SummaryInput renamed=store.summaryInput(key);assertTrue(save(renamed));store.upsert(Collections.singletonList(notice("活动更名","<p>11月6日截止。</p>")),500);assertEquals(0,store.summaryCount());assertFalse(save(renamed));
    }
    @Test public void clearEpochRejectsLateResultsEvenWhenSourceIsUnchanged(){
        SummaryInput before=store.summaryInput(key);assertTrue(save(before));assertEquals(1,store.clearSummaries());assertFalse(save(before));assertEquals(0,store.summaryCount());
        SummaryInput after=store.summaryInput(key);assertTrue(after.cacheGeneration>before.cacheGeneration);assertTrue(save(after));assertEquals(1,store.summaryCount());
    }
    @Test public void bodyEvictionRemovesSummaryAndLateSaveCannotReviveIt(){SummaryInput input=store.summaryInput(key);assertTrue(save(input));store.clearBodies();assertNull(store.summaryInput(key));assertEquals(0,store.summaryCount());assertFalse(save(input));assertNotNull(store.find(key));}
    @Test public void cacheIsAccountScopedAndOneRecordPerBody(){SummaryInput input=store.summaryInput(key);assertTrue(save(input));assertTrue(save(input));assertEquals(1,store.summaryCount());try(CampusNoticeStore other=new CampusNoticeStore(context,profile+"-other")){assertEquals(0,other.summaryCount());assertNull(other.findSummary(key,input.contentHash,CONFIG));}}
    @Test public void oversizeResultFailsWithoutChangingArticle(){SummaryInput input=store.summaryInput(key);char[] huge=new char[16385];Arrays.fill(huge,'a');try{store.saveSummaryIfCurrent(key,input.contentHash,input.cacheGeneration,CONFIG,new String(huge),200);fail();}catch(IllegalArgumentException expected){}assertEquals(0,store.summaryCount());assertNotNull(store.find(key));}
    @Test public void v2MigrationRetainsExistingArticleAndFlags(){
        store.setRead(key,true);store.setLater(key,true);SQLiteDatabase db=store.getWritableDatabase();db.execSQL("DROP TRIGGER invalidate_notice_summary");db.execSQL("DROP TABLE notice_summaries");db.execSQL("DROP TABLE summary_control");db.setVersion(2);store.close();
        store=new CampusNoticeStore(context,profile);assertEquals(3,store.getReadableDatabase().getVersion());assertTrue(store.find(key).read);assertTrue(store.find(key).later);assertTrue(save(store.summaryInput(key)));
    }
    @Test public void v1MigrationBuildsBothMetadataAndSummaryTables(){
        String legacy="legacy-summary-"+UUID.randomUUID();String name="campus-notices-"+SummaryInput.hash(legacy)+".db";
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)){
            db.execSQL("CREATE TABLE campus_notices(source TEXT NOT NULL,stable_id TEXT NOT NULL,title TEXT NOT NULL,published_ms INTEGER NOT NULL,body_html TEXT NOT NULL,original_url TEXT NOT NULL,images_json TEXT NOT NULL,attachments_json TEXT NOT NULL,is_read INTEGER NOT NULL DEFAULT 0,is_later INTEGER NOT NULL DEFAULT 0,refreshed_ms INTEGER NOT NULL,PRIMARY KEY(source,stable_id))");
            db.execSQL("CREATE TABLE campus_connections(source TEXT PRIMARY KEY NOT NULL,state TEXT NOT NULL)");
            db.execSQL("INSERT INTO campus_notices VALUES('fixture','id:1','旧通知',10,'<p>仍保留正文</p>','https://portal.example.org/notice/1','[]','[]',1,1,20)");db.setVersion(1);
        }
        try(CampusNoticeStore upgraded=new CampusNoticeStore(context,legacy)){assertEquals(3,upgraded.getReadableDatabase().getVersion());CachedNotice article=upgraded.find(key);assertTrue(article.read);assertTrue(article.later);assertTrue(article.bodyCached);SummaryInput input=upgraded.summaryInput(key);assertTrue(upgraded.saveSummaryIfCurrent(key,input.contentHash,input.cacheGeneration,CONFIG,"{}",300));}
    }
}
