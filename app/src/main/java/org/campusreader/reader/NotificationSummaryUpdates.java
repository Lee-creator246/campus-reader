package org.campusreader.reader;

import android.app.*;
import android.content.Context;
import android.os.*;
import android.service.notification.StatusBarNotification;
import org.campusreader.notices.*;
import java.util.*;
import java.util.concurrent.*;

/** Best-effort silent replacement of an existing publication, never a historical re-push. */
final class NotificationSummaryUpdates {
    static final String PROFILE="cr_summary_profile",EVENT="cr_summary_event",SOURCES="cr_summary_sources",IDS="cr_summary_ids",TITLES="cr_summary_titles",HASHES="cr_summary_hashes",SUMMARIES="cr_summary_texts";
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private NotificationSummaryUpdates(){}
    static void attach(Notification.Builder builder,String profile,List<NoticeKey> keys,List<String> titles,List<String> hashes){
        int count=Math.min(4,keys.size());String[] sources=new String[count],ids=new String[count],names=new String[count],versions=new String[count],summaries=new String[count];
        for(int i=0;i<count;i++){sources[i]=keys.get(i).source;ids[i]=keys.get(i).stableId;names[i]=shortText(titles.get(i),100);versions[i]=hashes.get(i);summaries[i]="";}
        Bundle data=new Bundle();data.putString(PROFILE,profile);data.putString(EVENT,UUID.randomUUID().toString());data.putStringArray(SOURCES,sources);data.putStringArray(IDS,ids);data.putStringArray(TITLES,names);data.putStringArray(HASHES,versions);data.putStringArray(SUMMARIES,summaries);builder.addExtras(data);
    }
    static void updateSummary(Context context,String profile,NoticeKey key){
        if(Build.VERSION.SDK_INT<24)return;Context app=context.getApplicationContext();
        String event=null;try{NotificationManager manager=(NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE);if(manager==null)return;
            for(StatusBarNotification posted:manager.getActiveNotifications())if(matches(posted,profile,key,null)){event=posted.getNotification().extras.getString(EVENT);break;}
        }catch(RuntimeException unavailable){return;}if(event==null)return;final String publication=event;
        WORKER.execute(()->{AiSummaryPresentation.Cached cached=AiSummaryPresentation.loadCached(app,profile,key);if(cached!=null)apply(app,profile,key,cached,publication);});
    }
    static boolean apply(Context c,String profile,NoticeKey key,AiSummaryPresentation.Cached cached,String publication){
        if(Build.VERSION.SDK_INT<24||cached==null||cached.summary==null||cached.snippet.isEmpty())return false;
        synchronized(PortalAccess.ACCOUNT_LOCK){synchronized(KeywordRules.LOCK){
            if(!profile.equals(CampusSyncEngine.currentProfile())||!ReaderNotifications.canPost(c)||new AiSettings(c,profile).revision()!=cached.settingsRevision)return false;
            NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(manager==null)return false;
            try(CampusNoticeStore store=new CampusNoticeStore(c,profile)){
                KeywordRules rules=KeywordRules.loadStrict(c,profile);
                for(StatusBarNotification posted:manager.getActiveNotifications()){
                    if(!matches(posted,profile,key,publication))continue;Notification original=posted.getNotification();Bundle extra=original.extras;
                    String[] sources=extra.getStringArray(SOURCES),ids=extra.getStringArray(IDS),titles=extra.getStringArray(TITLES),hashes=extra.getStringArray(HASHES),summaries=extra.getStringArray(SUMMARIES);
                    if(sources==null||ids==null||titles==null||hashes==null||summaries==null||ids.length<1||ids.length>4||sources.length!=ids.length||titles.length!=ids.length||hashes.length!=ids.length||summaries.length!=ids.length)return false;
                    for(int i=0;i<ids.length;i++){
                        NoticeKey entry=CampusNoticeStore.summaryKey(sources[i],ids[i]);CachedNotice row=store.find(entry);SummaryInput input=store.summaryInput(entry);
                        if(row==null||row.read||input==null||!input.contentHash.equals(hashes[i])||RecommendationEngine.blocked(row,rules))return false;
                        if(entry.equals(key)){if(!input.contentHash.equals(cached.source.contentHash)||input.cacheGeneration!=cached.source.cacheGeneration)return false;summaries[i]=shortText(cached.snippet,160);}
                    }
                    if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=manager.getNotificationChannel(original.getChannelId());if(channel==null||channel.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;}
                    Notification.InboxStyle style=new Notification.InboxStyle();for(int i=0;i<ids.length;i++)style.addLine(titles[i]+(summaries[i].isEmpty()?"":" · AI："+summaries[i]));
                    Bundle amended=new Bundle(extra);amended.putStringArray(SUMMARIES,summaries);
                    Notification updated=Notification.Builder.recoverBuilder(c,original).addExtras(amended).setStyle(style).setContentText(summaries[0].isEmpty()?titles[0]:"AI："+summaries[0])
                            .setOnlyAlertOnce(true).setSound(null).setVibrate(null).setDefaults(0).build();
                    if(!profile.equals(CampusSyncEngine.currentProfile()))return false;
                    // A newer aggregation or a dismissed publication must not be recreated by this result.
                    boolean stillActive=false;for(StatusBarNotification current:manager.getActiveNotifications())if(matches(current,profile,key,publication)){stillActive=true;break;}if(!stillActive)return false;
                    manager.notify(posted.getId(),updated);return true;
                }
            }catch(RuntimeException unavailable){return false;}
            return false;
        }}
    }
    private static boolean matches(StatusBarNotification posted,String profile,NoticeKey key,String publication){
        if(posted.getId()!=47062&&posted.getId()!=47065)return false;Bundle extras=posted.getNotification().extras;if(extras==null||!profile.equals(extras.getString(PROFILE)))return false;
        String event=extras.getString(EVENT);if(event==null||publication!=null&&!publication.equals(event))return false;String[] ids=extras.getStringArray(IDS),sources=extras.getStringArray(SOURCES);
        if(ids==null||sources==null||ids.length>4||ids.length!=sources.length)return false;for(int i=0;i<ids.length;i++)if(key.source.equals(sources[i])&&key.stableId.equals(ids[i]))return true;return false;
    }
    private static String shortText(String value,int max){return value.length()>max?value.substring(0,max)+"…":value;}
}
