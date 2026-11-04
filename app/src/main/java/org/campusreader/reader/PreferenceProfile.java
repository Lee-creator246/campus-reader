package org.campusreader.reader;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Explicit account-local interests. This data is never included in AI requests. */
public final class PreferenceProfile {
    public static final List<String> AUDIENCES=Collections.unmodifiableList(Arrays.asList("未选择","本科生","研究生","教职工"));
    public final Set<String> topics;
    public final String audience;
    public final boolean learningEnabled,themeAlerts,onboardingDone,loadFailed;
    private PreferenceProfile(Collection<String> selected,String audience,boolean learning,boolean alerts,boolean done,boolean failed){
        LinkedHashSet<String> clean=new LinkedHashSet<>();
        for(String name:selected){if(!RecommendationEngine.TOPICS.contains(name))throw new IllegalArgumentException("未知关注主题");clean.add(name);}
        if(!AUDIENCES.contains(audience))throw new IllegalArgumentException("未知适用对象");
        topics=Collections.unmodifiableSet(clean);this.audience=audience;learningEnabled=learning;themeAlerts=alerts;onboardingDone=done;loadFailed=failed;
    }
    public static PreferenceProfile create(Collection<String> topics,String audience,boolean learning,boolean alerts){return new PreferenceProfile(topics,audience,learning,alerts,true,false);}
    private static PreferenceProfile empty(boolean failed){return new PreferenceProfile(Collections.emptySet(),"未选择",true,false,false,failed);}
    static SharedPreferences preferences(Context context){return context.getApplicationContext().getSharedPreferences("interest-profiles",Context.MODE_PRIVATE);}
    static String key(String profile){if(!NotificationHistory.validProfile(profile))throw new IllegalArgumentException("无效偏好账号");return "profile:"+profile;}
    public static PreferenceProfile load(Context context,String profile){
        synchronized(KeywordRules.LOCK){try{String raw=preferences(context).getString(key(profile),null);return raw==null?empty(false):decode(raw);}catch(RuntimeException invalid){return empty(true);}}
    }
    public static boolean isOnboardingDone(Context context,String profile){return load(context,profile).onboardingDone;}
    static boolean ownsCurrent(Context context,String profile){
        if(!NotificationHistory.validProfile(profile))return false;String current=CampusSyncEngine.currentProfile();
        if(current.isEmpty())current=context.getSharedPreferences("reader-local",Context.MODE_PRIVATE).getString(ReaderPreferences.lastProfileKey(),"local");return profile.equals(current);
    }
    public boolean save(Context context,String profile){
        if(loadFailed)throw new IllegalStateException("无法保存未读取的偏好");
        synchronized(KeywordRules.LOCK){boolean saved=preferences(context).edit().putString(key(profile),encode()).commit();if(saved)ReaderNotifications.clearNewItems(context);return saved;}
    }
    public static boolean skip(Context context,String profile){return new PreferenceProfile(Collections.emptySet(),"未选择",true,false,true,false).save(context,profile);}
    private String encode(){try{return new JSONObject().put("version",1).put("topics",new JSONArray(topics)).put("audience",audience).put("learning",learningEnabled).put("themeAlerts",themeAlerts).put("done",onboardingDone).toString();}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    private static PreferenceProfile decode(String raw){try{
        if(raw.length()>4096)throw new JSONException("Oversized preferences");JSONObject value=new JSONObject(raw);
        if(value.length()!=6||value.getInt("version")!=1)throw new JSONException("Invalid preferences");
        if(!(value.get("learning") instanceof Boolean)||!(value.get("themeAlerts") instanceof Boolean)||!(value.get("done") instanceof Boolean))throw new JSONException("Invalid preference type");
        JSONArray array=value.getJSONArray("topics");if(array.length()>RecommendationEngine.TOPICS.size())throw new JSONException("Too many topics");List<String> topics=new ArrayList<>();for(int i=0;i<array.length();i++)topics.add(array.getString(i));
        return new PreferenceProfile(topics,value.getString("audience"),value.getBoolean("learning"),value.getBoolean("themeAlerts"),value.getBoolean("done"),false);
    }catch(JSONException invalid){throw new IllegalArgumentException("Saved preferences cannot be read",invalid);}}
}
