package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import org.campusreader.notices.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

/** Exercises production SQL membership and retained states; uses no SDK or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class RuleStoreTest {
    private CampusNoticeStore store;
    private DatabaseTestContext databaseContext;
    private NoticeKey first,second;
    @Before public void before()throws Exception{
        databaseContext=new DatabaseTestContext(RuntimeEnvironment.getApplication());
        Context context=databaseContext;
        store=new CampusNoticeStore(context,"test-"+UUID.randomUUID());
        store.setRetentionPolicy(new RetentionPolicy(300,20L<<20,-1));
        first=NoticeKey.portalId("fixture","1");second=NoticeKey.portalId("fixture","2");
        store.upsert(Arrays.asList(notice(first,"志愿活动",100,"<p>干部任职说明</p>"),notice(second,"志愿者招募 100%",200,"<p>周末参与</p>")),300);
    }
    private CampusNotice notice(NoticeKey key,String title,long time,String body){return new CampusNotice(key,title,time,body,"https://portal.example.edu/"+key.stableId,Collections.emptyList(),Collections.emptyList());}
    @After public void after(){if(store!=null)store.close();if(databaseContext!=null)databaseContext.dispose();}
    @Test public void previewCountsUseSameSqlAsListAndPreserveFlags(){
        store.setRead(first,true);store.setLater(first,true);NoticeTag tag=store.createTag("关注");store.addTag(first,tag.id);
        KeywordRules rules=new KeywordRules(true,Arrays.asList("任职"),true,Arrays.asList("志愿"));
        NoticeQuery hidden=NoticeQuery.builder(null).priorityKeywords(rules.blockedWords).build();
        NoticeQuery focus=rules.apply(NoticeQuery.builder(null),true).build();
        assertEquals(1,store.count(hidden));assertEquals(1,store.count(focus));assertEquals(second,store.query(focus).get(0).content.key);
        CachedNotice unchanged=store.find(first);assertTrue(unchanged.read);assertTrue(unchanged.later);assertEquals(tag.id,unchanged.tags.get(0).id);
    }
    @Test public void clearedBodiesOnlyMatchTitleAndLiteralWildcardsAreNotExpanded(){
        assertEquals(1,store.count(NoticeQuery.builder(null).search("100%").build()));
        assertEquals(0,store.count(NoticeQuery.builder(null).search("_").build()));
        store.clearBodies();assertEquals(0,store.count(NoticeQuery.builder(null).search("任职").build()));
        assertEquals(2,store.count(NoticeQuery.builder(null).search("志愿").build()));
    }
    @Test public void filterBeforePaginationAndRefreshDoesNotResetUserState(){
        store.setRead(second,true);store.setLater(second,true);
        KeywordRules rules=new KeywordRules(true,Arrays.asList("任职"),false,Collections.emptyList());
        assertEquals(second,store.query(rules.apply(NoticeQuery.builder(null).page(1,0),false).build()).get(0).content.key);
        assertEquals(0,store.upsertMetadata(Arrays.asList(notice(second,"更新的志愿标题",210,"")),900).insertedKeys.size());
        CachedNotice row=store.find(second);assertTrue(row.read);assertTrue(row.later);assertTrue(row.bodyCached);assertTrue(row.content.bodyHtml.contains("周末"));
    }
}
