package org.campusreader.reader;

import android.content.*;
import org.campusreader.notices.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AiUiTest.ShortPathApplication.class,manifest=Config.NONE)
public class AiAutoSummariesTest {
    private AiUiTest.ShortPathApplication app;private AiSettings settings;private SharedPreferences prefs;private NoticeKey key;private SummaryInput input;
    @Before public void before(){app=(AiUiTest.ShortPathApplication)RuntimeEnvironment.getApplication();app.getSharedPreferences("reader-local",0).edit().putString(ReaderPreferences.lastProfileKey(),"local").commit();
        prefs=app.getSharedPreferences("reader-ai-"+SummaryInput.hash("local"),0);prefs.edit().clear().putBoolean("enabled",true).putString("endpoint","https://api.example.org/v1/chat/completions").putString("model","synthetic").commit();settings=new AiSettings(app,"local");key=CampusNoticeStore.summaryKey("synthetic-queue","one");
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){store.upsert(Collections.singletonList(new CampusNotice(key,"Private synthetic title",10,"<p>Private synthetic body.</p>","https://portal.example.org/one",Collections.emptyList(),Collections.emptyList())),100);input=store.summaryInput(key);}
    }
    @After public void after(){AiAutoSummaries.finishAttempt(app,"local",key,input.contentHash,"uncertain");app.dispose();}
    @Test public void legacyServiceDoesNotSilentlyAuthorizeAutomaticSending(){assertTrue(settings.enabled());assertFalse(settings.automaticEnabled());assertFalse(settings.automaticPolicies());}
    @Test public void automaticGrantBindsExactDestinationAndPoliciesAreSeparate(){settings.configureAutomation(true,false);assertTrue(settings.automaticEnabled());assertFalse(settings.automaticPolicies());settings.configureAutomation(true,true);assertTrue(settings.automaticPolicies());prefs.edit().putString("endpoint","https://other.example.org/v1/chat/completions").commit();assertFalse(settings.automaticEnabled());}
    @Test public void disabledServiceCannotAuthorizeAutomation(){settings.disable();try{settings.configureAutomation(true,false);fail();}catch(IllegalArgumentException expected){}assertFalse(settings.automaticEnabled());}
    @Test public void oneLeaseCoordinatesManualAndAutomaticAndUnknownAttemptSurvivesQueueEviction()throws Exception{
        AiSettings.Config config=settings.load();assertTrue(AiAutoSummaries.beginAttempt(app,"local",key,input.contentHash,false,settings,config,()->{}));assertEquals(1,settings.usedToday());
        try{AiAutoSummaries.beginAttempt(app,"local",key,input.contentHash,true,settings,config,()->{});fail();}catch(java.io.IOException expected){assertEquals("BUSY",expected.getMessage());}
        assertEquals(1,settings.usedToday());AiAutoSummaries.finishAttempt(app,"local",key,input.contentHash,"uncertain");
        AiAutoSummaries.write(app,"local",new ArrayList<>());assertTrue(AiAutoSummaries.attempted(app,"local",key,input.contentHash));
        assertFalse(AiAutoSummaries.beginAttempt(app,"local",key,input.contentHash,false,settings,config,()->{}));assertEquals(1,settings.usedToday());
        assertTrue(AiAutoSummaries.beginAttempt(app,"local",key,input.contentHash,true,settings,config,()->{}));assertEquals(2,settings.usedToday());
    }
    @Test public void otherNoticeCannotStartConcurrentNetworkAttempt()throws Exception{
        AiSettings.Config config=settings.load();assertTrue(AiAutoSummaries.beginAttempt(app,"local",key,input.contentHash,true,settings,config,()->{}));
        NoticeKey other=CampusNoticeStore.summaryKey("synthetic-queue","two");try{AiAutoSummaries.beginAttempt(app,"local",other,input.contentHash,true,settings,config,()->{});fail();}catch(java.io.IOException expected){assertEquals("BUSY",expected.getMessage());}assertEquals(1,settings.usedToday());
    }
    @Test public void storedQueueContainsIdentifiersAndHashesNotNoticeText()throws Exception{
        String hash=SummaryInput.hash("config");AiAutoSummaries.Task task=new AiAutoSummaries.Task(AiAutoSummaries.identity(key,input.contentHash,hash),key,input.contentHash,hash,"uncertain",100,0);
        AiAutoSummaries.write(app,"local",new ArrayList<>(Collections.singletonList(task)));List<AiAutoSummaries.Task> loaded=AiAutoSummaries.read(app,"local");assertEquals(1,loaded.size());assertEquals("uncertain",loaded.get(0).state);
        String raw=new String(Files.readAllBytes(app.getNoBackupFilesDir().toPath().resolve("ai-queue-"+SummaryInput.hash("local")+".json")),java.nio.charset.StandardCharsets.UTF_8);assertFalse(raw.contains(input.title));assertFalse(raw.contains(input.bodyText));assertFalse(raw.contains("key"));
    }
    @Test public void clearingTasksDoesNotRefundQuotaAndCanExplicitlyResetUnknownAttempts()throws Exception{
        assertTrue(AiAutoSummaries.beginAttempt(app,"local",key,input.contentHash,false,settings,settings.load(),()->{}));AiAutoSummaries.finishAttempt(app,"local",key,input.contentHash,"uncertain");
        AiAutoSummaries.cancelAndClear(app,"local");assertFalse(AiAutoSummaries.attempted(app,"local",key,input.contentHash));assertEquals(1,settings.usedToday());
    }
    @Test public void defaultAutomaticContextDoesNotContainPolicyText()throws Exception{
        PolicyLibrary library=new PolicyLibrary(app,"local");library.add(new PolicyLibrary.Metadata("Private synthetic policy","","","","","synthetic","待核验","local.txt"),Collections.singletonList("Private synthetic body rules and policy."));
        AiEvidence.Snapshot without=AiAutoSummaries.context(input,library.snapshot(),false);assertEquals(0,without.policyCount());assertFalse(without.exactText.contains("Private synthetic policy"));
    }
    @Test public void policyExcludedCacheRemainsReadableAfterDisablingAi()throws Exception{
        PolicyLibrary library=new PolicyLibrary(app,"local");library.add(new PolicyLibrary.Metadata("Private synthetic policy","","","","","synthetic","待核验","local.txt"),Collections.singletonList("Private synthetic body rules and policy."));
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){input=store.summaryInput(key);}
        settings.configureAutomation(true,false);AiEvidence.Snapshot context=AiAutoSummaries.context(input,library.snapshot(),false);AiEvidence.Unit unit=context.unit("N2");
        JSONObject raw=new JSONObject().put("schema",3).put("overview",new JSONObject().put("text",unit.text).put("refs",new JSONArray().put(new JSONObject().put("id",unit.id).put("quote",unit.text))))
                .put("points",new JSONArray()).put("tags",new JSONArray()).put("policy_links",new JSONArray());
        AiBriefSummary brief=AiBriefSummary.parse(raw.toString(),context);
        try(CampusNoticeStore store=new CampusNoticeStore(app,"local")){assertTrue(store.saveSummaryIfCurrent(key,input.contentHash,input.cacheGeneration,AiAutoSummaries.cacheHash(context,settings.cacheConfig()),brief.json,200));}
        settings.disable();AiSummaryPresentation.Cached cached=AiSummaryPresentation.loadCached(app,"local",key);assertNotNull(cached);assertEquals(unit.text,cached.snippet);assertEquals(0,settings.usedToday());
    }
}
