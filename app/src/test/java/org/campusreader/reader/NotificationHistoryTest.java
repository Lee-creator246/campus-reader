package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import org.campusreader.notices.NoticeKey;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class NotificationHistoryTest {
    private Context context;
    private static final String OWNER="campus:"+String.join("",Collections.nCopies(64,"a"));
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("notification-history",0).edit().clear().commit();context.getSharedPreferences("reader-settings",0).edit().clear().commit();}
    private NotificationHistory.Entry entry(long time){return new NotificationHistory.Entry(time,NotificationHistory.Reason.APP_DISABLED,"","","","","",0,0,0,false);}
    @Test public void boundedJournalRetainsNewest200(){long now=System.currentTimeMillis();for(int i=0;i<230;i++)NotificationHistory.append(context,OWNER,entry(now-230+i),now);
        List<NotificationHistory.Entry> rows=NotificationHistory.read(context,OWNER);assertEquals(200,rows.size());assertEquals(now-1,rows.get(0).timeMillis);assertEquals(now-200,rows.get(199).timeMillis);}
    @Test public void expiredAndFutureRecordsAreNotRetained(){long now=System.currentTimeMillis();NotificationHistory.append(context,OWNER,entry(now-NotificationHistory.MAX_AGE_MS-1),now);NotificationHistory.append(context,OWNER,entry(now+1),now);assertTrue(NotificationHistory.read(context,OWNER).isEmpty());}
    @Test public void profilesAndClearAreIsolated(){long now=System.currentTimeMillis();NotificationHistory.append(context,OWNER,entry(now),now);NotificationHistory.append(context,"local",entry(now),now);assertTrue(NotificationHistory.clear(context,OWNER));assertTrue(NotificationHistory.read(context,OWNER).isEmpty());assertEquals(1,NotificationHistory.read(context,"local").size());}
    @Test public void onlyHashedNoticeAndRuleIdentityAreStored(){KeywordRules rules=new KeywordRules(true,Arrays.asList("任职","另一个关键词"),true,Arrays.asList("志愿"));
        NotificationHistory.record(context,OWNER,NotificationHistory.Reason.BLOCKED,NoticeKey.portalId("https://private.example.edu/notice","private-student-account"),rules,"任职",null);
        String raw=context.getSharedPreferences("notification-history",0).getString(OWNER,"");assertFalse(raw.contains("private.example"));assertFalse(raw.contains("student-account"));assertFalse(raw.contains("另一个关键词"));
        NotificationHistory.Entry row=NotificationHistory.read(context,OWNER).get(0);assertEquals(64,row.noticeHash.length());assertEquals(64,row.ruleHash.length());assertEquals("任职",row.matchedWord);}
    @Test public void rawAccountCannotBeJournalNamespace(){assertThrows(IllegalArgumentException.class,()->NotificationHistory.read(context,"student123"));NotificationHistory.record(context,"student123",NotificationHistory.Reason.REFRESH,null,null,null,null);assertFalse(context.getSharedPreferences("notification-history",0).contains("student123"));}
    @Test public void malformedJournalIsReportedAndNewRecordsCanRecover(){context.getSharedPreferences("notification-history",0).edit().putString(OWNER,"{broken").commit();assertThrows(IllegalStateException.class,()->NotificationHistory.read(context,OWNER));long now=System.currentTimeMillis();NotificationHistory.append(context,OWNER,entry(now),now);assertEquals(1,NotificationHistory.read(context,OWNER).size());}
    @Test public void resultPreferencesAreIsolatedAndFailurePreservesLastSuccess(){ReaderPreferences prefs=new ReaderPreferences(context);prefs.recordResult(result(CampusSyncEngine.Status.SUCCESS,OWNER));long success=prefs.getLastSuccessfulMillis(OWNER);assertTrue(success>0);prefs.recordResult(result(CampusSyncEngine.Status.PORTAL_LOGIN_REQUIRED,OWNER));assertEquals(success,prefs.getLastSuccessfulMillis(OWNER));assertEquals("PORTAL_LOGIN_REQUIRED",prefs.getLastResult(OWNER));assertEquals(0,prefs.getLastAttemptMillis("local"));assertEquals("",prefs.getLastResult("local"));}
    @Test public void partialRefreshDoesNotClaimFullSuccess(){ReaderPreferences prefs=new ReaderPreferences(context);prefs.recordResult(result(CampusSyncEngine.Status.PARTIAL,OWNER));assertEquals(0,prefs.getLastSuccessfulMillis(OWNER));assertEquals("PARTIAL",prefs.getLastResult(OWNER));}
    @Test public void foregroundJournalDoesNotPretendNotificationWasSent(){NoticeKey key=NoticeKey.portalId("campus-portal","example-notice");CampusSyncEngine.Result result=new CampusSyncEngine.Result(CampusSyncEngine.Status.SUCCESS,OWNER,1,1,0,1,null,Arrays.asList(key));NotificationHistory.refresh(context,result,false);List<NotificationHistory.Entry> rows=NotificationHistory.read(context,OWNER);assertEquals(2,rows.size());assertEquals(NotificationHistory.Reason.FOREGROUND_ONLY,rows.get(0).reason);assertEquals(NotificationHistory.Reason.REFRESH,rows.get(1).reason);assertEquals(1,rows.get(1).added);}
    @Test public void noNewIdentityHasNoAlertClaim(){NotificationHistory.refresh(context,result(CampusSyncEngine.Status.SUCCESS,OWNER),true);assertEquals(NotificationHistory.Reason.NO_NEW_ITEMS,NotificationHistory.read(context,OWNER).get(0).reason);}
    private CampusSyncEngine.Result result(CampusSyncEngine.Status status,String profile){return new CampusSyncEngine.Result(status,profile,0,0,0,0,null);}
}
