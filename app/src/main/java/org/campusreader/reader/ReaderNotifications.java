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
import java.util.*;
import org.campusreader.notices.CachedNotice;

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
            NotificationChannel priority=new NotificationChannel(PRIORITY,"重点关注提醒",NotificationManager.IMPORTANCE_HIGH);
            priority.setDescription("新通知命中明确关注词或已启用提醒的关注主题；屏蔽词优先");priority.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
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
        synchronized(PortalAccess.ACCOUNT_LOCK){synchronized(KeywordRules.LOCK){publishCurrentResult(context,result);}}
    }
    private static void publishCurrentResult(Context context,CampusSyncEngine.Result result){
        ReaderPreferences prefs=new ReaderPreferences(context);
        if(!result.profile.equals(CampusSyncEngine.currentProfile())){
            NotificationHistory.record(context,result.profile,NotificationHistory.Reason.ACCOUNT_CHANGED,null,null,null,null);return;
        }
        if(result.status==CampusSyncEngine.Status.SUCCESS||result.status==CampusSyncEngine.Status.PARTIAL)clearSessionReminder(context,result.profile);
        initialize(context);
        publishNewItems(context,result);
        if(!result.profile.equals(CampusSyncEngine.currentProfile()))return;
        boolean needsLogin=result.status==CampusSyncEngine.Status.PORTAL_LOGIN_REQUIRED;
        boolean needsTunnel=result.status==CampusSyncEngine.Status.TUNNEL_REQUIRED;
        if(needsLogin||needsTunnel){
            NotificationHistory.Reason reason=availability(context,result.profile,SESSION);
            if(reason==null&&prefs.hasReminder(result.profile))reason=NotificationHistory.Reason.SESSION_ALREADY_REMINDED;
            if(reason==null){
                Notification notification=builder(context,SESSION,result.profile,false)
                        .setContentTitle(needsLogin?"信息门户需要登录":"校园连接需要检查")
                        .setContentText("打开应用继续同步；已有缓存和阅读状态保留").build();
                if(!result.profile.equals(CampusSyncEngine.currentProfile()))reason=NotificationHistory.Reason.ACCOUNT_CHANGED;
                else if(post(context,SESSION_ID,notification)){prefs.markReminder(result.profile);reason=NotificationHistory.Reason.SESSION_SUBMITTED;}
                else reason=NotificationHistory.Reason.POST_FAILED;
            }
            NotificationHistory.record(context,result.profile,reason,null,null,null,"session");
        }
    }
    private static final class Candidate {
        final NoticeKey key;final String word,title,contentHash;
        Candidate(CachedNotice row,String word,String contentHash){this.key=row.content.key;this.title=row.content.title;this.word=word;this.contentHash=contentHash;}
    }
    public static void noteForegroundBaseline(Context context,String profile){if(NotificationHistory.validProfile(profile))context.getApplicationContext().getSharedPreferences("notification-delivery",Context.MODE_PRIVATE).edit().putBoolean("baseline:"+profile,true).apply();}
    private static void publishNewItems(Context context,CampusSyncEngine.Result result){
        if(result.insertedKeys.isEmpty())return;
        if(!context.getSharedPreferences("notification-delivery",Context.MODE_PRIVATE).getBoolean("baseline:"+result.profile,false)){
            noteForegroundBaseline(context,result.profile);
            NotificationHistory.record(context,result.profile,NotificationHistory.Reason.INITIAL_SYNC,null,null,null,null);return;
        }
        try(CampusNoticeStore store=new CampusNoticeStore(context,result.profile)){
            KeywordRules rules=KeywordRules.loadStrict(context,result.profile);
            NoticeQuery visibleUnread=rules.apply(NoticeQuery.builder(CampusPortalParser.SOURCE).unreadOnly(true),false).build();
            List<Candidate> normal=new ArrayList<>(),priority=new ArrayList<>();
            for(NoticeKey key:new LinkedHashSet<>(result.insertedKeys)){
                if(!result.profile.equals(CampusSyncEngine.currentProfile())){
                    NotificationHistory.record(context,result.profile,NotificationHistory.Reason.ACCOUNT_CHANGED,key,rules,null,null);continue;
                }
                if(NotificationDeliveryLedger.contains(context,result.profile,key)){
                    NotificationHistory.record(context,result.profile,NotificationHistory.Reason.ALREADY_DELIVERED,key,rules,null,null);continue;
                }
                CachedNotice row=store.find(key);
                String blockedWord=row==null?"":firstMatch(store,key,rules.hasBlocked()?rules.blockedWords:Collections.emptyList());
                NotificationHistory.Reason reason=NotificationDecision.suppressed(true,row!=null,row!=null&&row.read,!blockedWord.isEmpty(),true,true,true,true);
                if(reason!=null){NotificationHistory.record(context,result.profile,reason,key,rules,blockedWord,null);continue;}
                String priorityWord=RecommendationEngine.priorityReason(context,result.profile,row,rules);
                org.campusreader.notices.SummaryInput input=store.summaryInput(key);
                (priorityWord.isEmpty()?normal:priority).add(new Candidate(row,priorityWord,input==null?"":input.contentHash));
            }
            long unreadCount=store.count(visibleUnread),priorityCount=priority.size();
            publishCandidates(context,result.profile,normal,rules,UPDATES,UPDATES_ID,unreadCount);
            // A disabled priority channel never falls back to the ordinary channel.
            publishCandidates(context,result.profile,priority,rules,PRIORITY,PRIORITY_ID,priorityCount);
        }catch(RuntimeException failure){
            NotificationHistory.record(context,result.profile,NotificationHistory.Reason.RULES_ERROR,null,null,null,null);
            android.util.Log.w("CampusNotify","Keyword matching unavailable");
        }
    }
    private static String firstMatch(CampusNoticeStore store,NoticeKey key,List<String> words){
        for(String word:words)if(store.matches(key,NoticeQuery.builder(CampusPortalParser.SOURCE).search(word).build()))return word;
        return "";
    }
    private static void publishCandidates(Context context,String profile,List<Candidate> rows,KeywordRules rules,String channel,int id,long unread){
        if(rows.isEmpty())return;
        NotificationHistory.Reason reason=availability(context,profile,channel);
        boolean priority=PRIORITY.equals(channel);
        if(reason==null){
            Notification.Builder build=builder(context,channel,profile,true,id)
                    .setContentTitle(priority?"重点关注 · "+rows.size()+" 条新通知":"北邮人信息 · "+rows.size()+" 条新通知")
                    .setContentText(shortTitle(rows.get(0).title)).setNumber((int)Math.min(Integer.MAX_VALUE,unread));
            Notification.InboxStyle style=new Notification.InboxStyle().setSummaryText(priority?"匹配你明确设置的关注偏好":"当前 "+unread+" 条未读");
            for(int i=0;i<Math.min(4,rows.size());i++)style.addLine(shortTitle(rows.get(i).title));build.setStyle(style);
            List<NoticeKey> keys=new ArrayList<>();List<String> titles=new ArrayList<>(),hashes=new ArrayList<>();for(Candidate row:rows){keys.add(row.key);titles.add(row.title);hashes.add(row.contentHash);}NotificationSummaryUpdates.attach(build,profile,keys,titles,hashes);
            if(rows.size()==1){build.addAction(action(context,profile,rows.get(0).key,"later","稍后读",id));build.addAction(action(context,profile,rows.get(0).key,"read","标为已读",id));}
            Notification notification=build.build();
            if(!profile.equals(CampusSyncEngine.currentProfile()))reason=NotificationHistory.Reason.ACCOUNT_CHANGED;
            else if(post(context,id,notification)){
                List<NoticeKey> delivered=new ArrayList<>();for(Candidate row:rows)delivered.add(row.key);NotificationDeliveryLedger.mark(context,profile,delivered);
                reason=priority?NotificationHistory.Reason.PRIORITY_SUBMITTED:NotificationHistory.Reason.NORMAL_SUBMITTED;
                for(int i=0;i<Math.min(4,rows.size());i++)updateSummary(context,profile,rows.get(i).key);
            }else reason=NotificationHistory.Reason.POST_FAILED;
        }
        for(Candidate row:rows)NotificationHistory.record(context,profile,reason,row.key,rules,row.word,priority?"priority":"normal");
    }
    public static void updateSummary(Context context,String profile,NoticeKey key){NotificationSummaryUpdates.updateSummary(context,profile,key);}
    private static String shortTitle(String title){return title.length()>100?title.substring(0,100)+"…":title;}
    private static Notification.Action action(Context context,String profile,NoticeKey key,String operation,String label,int notificationId){
        Intent intent=new Intent(context,NoticeNotificationActionReceiver.class).setAction("org.campusreader.NOTICE_"+operation)
                .setData(android.net.Uri.parse("campus-reader://notice/"+NotificationHistory.digest(profile+'\0'+key.source+'\0'+key.stableId)+"/"+operation))
                .putExtra("profile",profile).putExtra("source",key.source).putExtra("id",key.stableId).putExtra("operation",operation).putExtra("notificationId",notificationId);
        PendingIntent pending=PendingIntent.getBroadcast(context,notificationId,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(android.R.drawable.ic_menu_agenda,label,pending).build();
    }
    private static NotificationHistory.Reason availability(Context context,String profile,String channel){
        NotificationManager m=manager(context);
        return NotificationDecision.suppressed(profile.equals(CampusSyncEngine.currentProfile()),true,false,false,
                new ReaderPreferences(context).isNotificationsEnabled(),
                Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED,
                m!=null&&(Build.VERSION.SDK_INT<24||m.areNotificationsEnabled()),channelEnabled(context,channel));
    }
    public static void clearNewItems(Context context){synchronized(KeywordRules.LOCK){NotificationManager m=manager(context);if(m!=null){m.cancel(UPDATES_ID);m.cancel(PRIORITY_ID);}}}
    /** Explicit settings-button probe: no cache row, unread count, or login-reminder state changes. */
    public static boolean postTest(Context context){
        synchronized(PortalAccess.ACCOUNT_LOCK){
        if(!canPost(context))return false;
        initialize(context);
        if(!channelEnabled(context,UPDATES))return false;
        Notification notification=builder(context,UPDATES,CampusSyncEngine.currentProfile(),false,TEST_ID)
                .setContentTitle("北邮人信息 · 通知测试").setContentText("通知权限已可用").build();
        return post(context,TEST_ID,notification);
        }
    }
    public static void clearSessionReminder(Context context,String profile){
        synchronized(PortalAccess.ACCOUNT_LOCK){
        new ReaderPreferences(context).clearReminder(profile);
        if(profile.equals(CampusSyncEngine.currentProfile())){NotificationManager m=manager(context);if(m!=null)m.cancel(SESSION_ID);}
        }
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
        try{NotificationManager m=manager(context);if(m==null)return false;m.notify(id,notification);return true;}catch(RuntimeException denied){return false;}
    }
}
