package org.campusreader.reader;

import android.app.*;
import android.content.Context;
import android.net.Uri;
import org.campusreader.auth.CampusApplication;
import org.campusreader.config.CampusSite;
import org.campusreader.notices.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic active-notification checks; no AI, device, or network is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CampusApplication.class,manifest=Config.NONE)
public class NotificationSummaryUpdatesTest {
    private DatabaseTestContext context;private CampusNoticeStore store;private NotificationManager manager;
    private NoticeKey key;private String profile;private SummaryInput input;private AiSummaryPresentation.Cached cached;
    private CampusSite.Profile previousSite;
    @Before public void setup()throws Exception{
        context=new DatabaseTestContext(RuntimeEnvironment.getApplication());
        previousSite=CampusSite.isConfigured()?CampusSite.requireConfigured():null;
        CampusSite.install(CampusSite.create("campus.example.edu","https://vpn.campus.example.edu","https://portal.campus.example.edu/notices","https://auth.campus.example.edu/authserver/login"));
        context.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),"synthetic-student").commit();profile=PortalAccess.currentProfile(context);
        assertTrue(profile.startsWith("campus:"));assertEquals(profile,CampusSyncEngine.currentProfile());
        context.getSharedPreferences("reader-settings",0).edit().clear().commit();context.getSharedPreferences("keyword-rules",0).edit().clear().commit();
        context.getSharedPreferences("notification-delivery",0).edit().clear().commit();
        store=new CampusNoticeStore(context,profile);store.setRetentionPolicy(new RetentionPolicy(100,5L<<20,-1));key=NoticeKey.portalId("fixture","summary-notification");
        store.upsert(Collections.singletonList(new CampusNotice(key,"志愿服务通知",System.currentTimeMillis(),"<p>请报名志愿活动。</p>","https://portal.example.edu/news",Collections.emptyList(),Collections.emptyList())),System.currentTimeMillis());input=store.summaryInput(key);
        AiEvidence.Snapshot evidence=AiAutoSummaries.context(input,new PolicyLibrary.Snapshot(0,Collections.emptyList()),false);AiEvidence.Unit unit=null;for(AiEvidence.Unit item:evidence.units)if(item.text.contains("请报名志愿活动。"))unit=item;assertNotNull(unit);
        JSONObject overview=new JSONObject().put("text","报名志愿活动。").put("refs",new JSONArray().put(new JSONObject().put("id",unit.id).put("quote","请报名志愿活动。")));
        AiBriefSummary result=AiBriefSummary.parse(new JSONObject().put("schema",3).put("overview",overview).put("points",new JSONArray()).put("tags",new JSONArray()).put("policy_links",new JSONArray()).toString(),evidence);
        cached=new AiSummaryPresentation.Cached(result,null,evidence,input,System.currentTimeMillis(),new AiSettings(context,profile).revision());
        manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);manager.cancelAll();manager.createNotificationChannel(new NotificationChannel("fixture_updates","测试提醒",NotificationManager.IMPORTANCE_HIGH));
    }
    @After public void cleanup(){if(manager!=null)manager.cancelAll();if(store!=null)store.close();if(context!=null)context.dispose();if(previousSite!=null)CampusSite.install(previousSite);else CampusSite.clear();}
    private String publish(){Notification.Builder builder=new Notification.Builder(context,"fixture_updates").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("测试通知").setContentText("原始标题").setSound(Uri.parse("content://synthetic/sound"));
        NotificationSummaryUpdates.attach(builder,profile,Collections.singletonList(key),Collections.singletonList("志愿服务通知"),Collections.singletonList(input.contentHash));Notification notification=builder.build();manager.notify(47062,notification);return notification.extras.getString(NotificationSummaryUpdates.EVENT);}
    @Test public void updatesExistingPublicationSilentlyWithoutNewReceiptOrReadingChange(){
        String publication=publish();assertTrue(ReaderNotifications.canPost(context));assertEquals(cached.settingsRevision,new AiSettings(context,profile).revision());
        assertEquals(1,manager.getActiveNotifications().length);Notification original=manager.getActiveNotifications()[0].getNotification();assertEquals("fixture_updates",original.getChannelId());assertNotNull(manager.getNotificationChannel(original.getChannelId()));
        // Exercise platform recovery separately so a framework-stub failure is visible, not silently skipped.
        assertNotNull(Notification.Builder.recoverBuilder(context,original).build());
        assertTrue(NotificationSummaryUpdates.apply(context,profile,key,cached,publication));
        assertEquals(1,manager.getActiveNotifications().length);Notification current=manager.getActiveNotifications()[0].getNotification();assertTrue(current.extras.getString(Notification.EXTRA_TEXT).contains("报名志愿活动"));
        assertNotEquals(0,current.flags&Notification.FLAG_ONLY_ALERT_ONCE);assertNull(current.sound);assertNull(current.vibrate);assertFalse(store.find(key).read);assertFalse(store.find(key).later);
        assertTrue(context.getSharedPreferences("notification-delivery",0).getAll().isEmpty());assertEquals(publication,current.extras.getString(NotificationSummaryUpdates.EVENT));
    }
    @Test public void dismissedReplacedBlockedOrDifferentAccountCannotBeRevived(){
        String old=publish(),replacement=publish();assertFalse(NotificationSummaryUpdates.apply(context,profile,key,cached,old));
        manager.cancel(47062);assertFalse(NotificationSummaryUpdates.apply(context,profile,key,cached,replacement));assertEquals(0,manager.getActiveNotifications().length);
        new KeywordRules(true,Collections.singletonList("志愿"),false,Collections.emptyList()).save(context,profile);String blocked=publish();assertFalse(NotificationSummaryUpdates.apply(context,profile,key,cached,blocked));
        KeywordRules.empty().save(context,profile);String active=publish();context.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),"different-student").commit();
        assertFalse(NotificationSummaryUpdates.apply(context,profile,key,cached,active));assertFalse(manager.getActiveNotifications()[0].getNotification().extras.getString(Notification.EXTRA_TEXT).contains("AI："));
    }
}
