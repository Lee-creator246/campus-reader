package org.campusreader.reader;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-secret UI/scheduling preferences. Portal ownership is a local hashed profile, never a cookie. */
public final class ReaderPreferences {
    public static String lastProfileKey(){return "lastProfile:"+org.campusreader.config.CampusSite.scopeId();}
    private static final Object SESSION_LOCK = new Object();
    private final SharedPreferences prefs;
    private final Context context;
    public ReaderPreferences(Context context) { this.context=context.getApplicationContext();prefs=this.context.getSharedPreferences("reader-settings",Context.MODE_PRIVATE); }
    public boolean isBackgroundEnabled(){return prefs.getBoolean("background",true);}
    public boolean isRefreshOnOpen(){return prefs.getBoolean("refreshOnOpen",true);}
    public void setRefreshOnOpen(boolean value){prefs.edit().putBoolean("refreshOnOpen",value).apply();}
    public boolean isPortalAutoRestoreEnabled(){return prefs.getBoolean("portalAutoRestore",true);}
    public void setPortalAutoRestoreEnabled(boolean value){prefs.edit().putBoolean("portalAutoRestore",value).apply();}
    public void setBackgroundEnabled(boolean value){prefs.edit().putBoolean("background",value).apply();}
    public int getIntervalMinutes(){return SyncGate.intervalMinutes(prefs.getInt("interval",30));}
    public void setIntervalMinutes(int value){prefs.edit().putInt("interval",SyncGate.intervalMinutes(value)).apply();}
    public boolean isWifiOnly(){return prefs.getBoolean("wifiOnly",false);}
    public void setWifiOnly(boolean value){prefs.edit().putBoolean("wifiOnly",value).apply();}
    public boolean isNotificationsEnabled(){return prefs.getBoolean("notifications",true);}
    public void setNotificationsEnabled(boolean value){prefs.edit().putBoolean("notifications",value).apply();}
    public int getMaxCachedEntries(){return prefs.getInt("maxEntries",300);}
    public void setMaxCachedEntries(int value){if(value<0)throw new IllegalArgumentException("Negative capacity");prefs.edit().putInt("maxEntries",value).apply();}
    public long getMaxBodyBytes(){return prefs.getLong("maxBodyBytes",20L*1024*1024);}
    public void setMaxBodyBytes(long value){if(value<0)throw new IllegalArgumentException("Negative bytes");prefs.edit().putLong("maxBodyBytes",value).apply();}
    public long getReadRetentionMs(){return prefs.getLong("readRetentionMs",86400000L);}
    public void setReadRetentionMs(long value){if(value< -1)throw new IllegalArgumentException("Invalid age");prefs.edit().putLong("readRetentionMs",value).apply();}
    public long getImageCacheLimitBytes(){return prefs.getLong("imageCacheLimit",40L*1024*1024);}
    public void setImageCacheLimitBytes(long value){if(value<0)throw new IllegalArgumentException("Negative bytes");prefs.edit().putLong("imageCacheLimit",value).apply();}
    public long getLastAttemptMillis(){return getLastAttemptMillis(visibleProfile());}
    public String getLastResult(){return getLastResult(visibleProfile());}
    public long getLastAttemptMillis(String profile){return NotificationHistory.validProfile(profile)?prefs.getLong("lastAttempt:"+profile,0):0;}
    public String getLastResult(String profile){return NotificationHistory.validProfile(profile)?prefs.getString("lastResult:"+profile,""):"";}
    public long getLastSuccessfulMillis(String profile){return NotificationHistory.validProfile(profile)?prefs.getLong("lastSuccess:"+profile,0):0;}
    public long getLastSuccessfulMillis(){return getLastSuccessfulMillis(visibleProfile());}
    private String visibleProfile(){String profile=CampusSyncEngine.currentProfile();return profile.isEmpty()?context.getSharedPreferences("reader-local",Context.MODE_PRIVATE).getString(lastProfileKey(),"local"):profile;}
    void recordResult(CampusSyncEngine.Result result){
        if(!NotificationHistory.validProfile(result.profile))return;
        long now=System.currentTimeMillis();SharedPreferences.Editor edit=prefs.edit().putLong("lastAttempt:"+result.profile,now).putString("lastResult:"+result.profile,result.status.name());
        // Partial work is reported honestly and must not replace the last fully successful refresh.
        if(result.status==CampusSyncEngine.Status.SUCCESS)edit.putLong("lastSuccess:"+result.profile,now);
        edit.apply();
    }
    public String getPortalProfile(){return prefs.getString("portalProfile","");}
    public long getPortalGeneration(){return prefs.getLong("portalGeneration",0);}
    public void clearPortalSession(){synchronized(SESSION_LOCK){prefs.edit().remove("portalProfile").putLong("portalGeneration",getPortalGeneration()+1).apply();}}
    public void markPortalSession(String profile){synchronized(SESSION_LOCK){prefs.edit().putString("portalProfile",profile).apply();}}
    boolean markPortalSessionIfCurrent(String profile,long generation){synchronized(SESSION_LOCK){if(getPortalGeneration()!=generation)return false;markPortalSession(profile);return true;}}
    boolean hasReminder(String profile){return prefs.getBoolean("reminded:"+profile,false);}
    void markReminder(String profile){prefs.edit().putBoolean("reminded:"+profile,true).apply();}
    void clearReminder(String profile){prefs.edit().remove("reminded:"+profile).apply();}
}
