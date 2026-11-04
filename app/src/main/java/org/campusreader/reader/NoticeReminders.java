package org.campusreader.reader;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import org.campusreader.notices.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** User-chosen times, persisted separately from reading status. Uses inexact system alarms. */
final class NoticeReminders {
    static final String ACTION="org.campusreader.reader.NOTICE_REMINDER";
    static final String EXTRA_SOURCE="reminder_source",EXTRA_ID="reminder_id";
    private static final Object LOCK=new Object();
    private NoticeReminders(){}
    private static SharedPreferences preferences(Context c){return c.getApplicationContext().getSharedPreferences("notice-reminders",Context.MODE_PRIVATE);}
    static String identity(String profile,NoticeKey key){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest((profile+'\0'+key.source+'\0'+key.stableId).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static PendingIntent pending(Context c,String id){return PendingIntent.getBroadcast(c,0,new Intent(c,NoticeReminderReceiver.class).setAction(ACTION).setData(Uri.parse("campus-reminder://notice/"+id)),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static boolean schedule(Context context,String profile,NoticeKey key,long when){
        if(!profile.equals(PortalAccess.currentProfile(context))||when<=System.currentTimeMillis()||when-System.currentTimeMillis()>366L*86400000)return false;
        synchronized(LOCK){try(CampusNoticeStore store=new CampusNoticeStore(context,profile)){
            if(store.find(key)==null)return false;SharedPreferences prefs=preferences(context);String id=identity(profile,key);if(!prefs.contains(id)&&prefs.getAll().size()>=50)return false;
            String value=new JSONObject().put("profile",profile).put("source",key.source).put("id",key.stableId).put("when",when).toString();
            if(!prefs.edit().putString(id,value).commit())return false;
            if(!register(context,id,when)){prefs.edit().remove(id).commit();return false;}return true;
        }catch(RuntimeException|JSONException e){return false;}}
    }
    private static boolean register(Context context,String id,long when){try{AlarmManager alarms=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE);if(alarms==null)return false;alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pending(context,id));return true;}catch(RuntimeException e){return false;}}
    static void restore(Context c){synchronized(LOCK){for(Map.Entry<String,?> entry:preferences(c).getAll().entrySet()){try{if(!(entry.getValue() instanceof String)||!entry.getKey().matches("[a-f0-9]{64}"))throw new JSONException("Invalid reminder");JSONObject value=new JSONObject((String)entry.getValue());long when=value.getLong("when");if(when<System.currentTimeMillis()-86400000L)preferences(c).edit().remove(entry.getKey()).apply();else register(c,entry.getKey(),Math.max(when,System.currentTimeMillis()+1000));}catch(RuntimeException|JSONException invalid){preferences(c).edit().remove(entry.getKey()).apply();}}}}
    static void deliver(Context c,String id){
        if(id==null||!id.matches("[a-f0-9]{64}"))return;
        synchronized(PortalAccess.ACCOUNT_LOCK){synchronized(KeywordRules.LOCK){synchronized(LOCK){String raw=preferences(c).getString(id,null);if(raw==null)return;
            try{JSONObject value=new JSONObject(raw);String profile=value.getString("profile");NoticeKey key=CampusNoticeStore.summaryKey(value.getString("source"),value.getString("id"));
                if(!identity(profile,key).equals(id)||value.getLong("when")>System.currentTimeMillis()+2000)return;
                // Consume once, even if the user switched accounts or revoked notifications.
                if(!preferences(c).edit().remove(id).commit())return;if(!profile.equals(PortalAccess.currentProfile(c))||!ReaderNotifications.canPost(c))return;
                try(CampusNoticeStore store=new CampusNoticeStore(c,profile)){CachedNotice row=store.find(key);if(row==null)return;
                    KeywordRules rules=KeywordRules.loadStrict(c,profile);if(rules.loadFailed||RecommendationEngine.blocked(row,rules))return;
                    NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(manager==null)return;String channel="notice_time_reminders";
                    if(Build.VERSION.SDK_INT>=26){NotificationChannel ch=new NotificationChannel(channel,"我设置的时间提醒",NotificationManager.IMPORTANCE_DEFAULT);ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(ch);}
                    Intent open=new Intent(c,ReaderActivity.class).setData(Uri.parse("campus-reminder://open/"+id)).putExtra(ReaderNotifications.EXTRA_PROFILE,profile).putExtra(EXTRA_SOURCE,key.source).putExtra(EXTRA_ID,key.stableId);
                    PendingIntent content=PendingIntent.getActivity(c,id.hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                    Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,channel):new Notification.Builder(c);
                    if(profile.equals(PortalAccess.currentProfile(c)))manager.notify(id,47080,builder.setSmallIcon(R.drawable.ic_launcher).setContentTitle(row.content.title).setContentText("你设置的通知时间提醒").setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(content).build());
                }
            }catch(RuntimeException|JSONException invalid){preferences(c).edit().remove(id).apply();}
        }}}
    }
}
