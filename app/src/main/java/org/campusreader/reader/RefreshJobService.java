package org.campusreader.reader;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import org.campusreader.auth.CampusApplication;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded page attempt. An expired session waits for foreground user authentication. */
public final class RefreshJobService extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Run active;
    private static final class Run {final JobParameters params;final AtomicBoolean stopped=new AtomicBoolean();Future<?> future;Run(JobParameters p){params=p;}}
    @Override public boolean onStartJob(JobParameters params){
        ReaderPreferences prefs=new ReaderPreferences(this);
        if(!prefs.isBackgroundEnabled()||active!=null)return false;
        // A query only: never restore, log in, start a tunnel, or request an OTP here.
        CampusApplication.auth().synchronizeSdkState();
        Run run=new Run(params);active=run;
        run.future=worker.submit(()->{
            try {
                String profile=CampusSyncEngine.currentProfile();
                if(profile.isEmpty())profile=getSharedPreferences("reader-local",MODE_PRIVATE).getString("lastProfile","");
                final String requestedProfile=profile;
                boolean skipNetwork=prefs.isWifiOnly()&&!RefreshScheduler.wifiConnected(this);
                CampusSyncEngine.Result result=CampusSyncEngine.syncPage(this,requestedProfile,null,true,
                        ()->run.stopped.get()||!new ReaderPreferences(this).isBackgroundEnabled()||skipNetwork
                            ||(new ReaderPreferences(this).isWifiOnly()&&!RefreshScheduler.wifiConnected(this)));
                if(!run.stopped.get()){
                    if(prefs.isBackgroundEnabled()&&requestedProfile.equals(CampusSyncEngine.currentProfile()))ReaderNotifications.publishResult(this,result);
                }
            }catch(RuntimeException error){android.util.Log.w("CampusSync","Background attempt failed: "+error.getClass().getSimpleName());}
            finally{main.post(()->{if(active==run){active=null;if(!run.stopped.get())jobFinished(run.params,false);}});}
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        Run run=active;
        if(run!=null&&run.params.getJobId()==params.getJobId()){run.stopped.set(true);if(run.future!=null)run.future.cancel(true);active=null;}
        // Periodic schedule remains. Avoid an immediate authentication/network retry loop.
        return false;
    }
    @Override public void onDestroy(){if(active!=null){active.stopped.set(true);if(active.future!=null)active.future.cancel(true);active=null;}worker.shutdownNow();super.onDestroy();}
}
