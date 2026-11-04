package org.campusreader.reader;

import android.app.*;
import android.content.Context;
import org.campusreader.config.CampusSite;
import org.campusreader.notices.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class NoticeRemindersTest {
    private DatabaseTestContext c;private String profile;private CampusNoticeStore store;private NoticeKey key;
    @Before public void before()throws Exception{
        c=new DatabaseTestContext(RuntimeEnvironment.getApplication());CampusSite.install(CampusSite.create("example.org","https://vpn.example.org","http://portal.example.org/list","https://login.example.org/login"));
        c.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),"student-one").commit();profile=PortalAccess.currentProfile(c);
        c.getSharedPreferences("notice-reminders",0).edit().clear().commit();c.getSharedPreferences("keyword-rules",0).edit().clear().commit();
        store=new CampusNoticeStore(c,profile);key=CampusNoticeStore.summaryKey("fixture","notice-one");
        store.upsert(Collections.singletonList(new CampusNotice(key,"志愿服务通知",System.currentTimeMillis(),"<p>活动报名。</p>","https://portal.example.org/info/one",Collections.emptyList(),Collections.emptyList())),System.currentTimeMillis());
    }
    @After public void after(){store.close();c.dispose();CampusSite.clear();}
    private String due()throws Exception{String id=NoticeReminders.identity(profile,key);JSONObject value=new JSONObject(c.getSharedPreferences("notice-reminders",0).getString(id,""));value.put("when",System.currentTimeMillis()-1000);c.getSharedPreferences("notice-reminders",0).edit().putString(id,value.toString()).commit();return id;}
    private int notifications(){return Shadows.shadowOf((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).getAllNotifications().size();}
    @Test public void changingTimeReplacesSameReminderWithoutChangingReadingState(){
        store.setLater(key,true);assertTrue(NoticeReminders.schedule(c,profile,key,System.currentTimeMillis()+60000));assertTrue(NoticeReminders.schedule(c,profile,key,System.currentTimeMillis()+120000));
        assertEquals(1,c.getSharedPreferences("notice-reminders",0).getAll().size());assertFalse(store.find(key).read);assertTrue(store.find(key).later);
        assertFalse(NoticeReminders.schedule(c,profile,key,System.currentTimeMillis()-1));
    }
    @Test public void alarmDeliveryIsConsumedOnceAndDoesNotMarkRead()throws Exception{
        assertTrue(NoticeReminders.schedule(c,profile,key,System.currentTimeMillis()+60000));String id=due();NoticeReminders.deliver(c,id);NoticeReminders.deliver(c,id);
        assertEquals(1,notifications());assertFalse(store.find(key).read);assertTrue(c.getSharedPreferences("notice-reminders",0).getAll().isEmpty());
    }
    @Test public void changedAccountCannotReceivePreviousAccountsReminder()throws Exception{
        assertTrue(NoticeReminders.schedule(c,profile,key,System.currentTimeMillis()+60000));String id=due();c.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),"student-two").commit();
        NoticeReminders.deliver(c,id);assertEquals(0,notifications());assertTrue(c.getSharedPreferences("notice-reminders",0).getAll().isEmpty());
    }
    @Test public void currentBlockRulesAlsoApplyToPreviouslyScheduledReminders()throws Exception{
        assertTrue(NoticeReminders.schedule(c,profile,key,System.currentTimeMillis()+60000));String id=due();new KeywordRules(true,Collections.singletonList("志愿"),false,Collections.emptyList()).save(c,profile);
        NoticeReminders.deliver(c,id);assertEquals(0,notifications());assertFalse(store.find(key).read);
    }
}
