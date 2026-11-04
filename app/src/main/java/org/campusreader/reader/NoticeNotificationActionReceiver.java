package org.campusreader.reader;

import android.content.*;
import android.app.NotificationManager;
import org.campusreader.notices.*;

/** Explicit local actions from this app's immutable PendingIntents. Never authenticates. */
public final class NoticeNotificationActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String profile=intent.getStringExtra("profile"),source=intent.getStringExtra("source"),id=intent.getStringExtra("id"),action=intent.getStringExtra("operation");
        if(!NotificationHistory.validProfile(profile)||!profile.equals(CampusSyncEngine.currentProfile())||source==null||id==null||source.length()>512||id.length()>8192)return;
        if(!"read".equals(action)&&!"later".equals(action))return;
        synchronized(PortalAccess.ACCOUNT_LOCK){synchronized(KeywordRules.LOCK){
            if(!profile.equals(CampusSyncEngine.currentProfile()))return;
            try(CampusNoticeStore store=new CampusNoticeStore(context,profile)){
                NoticeKey key=CampusNoticeStore.summaryKey(source,id);CachedNotice row=store.find(key);KeywordRules rules=KeywordRules.loadStrict(context,profile);
                if(row==null||RecommendationEngine.blocked(row,rules)||!profile.equals(CampusSyncEngine.currentProfile()))return;
                if("read".equals(action))store.setRead(key,true);else store.setLater(key,true);
                NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);int notificationId=intent.getIntExtra("notificationId",0);if(profile.equals(CampusSyncEngine.currentProfile())&&manager!=null&&(notificationId==47062||notificationId==47065))manager.cancel(notificationId);
            }catch(RuntimeException ignored){android.util.Log.w("CampusNotify","Notification action unavailable");}
        }}
    }
}
