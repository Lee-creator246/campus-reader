package org.campusreader.reader;

import android.app.job.*;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** System-scheduled work resumes only the current account's explicitly enabled queue. */
public final class AiAutoJobService extends JobService {
    private final AtomicBoolean stopped=new AtomicBoolean();private Future<?> future;private JobParameters active;
    @Override public boolean onStartJob(JobParameters params){String profile=params.getExtras().getString("profile","");if(!AiProfile.owns(this,profile)||!new AiSettings(this,profile).automaticEnabled())return false;active=params;stopped.set(false);
        future=AiAutoSummaries.runJob(this,profile,stopped,()->new Handler(Looper.getMainLooper()).post(()->{if(active==params&&!stopped.get()){active=null;jobFinished(params,false);}}));return true;}
    @Override public boolean onStopJob(JobParameters params){stopped.set(true);active=null;if(future!=null)future.cancel(true);return false;}
    @Override public void onDestroy(){stopped.set(true);if(future!=null)future.cancel(true);super.onDestroy();}
}
