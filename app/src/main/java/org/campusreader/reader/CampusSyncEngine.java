package org.campusreader.reader;

import org.campusreader.config.CampusSite;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import org.campusreader.auth.CampusApplication;
import org.campusreader.notices.*;
import org.campusreader.portal.CampusPortalParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Worker-thread one-page sync. It never starts authentication, launches UI, or logs credentials. */
public final class CampusSyncEngine {
    public enum Status { SUCCESS, PARTIAL, BUSY, NO_PROFILE, TUNNEL_REQUIRED, PORTAL_LOGIN_REQUIRED, ACCOUNT_CHANGED, CANCELLED, NETWORK_ERROR, PARSE_ERROR }
    public interface CancelCheck { boolean isCancelled(); }
    /** Called on the sync worker after list validation; contains counts only, never notice/session data. */
    public interface ProgressListener { void onProgress(int completed,int total); }
    public static final class Result {
        public final Status status;
        public final String profile;
        public final int saved,newCount,failed;
        public final long unreadCount;
        public final CampusPortalParser.MoreRequest nextPage;
        /** Only identities first inserted by this sync, including commits before a partial failure. */
        public final List<NoticeKey> insertedKeys;
        Result(Status status,String profile,int saved,int newCount,int failed,long unreadCount,CampusPortalParser.MoreRequest nextPage){
            this(status,profile,saved,newCount,failed,unreadCount,nextPage,Collections.emptyList());
        }
        Result(Status status,String profile,int saved,int newCount,int failed,long unreadCount,CampusPortalParser.MoreRequest nextPage,List<NoticeKey> insertedKeys){
            this.status=status;this.profile=profile;this.saved=saved;this.newCount=newCount;this.failed=failed;this.unreadCount=unreadCount;this.nextPage=nextPage;
            this.insertedKeys=Collections.unmodifiableList(new ArrayList<>(insertedKeys));
        }
        public boolean mayAdvance(){return status==Status.SUCCESS;}
    }
    private CampusSyncEngine(){}
    public static SyncGate.Lease tryAcquire(String profile){return SyncGate.tryAcquire(profile);}
    public static String currentProfile(){String account=CampusApplication.auth().getProfileId();return account==null||account.trim().isEmpty()?"":MediaSafety.readerProfile(account);}
    public static Result syncPage(Context context,String profile,CampusPortalParser.MoreRequest request,boolean background,CancelCheck cancel){
        return syncPage(context,profile,request,background,cancel,null);
    }
    public static Result syncPage(Context context,String profile,CampusPortalParser.MoreRequest request,boolean background,CancelCheck cancel,ProgressListener progress){
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Sync must run on a worker thread");
        Result result=performSync(context,profile,request,background,cancel,progress);
        // Include short-circuits (busy, offline, no cookie, cancelled), not just downloaded pages.
        new ReaderPreferences(context).recordResult(result);
        NotificationHistory.refresh(context,result,background);
        return result;
    }
    private static Result performSync(Context context,String profile,CampusPortalParser.MoreRequest request,boolean background,CancelCheck cancel,ProgressListener progress){
        if(profile==null||!profile.startsWith("campus:"))return result(Status.NO_PROFILE,profile,0,0,0,0,request);
        SyncGate.Lease lease=tryAcquire(profile);
        if(lease==null)return result(Status.BUSY,profile,0,0,0,0,request);
        ReaderPreferences prefs=new ReaderPreferences(context);
        long generation=prefs.getPortalGeneration();
        // Checked between requests; this does not interrupt an in-flight HTTP read.
        long deadline=SystemClock.elapsedRealtime()+180000L;
        int saved=0,inserted=0,failed=0;
        List<NoticeKey> insertedKeys=new ArrayList<>();
        long unread=0;
        Status outcome=Status.SUCCESS;
        CampusPortalParser.MoreRequest next=request;
        try(SyncGate.Lease ignored=lease;CampusNoticeStore store=new CampusNoticeStore(context,profile)){
            // Local retention runs even when networking is cancelled/offline or authentication expired.
            store.enforceRetention(System.currentTimeMillis());
            Status guard=check(profile,generation,prefs,cancel,deadline);
            if(guard!=null)return result(guard,profile,0,0,0,0,request);
            String cookie=CookieManager.getInstance().getCookie(CampusSite.portalListUrl());
            if((background&&!profile.equals(prefs.getPortalProfile()))||cookie==null||cookie.trim().isEmpty()){
                store.setConnectionState(CampusPortalParser.SOURCE,CampusNoticeStore.ConnectionState.REAUTH_REQUIRED);
                signalPortal(context,profile,generation,true);
                return result(Status.PORTAL_LOGIN_REQUIRED,profile,0,0,0,0,request);
            }
            CampusPortalParser parser=new CampusPortalParser();
            try {
                ReaderHttp.Response response=ReaderHttp.get(request==null?CampusSite.portalListUrl():request.url(),5*1024*1024,true);
                guard=check(profile,generation,prefs,cancel,deadline);
                if(guard!=null)return result(guard,profile,0,0,0,0,request);
                CampusPortalParser.Page page=request==null?parser.parseList(response.url,response.status,response.text()):parser.parseMore(response.url,response.status,response.text(),request);
                if(!prefs.markPortalSessionIfCurrent(profile,generation))return result(Status.ACCOUNT_CHANGED,profile,0,0,0,0,request);
                notifyProgress(progress,0,page.notices.size());
                for(CampusPortalParser.Summary summary:page.notices){
                    guard=check(profile,generation,prefs,cancel,deadline);
                    if(guard!=null){outcome=guard;break;}
                    int completedBefore=saved+failed;
                    try {
                        CampusNotice listing=new CampusNotice(summary.key,summary.title,summary.publishedAtMillis,"",summary.originalUrl,Collections.emptyList(),Collections.emptyList());
                        NoticeRefreshPlan.Content content=NoticeRefreshPlan.resolve(store.find(summary.key),listing,()->{
                            ReaderHttp.Response detail=ReaderHttp.get(summary.originalUrl,5*1024*1024,true);
                            return parser.parseDetail(detail.url,detail.status,detail.text()).notice;
                        });
                        CampusNotice notice=content.notice;
                        if(!summary.key.equals(notice.key)){failed++;continue;}
                        guard=check(profile,generation,prefs,cancel,deadline);
                        if(guard!=null){outcome=guard;break;}
                        CampusNoticeStore.UpsertResult added=content.metadataOnly
                                ?store.upsertMetadata(Collections.singletonList(notice),System.currentTimeMillis())
                                :store.upsert(Collections.singletonList(notice),System.currentTimeMillis());
                        saved++;inserted+=added.insertedUnreadCount;
                        insertedKeys.addAll(added.insertedKeys);
                    }catch(CampusPortalParser.ParseException error){
                        guard=check(profile,generation,prefs,cancel,deadline);
                        if(guard!=null){outcome=guard;break;}
                        failed++;
                        if(error.failure==CampusPortalParser.Failure.REAUTH_REQUIRED){outcome=Status.PORTAL_LOGIN_REQUIRED;break;}
                    }catch(IOException error){
                        guard=check(profile,generation,prefs,cancel,deadline);
                        if(guard!=null){outcome=guard;break;}
                        failed++;
                    }finally{
                        if(saved+failed>completedBefore)notifyProgress(progress,saved+failed,page.notices.size());
                    }
                }
                guard=check(profile,generation,prefs,cancel,deadline);
                if(guard!=null)outcome=guard;
                if(outcome==Status.SUCCESS&&failed>0)outcome=Status.PARTIAL;
                if(outcome==Status.SUCCESS)next=page.next;
            }catch(CampusPortalParser.ParseException error){
                guard=check(profile,generation,prefs,cancel,deadline);
                outcome=guard!=null?guard:error.failure==CampusPortalParser.Failure.REAUTH_REQUIRED?Status.PORTAL_LOGIN_REQUIRED:Status.PARSE_ERROR;
            }catch(IOException error){
                guard=check(profile,generation,prefs,cancel,deadline);outcome=guard!=null?guard:Status.NETWORK_ERROR;
            }
            if(outcome==Status.PORTAL_LOGIN_REQUIRED){
                store.setConnectionState(CampusPortalParser.SOURCE,CampusNoticeStore.ConnectionState.REAUTH_REQUIRED);signalPortal(context,profile,generation,true);
            }else if(outcome==Status.SUCCESS||outcome==Status.PARTIAL){
                store.setConnectionState(CampusPortalParser.SOURCE,CampusNoticeStore.ConnectionState.AVAILABLE);signalPortal(context,profile,generation,false);ReaderNotifications.clearSessionReminder(context,profile);
            }
            unread=store.count(NoticeQuery.builder(CampusPortalParser.SOURCE).unreadOnly(true).build());
        }catch(RuntimeException unexpected){
            // Database/platform failure leaves the previously committed rows intact.
            android.util.Log.w("CampusSync","Sync failed: "+unexpected.getClass().getSimpleName());outcome=Status.NETWORK_ERROR;
        }finally{
            // The SQLite helper above is already closed; purge images whose bodies were evicted.
            try{ImageCacheMaintenance.prune(context,profile);}catch(RuntimeException failure){android.util.Log.w("CampusSync","Image cleanup failed: "+failure.getClass().getSimpleName());}
        }
        return new Result(outcome,profile,saved,inserted,failed,unread,next,insertedKeys);
    }
    private static void notifyProgress(ProgressListener listener,int completed,int total){
        if(listener==null)return;
        try{listener.onProgress(completed,total);}catch(RuntimeException ignored){
            // A UI callback must not turn a successful download into a failed sync.
            android.util.Log.w("CampusSync","Progress callback failed");
        }
    }
    private static Result result(Status s,String p,int saved,int added,int failed,long unread,CampusPortalParser.MoreRequest next){return new Result(s,p==null?"":p,saved,added,failed,unread,next);}
    private static Status check(String profile,long generation,ReaderPreferences prefs,CancelCheck cancel,long deadline){
        if(Thread.currentThread().isInterrupted()||(cancel!=null&&cancel.isCancelled())||SystemClock.elapsedRealtime()>=deadline)return Status.CANCELLED;
        if(!profile.equals(currentProfile())||prefs.getPortalGeneration()!=generation)return Status.ACCOUNT_CHANGED;
        return CampusApplication.auth().isAccessReady()?null:Status.TUNNEL_REQUIRED;
    }
    private static void signalPortal(Context context,String profile,long generation,boolean required){new Handler(Looper.getMainLooper()).post(()->{
        if(!profile.equals(currentProfile())||new ReaderPreferences(context).getPortalGeneration()!=generation)return;
        if(required)CampusApplication.auth().markPortalAuthenticationRequired();else CampusApplication.auth().clearPortalAuthenticationRequired();
    });}
}
