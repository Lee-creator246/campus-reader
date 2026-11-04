package org.campusreader.reader;

import android.app.job.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.campusreader.notices.*;

/** Account-bound durable, single-flight automatic work. Unknown sent outcomes are never auto-retried. */
public final class AiAutoSummaries {
    public static final String ACTION_UPDATED="org.campusreader.reader.AI_SUMMARY_UPDATED";
    static final int JOB_ID=47062,MAX_TASKS=60;
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final Object QUEUE_LOCK=new Object();
    private static volatile AiTransport.Cancellation activeCancellation;
    private static volatile String activeProfile="";
    private static volatile boolean draining;
    private static final String PROCESS=UUID.randomUUID().toString();
    private static String activeAttempt="";
    private AiAutoSummaries(){}
    public static void enqueueVisible(Context context,String profile,List<NoticeKey> keys){enqueue(context,profile,keys,10);}
    public static void enqueueNew(Context context,String profile,List<NoticeKey> keys){enqueue(context,profile,keys,20);}
    private static void enqueue(Context context,String profile,List<NoticeKey> keys,int limit){
        if(keys==null||keys.isEmpty()||!AiProfile.owns(context,profile))return;
        Context app=context.getApplicationContext();List<NoticeKey> copy=new ArrayList<>(keys.subList(0,Math.min(limit,keys.size())));
        WORKER.execute(()->{
            try{
                AiSettings settings=new AiSettings(app,profile);if(!settings.automaticEnabled()||!AiProfile.owns(app,profile))return;
                AiSettings.Config config=settings.load();PolicyLibrary.Snapshot policies=new PolicyLibrary(app,profile).snapshot();
                boolean changed=false;
                synchronized(AiSettings.LOCK){synchronized(QUEUE_LOCK){List<Task> tasks=read(app,profile);
                    try(CampusNoticeStore store=new CampusNoticeStore(app,profile)){
                        KeywordRules rules=KeywordRules.load(app,profile);if(rules.loadFailed)return;
                        for(NoticeKey key:copy){CachedNotice notice=store.find(key);SummaryInput input=store.summaryInput(key);if(notice==null||input==null||blocked(rules,input))continue;
                            AiEvidence.Snapshot evidence=context(input,policies,settings.automaticPolicies());String hash=cacheHash(evidence,config);
                            if(store.findSummary(key,input.contentHash,hash)!=null||attempted(app,profile,key,input.contentHash))continue;
                            String id=identity(key,input.contentHash,hash);boolean exists=false;for(Task task:tasks)if(task.id.equals(id)){exists=true;if(task.state.equals("stale")||(task.state.equals("quota")&&settings.usedToday()<settings.dailyLimit())){task.state="waiting";changed=true;}break;}if(exists)continue;
                            // Replaced content/config for the same notice invalidates stale unsent tasks.
                            tasks.removeIf(t->t.key.equals(key)&&t.state.equals("waiting"));
                            tasks.add(new Task(id,key,input.contentHash,hash,"waiting",System.currentTimeMillis(),config.revision));
                            changed=true;
                            while(tasks.size()>MAX_TASKS)tasks.remove(0);
                        }
                    }
                    if(changed)write(app,profile,tasks);
                }}
                if(!changed)return;
                schedule(app,profile);drain(app,profile,()->false);
            }catch(Exception unavailable){/* Storage/config failures stop the batch without logging content. */}
        });
    }
    public static void kick(Context context){
        String profile=CampusSyncEngine.currentProfile();if(profile==null||profile.isEmpty())profile=context.getSharedPreferences("reader-local",0).getString(ReaderPreferences.lastProfileKey(),"local");
        final String owner=profile;Context app=context.getApplicationContext();
        if(!AiProfile.owns(app,owner))return;
        WORKER.execute(()->{try{if(new AiSettings(app,owner).automaticEnabled()){schedule(app,owner);drain(app,owner,()->false);}}catch(RuntimeException unavailable){}});
    }
    public static String status(Context c,String profile,NoticeKey key){
        try{synchronized(QUEUE_LOCK){List<Task> tasks=read(c,profile);for(int i=tasks.size()-1;i>=0;i--){Task t=tasks.get(i);if(!t.key.equals(key))continue;
            switch(t.state){case "waiting":return "摘要已排队，联网后自动处理。";case "sending":return "正在生成摘要，稍后自动更新。";case "quota":return "今日摘要额度已用完，可明天继续。";case "uncertain":return "上次请求结果未知，未自动重发；可在摘要页手动重试。";case "failed":return "摘要生成失败，未自动重复请求；可在摘要页重试。";default:break;}
        }}}catch(RuntimeException unavailable){}return "暂无缓存摘要，自动摘要会优先处理当前页和新通知。";
    }
    public static void cancelAndClear(Context context,String profile){
        if(profile.equals(activeProfile)&&activeCancellation!=null)activeCancellation.cancel();
        synchronized(QUEUE_LOCK){write(context,profile,new ArrayList<>());}
        synchronized(AiSettings.LOCK){try(CampusNoticeStore store=new CampusNoticeStore(context,profile)){android.database.sqlite.SQLiteDatabase db=attemptDb(store);db.delete("ai_attempts",null,null);}}
        updated(context);
    }
    public static void settingsChanged(Context context,String profile){if(profile.equals(activeProfile)&&activeCancellation!=null)activeCancellation.cancel();updated(context);}
    public static void resultUpdated(Context context){updated(context);}
    public static boolean isPending(Context context,String profile,NoticeKey key){try{synchronized(QUEUE_LOCK){for(Task t:read(context,profile))if(t.key.equals(key)&&(t.state.equals("waiting")||t.state.equals("sending")))return true;}}catch(RuntimeException unavailable){}return false;}
    static AiEvidence.Snapshot context(SummaryInput input,PolicyLibrary.Snapshot policies,boolean includePolicies){
        return AiEvidence.build(input,includePolicies?policies:new PolicyLibrary.Snapshot(0,Collections.emptyList()),Collections.emptyList());
    }
    static String cacheHash(AiEvidence.Snapshot context,AiSettings.Config config){return SummaryInput.hash(context.cacheHash(config.configHash)+"\u0000"+AiBriefSummary.VERSION);}
    static String identity(NoticeKey key,String content,String hash){return SummaryInput.hash(key.source+"\u0000"+key.stableId+"\u0000"+content+"\u0000"+hash);}
    private static android.database.sqlite.SQLiteDatabase attemptDb(CampusNoticeStore store){android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.execSQL("CREATE TABLE IF NOT EXISTS ai_attempts (source TEXT NOT NULL,stable_id TEXT NOT NULL,content_hash TEXT NOT NULL,state TEXT NOT NULL,owner TEXT NOT NULL,attempted_ms INTEGER NOT NULL,PRIMARY KEY(source,stable_id),FOREIGN KEY(source,stable_id) REFERENCES campus_notices(source,stable_id) ON DELETE CASCADE)");return db;}
    static boolean attempted(Context c,String profile,NoticeKey key,String content){synchronized(AiSettings.LOCK){try(CampusNoticeStore store=new CampusNoticeStore(c,profile);android.database.Cursor row=attemptDb(store).query("ai_attempts",new String[]{"content_hash"},"source=? AND stable_id=?",new String[]{key.source,key.stableId},null,null,null)){return row.moveToFirst()&&content.equals(row.getString(0));}}}
    /** Both manual and automatic paths share this lease. Sent failures remain durable per notice version. */
    static boolean beginAttempt(Context c,String profile,NoticeKey key,String content,boolean manual,AiSettings settings,AiSettings.Config config,AiTransport.Guard guard)throws IOException{synchronized(AiSettings.LOCK){
        guard.check();if(!activeAttempt.isEmpty())throw new IOException("BUSY");try(CampusNoticeStore store=new CampusNoticeStore(c,profile)){android.database.sqlite.SQLiteDatabase db=attemptDb(store);
            try(android.database.Cursor row=db.query("ai_attempts",new String[]{"content_hash","state","owner"},"source=? AND stable_id=?",new String[]{key.source,key.stableId},null,null,null)){
                if(row.moveToFirst()&&content.equals(row.getString(0))&&(!manual||("sending".equals(row.getString(1))&&PROCESS.equals(row.getString(2)))))return false;
            }
            if(!settings.reserve(config))throw new IOException("QUOTA");
            android.content.ContentValues values=new android.content.ContentValues();values.put("source",key.source);values.put("stable_id",key.stableId);values.put("content_hash",content);values.put("state","sending");values.put("owner",PROCESS);values.put("attempted_ms",System.currentTimeMillis());
            if(db.insertWithOnConflict("ai_attempts",null,values,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IOException("QUEUE_STORAGE");activeAttempt=profile+"|"+identity(key,content,"");return true;
        }
    }}
    static void finishAttempt(Context c,String profile,NoticeKey key,String content,String state){synchronized(AiSettings.LOCK){try(CampusNoticeStore store=new CampusNoticeStore(c,profile)){android.content.ContentValues v=new android.content.ContentValues();v.put("state",state);attemptDb(store).update("ai_attempts",v,"source=? AND stable_id=? AND content_hash=? AND owner=?",new String[]{key.source,key.stableId,content,PROCESS});}catch(RuntimeException unavailable){/* prior sending marker conservatively prevents automatic resend */}finally{if(activeAttempt.equals(profile+"|"+identity(key,content,"")))activeAttempt="";}}}
    static Future<?> runJob(Context c,String profile,AtomicBoolean stopped,Runnable done){Context app=c.getApplicationContext();return WORKER.submit(()->{try{drain(app,profile,stopped::get);}finally{done.run();}});}
    private static void drain(Context c,String profile,java.util.function.BooleanSupplier stopped){
        if(draining)return;draining=true;activeProfile=profile;
        try{
            int attempts=0;
            while(attempts<3&&!stopped.getAsBoolean()&&AiProfile.owns(c,profile)){
                AiSettings settings=new AiSettings(c,profile);if(!settings.automaticEnabled())return;
                AiSettings.Config config=settings.load();if(settings.usedToday()>=settings.dailyLimit()){markWaiting(c,profile,"quota");return;}
                if(!network(c)){schedule(c,profile);return;}
                Task task=next(c,profile);if(task==null)return;
                try(CampusNoticeStore store=new CampusNoticeStore(c,profile)){
                    SummaryInput input=store.summaryInput(task.key);if(input==null||!input.contentHash.equals(task.content)){setState(c,profile,task,"stale");continue;}
                    KeywordRules rules=KeywordRules.load(c,profile);if(rules.loadFailed||blocked(rules,input)){setState(c,profile,task,"stale");continue;}
                    PolicyLibrary library=new PolicyLibrary(c,profile);PolicyLibrary.Snapshot policies=library.snapshot();boolean include=settings.automaticPolicies();AiEvidence.Snapshot evidence=context(input,policies,include);
                    if(!cacheHash(evidence,config).equals(task.hash)){setState(c,profile,task,"stale");continue;}
                    if(store.findSummary(task.key,input.contentHash,task.hash)!=null){setState(c,profile,task,"done");continue;}
                    AiTransport.Cancellation cancellation=new AiTransport.Cancellation();activeCancellation=cancellation;
                    AiTransport.Guard guard=()->{
                        cancellation.check();if(stopped.getAsBoolean()||!AiProfile.owns(c,profile)||!settings.automaticEnabled()||!settings.isCurrent(config))throw new IOException("STALE");
                        if(include&&!library.isCurrent(policies.revision))throw new IOException("STALE");
                        KeywordRules latest=KeywordRules.load(c,profile);if(latest.loadFailed||blocked(latest,input))throw new IOException("STALE");
                        try(CampusNoticeStore check=new CampusNoticeStore(c,profile)){SummaryInput now=check.summaryInput(task.key);if(now==null||now.cacheGeneration!=input.cacheGeneration||!now.contentHash.equals(input.contentHash))throw new IOException("STALE");}
                    };
                    if(!beginAttempt(c,profile,task.key,input.contentHash,false,settings,config,guard)){setState(c,profile,task,"uncertain");continue;}
                    setState(c,profile,task,"sending");
                    attempts++;updated(c);
                    try{
                        String json=AiTransport.requestJson(config,AiBriefSummary.instruction(),evidence.exactText,cancellation,guard);
                        AiBriefSummary result=AiBriefSummary.parse(json,evidence);guard.check();
                        synchronized(AiSettings.LOCK){guard.check();long at=System.currentTimeMillis();if(!store.saveSummaryIfCurrent(task.key,input.contentHash,input.cacheGeneration,task.hash,result.json,at)||!AiCoreCache.save(store,task.key,input,config,result,at))throw new IOException("STALE");}
                        setState(c,profile,task,"done");
                        finishAttempt(c,profile,task.key,input.contentHash,"done");
                        ReaderNotifications.updateSummary(c,profile,task.key);
                    }catch(Exception failed){
                        // Once reserved/sent, transport errors can be billable. Never silently re-send them.
                        setState(c,profile,task,failed instanceof IOException&&!(failed instanceof AiTransport.ResponseException)?"uncertain":"failed");
                        finishAttempt(c,profile,task.key,input.contentHash,"uncertain");
                    }finally{activeCancellation=null;updated(c);}
                }catch(Exception failed){if("BUSY".equals(failed.getMessage())){setState(c,profile,task,"waiting");schedule(c,profile);return;}if("QUOTA".equals(failed.getMessage())){setState(c,profile,task,"quota");return;}setState(c,profile,task,"stale");}
            }
            if(AiProfile.owns(c,profile)&&new AiSettings(c,profile).automaticEnabled())schedule(c,profile);
        }finally{draining=false;activeProfile="";activeCancellation=null;}
    }
    private static boolean blocked(KeywordRules rules,SummaryInput input){if(!rules.blockedEnabled)return false;String text=(input.title+"\n"+input.bodyText).toLowerCase(Locale.ROOT);for(String word:rules.blockedWords)if(text.contains(word.toLowerCase(Locale.ROOT)))return true;return false;}
    private static boolean network(Context c){try{ConnectivityManager m=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);Network n=m==null?null:m.getActiveNetwork();NetworkCapabilities caps=n==null?null:m.getNetworkCapabilities(n);return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}catch(RuntimeException unavailable){return false;}}
    private static void schedule(Context c,String profile){
        try{if(!new AiSettings(c,profile).automaticEnabled()||!AiProfile.owns(c,profile))return;synchronized(QUEUE_LOCK){boolean waiting=false;for(Task t:read(c,profile))if(t.state.equals("waiting")){waiting=true;break;}if(!waiting)return;}
            JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(scheduler==null)return;
            for(JobInfo job:scheduler.getAllPendingJobs())if(job.getId()==JOB_ID&&profile.equals(job.getExtras().getString("profile")))return;
            PersistableBundle extras=new PersistableBundle();extras.putString("profile",profile);
            scheduler.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(c,AiAutoJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(5000).setExtras(extras).build());
        }catch(RuntimeException unavailable){/* foreground kickoff can still resume durable work */}
    }
    private static Task next(Context c,String profile){boolean quotaAvailable=new AiSettings(c,profile).usedToday()<new AiSettings(c,profile).dailyLimit();synchronized(QUEUE_LOCK){List<Task> tasks=read(c,profile);boolean changed=false;Task next=null;for(Task task:tasks){if(task.state.equals("sending")){task.state="uncertain";changed=true;}if(next==null&&task.state.equals("waiting"))next=task;}
        if(next==null&&quotaAvailable)for(Task task:tasks)if(task.state.equals("quota")){task.state="waiting";next=task;changed=true;break;}
        if(changed)write(c,profile,tasks);return next;}}
    private static void markWaiting(Context c,String profile,String state){synchronized(QUEUE_LOCK){List<Task> tasks=read(c,profile);for(Task t:tasks)if(t.state.equals("waiting"))t.state=state;write(c,profile,tasks);}updated(c);}
    private static void setState(Context c,String profile,Task task,String state){synchronized(QUEUE_LOCK){List<Task> tasks=read(c,profile);for(Task t:tasks)if(t.id.equals(task.id)){t.state=state;break;}write(c,profile,tasks);}}
    private static AtomicFile file(Context c,String profile){if(!profile.equals("local")&&!profile.matches("campus:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid profile");return new AtomicFile(new File(c.getApplicationContext().getNoBackupFilesDir(),"ai-queue-"+SummaryInput.hash(profile)+".json"));}
    static List<Task> read(Context c,String profile){
        AtomicFile file=file(c,profile);List<Task> tasks=new ArrayList<>();if(!file.getBaseFile().exists()&&!new File(file.getBaseFile()+".bak").exists())return tasks;
        try(InputStream input=file.openRead()){
            String data=new String(PolicyImport.readBounded(input,512*1024),StandardCharsets.UTF_8);AiGroundedSummary.validateJson(data);JSONObject object=new JSONObject(data);if(object.getInt("schema")!=1)throw new IOException("Queue schema");JSONArray items=object.getJSONArray("tasks");if(items.length()>MAX_TASKS)throw new IOException("Queue size");
            for(int i=0;i<items.length();i++){JSONObject o=items.getJSONObject(i);Task t=new Task(o.getString("id"),CampusNoticeStore.summaryKey(o.getString("source"),o.getString("stable")),o.getString("content"),o.getString("hash"),o.getString("state"),o.getLong("created"),o.getLong("revision"));if(!t.id.equals(identity(t.key,t.content,t.hash))||!t.content.matches("[a-f0-9]{64}")||!t.hash.matches("[a-f0-9]{64}"))throw new IOException("Queue identity");tasks.add(t);}
            return tasks;
        }catch(Exception failed){throw new IllegalStateException("摘要任务暂时无法读取，请在设置中清理任务后重试");}
    }
    static void write(Context c,String profile,List<Task> tasks){
        AtomicFile f=file(c,profile);FileOutputStream output=null;
        try{JSONArray items=new JSONArray();for(Task t:tasks)items.put(new JSONObject().put("id",t.id).put("source",t.key.source).put("stable",t.key.stableId).put("content",t.content).put("hash",t.hash).put("state",t.state).put("created",t.created).put("revision",t.revision));byte[] bytes=new JSONObject().put("schema",1).put("tasks",items).toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>512*1024)throw new IOException("Queue limit");output=f.startWrite();output.write(bytes);f.finishWrite(output);
        }catch(Exception failed){if(output!=null)f.failWrite(output);throw new IllegalStateException("摘要任务未能保存");}
    }
    private static void updated(Context c){AiSummaryPresentation.invalidateCache();c.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(c.getPackageName()));}
    static final class Task {final String id,content,hash;final NoticeKey key;String state;final long created,revision;Task(String id,NoticeKey key,String content,String hash,String state,long created,long revision){this.id=id;this.key=key;this.content=content;this.hash=hash;this.state=state;this.created=created;this.revision=revision;}}
}
