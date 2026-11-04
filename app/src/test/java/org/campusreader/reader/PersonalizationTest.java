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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class PersonalizationTest {
    private static final String PROFILE="campus:"+String.join("",Collections.nCopies(64,"a")),OTHER="campus:"+String.join("",Collections.nCopies(64,"b"));
    private Context context;private DatabaseTestContext database;private CampusNoticeStore store;private CachedNotice volunteer,administration;
    @Before public void setup()throws Exception{
        database=new DatabaseTestContext(RuntimeEnvironment.getApplication());context=database;
        for(String file:Arrays.asList("interest-profiles","recommendation-feedback","keyword-rules","notification-delivery"))context.getSharedPreferences(file,Context.MODE_PRIVATE).edit().clear().commit();
        store=new CampusNoticeStore(context,"recommendation-"+UUID.randomUUID());store.setRetentionPolicy(new RetentionPolicy(100,5L<<20,-1));
        long now=System.currentTimeMillis();NoticeKey first=NoticeKey.portalId("fixture","volunteer"),second=NoticeKey.portalId("fixture","administration");
        store.upsert(Arrays.asList(notice(first,"志愿服务招募",now,"<p>面向本科生开放，报名需提交申请表。</p>"),notice(second,"岗位任职公示",now,"<p>公开岗位信息。</p>")),now);volunteer=store.find(first);administration=store.find(second);
    }
    private CampusNotice notice(NoticeKey key,String title,long now,String body){return new CampusNotice(key,title,now,body,"https://portal.example.edu/"+key.stableId,Collections.emptyList(),Collections.emptyList());}
    @After public void cleanup(){store.close();database.dispose();}
    @Test public void explicitInterestsAreLocalOptionalAndAccountScoped(){
        assertFalse(PreferenceProfile.isOnboardingDone(context,PROFILE));
        assertTrue(PreferenceProfile.create(Arrays.asList("志愿实践"),"本科生",true,true).save(context,PROFILE));
        assertFalse(PreferenceProfile.load(context,PROFILE).loadFailed);
        assertTrue(PreferenceProfile.isOnboardingDone(context,PROFILE));assertFalse(PreferenceProfile.isOnboardingDone(context,OTHER));
        assertEquals("本科生",PreferenceProfile.load(context,PROFILE).audience);assertTrue(PreferenceProfile.skip(context,OTHER));assertTrue(PreferenceProfile.load(context,OTHER).topics.isEmpty());
    }
    @Test public void hardBlockingWinsOverExplicitAndLearnedInterests(){
        PreferenceProfile.create(Arrays.asList("志愿实践"),"未选择",true,true).save(context,PROFILE);
        KeywordRules rules=new KeywordRules(true,Arrays.asList("申请表"),true,Arrays.asList("志愿"));rules.save(context,PROFILE);
        RecommendationEngine.feedback(context,PROFILE,volunteer,true);
        assertEquals(Integer.MIN_VALUE,RecommendationEngine.score(context,PROFILE,volunteer));assertEquals("",RecommendationEngine.priorityReason(context,PROFILE,volunteer,rules));
        assertEquals(Collections.singletonList(administration),RecommendationEngine.sort(context,PROFILE,Arrays.asList(volunteer,administration)));
    }
    @Test public void interestsAndFeedbackImproveRankWithoutUsingReadingFlags(){
        int initial=RecommendationEngine.score(context,PROFILE,volunteer);
        store.setRead(volunteer.content.key,true);store.setLater(volunteer.content.key,true);
        assertEquals(initial,RecommendationEngine.score(context,PROFILE,store.find(volunteer.content.key)));
        assertTrue(RecommendationEngine.feedback(context,PROFILE,volunteer,true));assertTrue(RecommendationEngine.score(context,PROFILE,volunteer)>initial);
        assertEquals(initial,RecommendationEngine.score(context,OTHER,volunteer));
        assertEquals("",RecommendationEngine.priorityReason(context,PROFILE,volunteer,KeywordRules.empty()));
        PreferenceProfile.create(Arrays.asList("志愿实践"),"未选择",true,true).save(context,PROFILE);
        assertEquals(volunteer,RecommendationEngine.sort(context,PROFILE,Arrays.asList(administration,volunteer)).get(0));assertEquals("主题：志愿实践",RecommendationEngine.priorityReason(context,PROFILE,volunteer,KeywordRules.empty()));
    }
    @Test public void learningCanBeDisabledAndResetWithoutChangingRules(){
        new KeywordRules(false,Collections.emptyList(),true,Arrays.asList("公益")).save(context,PROFILE);
        RecommendationEngine.feedback(context,PROFILE,volunteer,true);int learned=RecommendationEngine.score(context,PROFILE,volunteer);assertTrue(RecommendationEngine.resetFeedback(context,PROFILE));assertTrue(learned>RecommendationEngine.score(context,PROFILE,volunteer));
        PreferenceProfile.create(Collections.emptySet(),"未选择",false,false).save(context,PROFILE);assertFalse(RecommendationEngine.feedback(context,PROFILE,volunteer,true));assertEquals(Arrays.asList("公益"),KeywordRules.load(context,PROFILE).priorityWords);
    }
    @Test public void unknownAudienceDoesNotFilterAndWordRepetitionDoesNotMultiplyScore(){
        PreferenceProfile.create(Collections.emptySet(),"研究生",true,false).save(context,PROFILE);assertEquals(2,RecommendationEngine.sort(context,PROFILE,Arrays.asList(volunteer,administration)).size());
        KeywordRules rules=new KeywordRules(false,Collections.emptyList(),true,Arrays.asList("志愿"));rules.save(context,PROFILE);
        int score=RecommendationEngine.score(context,PROFILE,volunteer);CampusNotice original=volunteer.content;store.upsert(Collections.singletonList(notice(original.key,"志愿志愿志愿志愿",original.publishedAtMillis,original.bodyHtml)),System.currentTimeMillis());assertEquals(score,RecommendationEngine.score(context,PROFILE,store.find(original.key)));
    }
    @Test public void invalidPreferencesDoNotTriggerHighPriorityThemeAlerts(){
        context.getSharedPreferences("interest-profiles",Context.MODE_PRIVATE).edit().putString(PreferenceProfile.key(PROFILE),"broken").commit();
        assertTrue(PreferenceProfile.load(context,PROFILE).loadFailed);assertEquals("",RecommendationEngine.priorityReason(context,PROFILE,volunteer,KeywordRules.empty()));
        context.getSharedPreferences("keyword-rules",Context.MODE_PRIVATE).edit().putString("rules:"+PROFILE,"broken").commit();assertTrue(RecommendationEngine.sort(context,PROFILE,Arrays.asList(volunteer,administration)).isEmpty());
    }
    @Test public void receiptsSurviveReplayAndDoNotCrossAccountsOrContainText(){
        assertFalse(NotificationDeliveryLedger.contains(context,PROFILE,volunteer.content.key));assertTrue(NotificationDeliveryLedger.mark(context,PROFILE,Arrays.asList(volunteer.content.key)));
        assertTrue(NotificationDeliveryLedger.contains(context,PROFILE,volunteer.content.key));assertFalse(NotificationDeliveryLedger.contains(context,OTHER,volunteer.content.key));
        String raw=context.getSharedPreferences("notification-delivery",Context.MODE_PRIVATE).getAll().toString();assertFalse(raw.contains("志愿"));assertFalse(raw.contains("portal.example.edu"));
    }
    @Test public void feedbackDoesNotRetainNoticeTextOrIds(){
        for(int i=0;i<30;i++)RecommendationEngine.feedback(context,PROFILE,volunteer,true);
        String raw=context.getSharedPreferences("recommendation-feedback",Context.MODE_PRIVATE).getAll().toString();assertFalse(raw.contains("volunteer"));assertFalse(raw.contains("申请表"));assertFalse(raw.contains("portal.example.edu"));
        Map<String,Integer> high=new HashMap<>();high.put("志愿实践",4);PreferenceProfile empty=PreferenceProfile.create(Collections.emptySet(),"未选择",true,false);
        assertEquals(RecommendationEngine.decision(volunteer,empty,KeywordRules.empty(),high,System.currentTimeMillis()).score,RecommendationEngine.score(context,PROFILE,volunteer));
    }
}
