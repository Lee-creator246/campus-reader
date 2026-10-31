package org.campusreader.reader;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.PersistableBundle;
import java.util.concurrent.TimeUnit;

/** Inexact, battery-aware periodic work. No alarms, foreground service, or battery bypass. */
public final class RefreshScheduler {
    public static final int JOB_ID=47061;
    private RefreshScheduler(){}
    public static boolean schedule(Context context){
        ReaderPreferences prefs=new ReaderPreferences(context);
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)return false;
        if(!prefs.isBackgroundEnabled()){scheduler.cancel(JOB_ID);return true;}
        long interval=TimeUnit.MINUTES.toMillis(prefs.getIntervalMinutes());
        try {
            // Calling schedule on every resume would reset the period and stop an active job.
            for(JobInfo job:scheduler.getAllPendingJobs())if(job.getId()==JOB_ID&&job.getIntervalMillis()==interval
                    &&job.getExtras().getBoolean("wifiOnly",false)==prefs.isWifiOnly()&&job.getExtras().getInt("schema",0)==1)return true;
            PersistableBundle extras=new PersistableBundle();extras.putInt("schema",1);extras.putBoolean("wifiOnly",prefs.isWifiOnly());
            JobInfo.Builder job=new JobInfo.Builder(JOB_ID,new ComponentName(context,RefreshJobService.class))
                    .setPeriodic(interval).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setExtras(extras);
            if(Build.VERSION.SDK_INT>=28&&prefs.isWifiOnly())job.setRequiredNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build());
            // API 23-27 use ANY plus the same strict runtime Wi-Fi check; metered Wi-Fi is still Wi-Fi.
            return scheduler.schedule(job.build())==JobScheduler.RESULT_SUCCESS;
        }catch(RuntimeException failure){android.util.Log.w("CampusSync","Schedule failed: "+failure.getClass().getSimpleName());return false;}
    }
    public static void cancel(Context context){JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(scheduler!=null)scheduler.cancel(JOB_ID);}
    static boolean wifiConnected(Context context){
        try {
            ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(manager==null)return false;
            Network active=manager.getActiveNetwork();
            NetworkCapabilities caps=active==null?null:manager.getNetworkCapabilities(active);
            // Merely being associated with Wi-Fi does not prove the VPN/default route uses it.
            // If a vendor VPN omits its underlying transport, skip conservatively rather than use mobile data.
            return caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }catch(RuntimeException unavailable){return false;}
    }
}
