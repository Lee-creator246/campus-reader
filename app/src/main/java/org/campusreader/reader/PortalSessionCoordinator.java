package org.campusreader.reader;

import android.content.Context;
import org.campusreader.notices.SummaryInput;

/** Serializes foreground recovery, including across multiple resumed Activity callbacks. */
public final class PortalSessionCoordinator {
    private static String active="";
    private PortalSessionCoordinator(){}
    private static android.content.SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences("portal-recovery",Context.MODE_PRIVATE);}
    private static String key(String profile){return SummaryInput.hash(profile);}
    public static synchronized boolean shouldAttempt(Context c,String profile){
        if(profile==null||!profile.matches("campus:[a-f0-9]{64}")||!active.isEmpty()||!new ReaderPreferences(c).isPortalAutoRestoreEnabled())return false;
        long next=prefs(c).getLong("next:"+key(profile),0),now=System.currentTimeMillis();
        return !prefs(c).getBoolean("paused:"+key(profile),false)&&(now>=next||next-now>24*60*60*1000L);
    }
    static synchronized boolean acquire(Context c,String profile,boolean automatic){
        if(!active.isEmpty()||(automatic&&!shouldAttempt(c,profile)))return false;
        active=profile;prefs(c).edit().putLong("next:"+key(profile),System.currentTimeMillis()+5*60*1000L).apply();return true;
    }
    public static synchronized void finished(String profile){if(active.equals(profile))active="";}
    static void pause(Context c,String profile){prefs(c).edit().putBoolean("paused:"+key(profile),true).apply();}
    static void success(Context c,String profile){prefs(c).edit().remove("paused:"+key(profile)).putLong("next:"+key(profile),System.currentTimeMillis()+60_000L).apply();}
}
