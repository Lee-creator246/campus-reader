package org.campusreader.reader;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import org.campusreader.notices.CampusNoticeStore;
import org.campusreader.notices.NoticeKey;
import org.campusreader.notices.NoticeQuery;
import org.campusreader.portal.CampusPortalParser;
import java.util.LinkedHashSet;

/** Separate unread/priority summaries and one merged connection/login reminder. */
public final class ReaderNotifications {
    public static final String EXTRA_UNREAD="campus_unread",EXTRA_PROFILE="campus_profile",EXTRA_PRIORITY="campus_priority";
    private static final String UPDATES="campus_updates",SESSION="campus_session",PRIORITY="campus_priority";
    private static final int UPDATES_ID=47062,SESSION_ID=47063,TEST_ID=47064,PRIORITY_ID=47065;
    private ReaderNotifications(){}
    public static void initialize(Context context){
        NotificationManager manager=manager(context);if(manager==null)return;
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel updates=new NotificationChannel(UPDATES,"校园未读通知",NotificationManager.IMPORTANCE_DEFAULT);
            updates.setDescription("后台发现新通知时，汇总未读数量；阅读内容保存在本机");updates.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationChannel session=new NotificationChannel(SESSION,"校园连接提醒",NotificationManager.IMPORTANCE_LOW);
            session.setDescription("需要在应用内重新连接或登录门户时合并提醒");session.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationChannel priority=new NotificationChannel(PRIORITY,"关键词优先提醒",NotificationManager.IMPORTANCE_HIGH);
            priority.setDescription("新通知命中关注关键词时优先提醒；屏蔽词优先，不展示通知正文");priority.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(updates);manager.createNotificationChannel(session);manager.createNotificationChannel(priority);
        }
    }
    public static boolean canPost(Context context){
        if(!new ReaderPreferences(context).isNotificationsEnabled())return false;
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager manager=manager(context);return manager!=null&&(Build.VERSION.SDK_INT<24||manager.areNotificationsEnabled());
    }
    /** RefreshJobService calls this on its worker. Match the latest rules, never stale sync counts. */
    public static void publishResult(Context context,CampusSyncEngine.Result result){
        synchronized(KeywordRules.LOCK){publishCurrentResult(context,result);}
    }
    private static void publishCurrentResult(Context context,CampusSyncEngine.Result result){
        ReaderPreferences prefs=new ReaderPreferences(context);
        if(!result.profile.equals(CampusSyncEngine.currentProfile()))return;
        if(result.status==CampusSyncEngine.Status.SUCCESS||result.status==CampusSyncEngine.Status.PARTIAL)clearSessionReminder(context,result.profile);
        if(!canPost(context))return;
        initialize(context);
        publishNewItems(context,result);
        if(!result.profile.equals(CampusSyncEngine.currentProfile()))return;
        boolean needsLogin=result.status==CampusSyncEngine.Status.PORTAL_LOGIN_REQUIRED;
        boolean needsTunnel=result.status==CampusSyncEngine.Status.TUNNEL_REQUIRED;
        if((needsLogin||needsTunnel)&&!prefs.hasReminder(result.profile)&&channelEnabled(context,SESSION)){
            Notification notification=builder(context,SESSION,result.profile,false)
                    .setContentTitle(needsLogin?"信息门户需要登录":"校园连接需要检查")
                    .setContentText("打开应用继续同步；已有缓存和阅读状态保留").build();
            if(post(context,SESSION_ID,notification))prefs.markReminder(result.profile);
        }
    }
    private static void publishNewItems(Context context,CampusSyncEngine.Result result){
        if(result.insertedKeys.isEmpty())return;
        try(CampusNoticeStore store=new CampusNoticeStore(context,result.profile)){
            KeywordRules rules=KeywordRules.load(context,result.profile);
            NoticeQuery visibleUnread=rules.apply(NoticeQuery.builder(CampusPortalParser.SOURCE).unreadOnly(true),false).build();
            NoticeQuery priorityUnread=rules.apply(NoticeQuery.builder(CampusPortalParser.SOURCE).unreadOnly(true),true).build();
            int normalNew=0,priorityNew=0;
            for(NoticeKey key:new LinkedHashSet<>(result.insertedKeys)){
                // Reading, hiding, or eviction after sync may have made an inserted row ineligible.
                if(!store.matches(key,visibleUnread))continue;
                if(store.matches(key,priorityUnread))priorityNew++;else normalNew++;
            }
            long unreadCount=store.count(visibleUnread);
            long priorityCount=priorityNew>0?store.count(priorityUnread):0;
            if(normalNew>0&&unreadCount>0&&channelEnabled(context,UPDATES)&&result.profile.equals(CampusSyncEngine.currentProfile())){
                Notification notification=builder(context,UPDATES,result.profile,true)
                        .setContentTitle("校园门户有 "+normalNew+" 条新通知")
                        .setContentText("当前 "+unreadCount+" 条未读，点击查看")
                        .setNumber((int)Math.min(Integer.MAX_VALUE,unreadCount)).build();
                post(context,UPDATES_ID,notification);
            }
            // A disabled priority channel never falls back to the ordinary channel.
            if(priorityNew>0&&priorityCount>0&&channelEnabled(context,PRIORITY)&&result.profile.equals(CampusSyncEngine.currentProfile())){
                Notification notification=builder(context,PRIORITY,result.profile,true,PRIORITY_ID)
                        .setContentTitle("关键词关注 · "+priorityNew+" 条新通知")
                        .setContentText("当前 "+priorityCount+" 条关注未读，点击查看")
                        .setNumber((int)Math.min(Integer.MAX_VALUE,priorityCount)).build();
                post(context,PRIORITY_ID,notification);
            }
        }catch(RuntimeException failure){
            // Fail closed: a cache/rule error must not reveal blocked notices via raw sync counts.
            android.util.Log.w("CampusNotify","Keyword matching failed: "+failure.getClass().getSimpleName());
        }
    }
    public static void clearNewItems(Context context){synchronized(KeywordRules.LOCK){NotificationManager m=manager(context);if(m!=null){m.cancel(UPDATES_ID);m.cancel(PRIORITY_ID);}}}
    /** Explicit settings-button probe: no cache row, unread count, or login-reminder state changes. */
    public static boolean postTest(Context context){
        if(!canPost(context))return false;
        initialize(context);
        if(!channelEnabled(context,UPDATES))return false;
        Notification notification=builder(context,UPDATES,CampusSyncEngine.currentProfile(),false,TEST_ID)
                .setContentTitle("校园阅读 · 通知测试").setContentText("通知权限已可用").build();
        return post(context,TEST_ID,notification);
    }
    public static void clearSessionReminder(Context context,String profile){
        new ReaderPreferences(context).clearReminder(profile);
        if(profile.equals(CampusSyncEngine.currentProfile())){NotificationManager m=manager(context);if(m!=null)m.cancel(SESSION_ID);}
    }
    public static void clearAll(Context context){synchronized(KeywordRules.LOCK){NotificationManager m=manager(context);if(m!=null){m.cancel(UPDATES_ID);m.cancel(SESSION_ID);m.cancel(TEST_ID);m.cancel(PRIORITY_ID);}}}
    private static Notification.Builder builder(Context context,String channel,String profile,boolean unread){
        return builder(context,channel,profile,unread,unread?UPDATES_ID:SESSION_ID);
    }
    private static Notification.Builder builder(Context context,String channel,String profile,boolean unread,int requestCode){
        Intent intent=new Intent(context,ReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_UNREAD,unread).putExtra(EXTRA_PROFILE,profile).putExtra(EXTRA_PRIORITY,PRIORITY.equals(channel));
        PendingIntent action=PendingIntent.getActivity(context,requestCode,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,channel):new Notification.Builder(context);
        if(PRIORITY.equals(channel))builder.setPriority(Notification.PRIORITY_HIGH);
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(action).setAutoCancel(true)
                .setOnlyAlertOnce(!PRIORITY.equals(channel)).setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS);
    }
    private static boolean channelEnabled(Context context,String id){NotificationManager m=manager(context);if(m==null)return false;if(Build.VERSION.SDK_INT<26)return true;NotificationChannel channel=m.getNotificationChannel(id);return channel!=null&&channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;}
    private static NotificationManager manager(Context context){return (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);}
    private static boolean post(Context context,int id,Notification notification){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        try{NotificationManager m=manager(context);if(m==null)return false;m.notify(id,notification);return true;}catch(SecurityException denied){return false;}
    }
}
