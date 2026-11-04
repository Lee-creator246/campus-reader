package org.campusreader.reader;

import android.content.Context;
import org.campusreader.notices.NoticeKey;
import org.json.*;
import java.util.*;

/** Bounded delivery receipts survive duplicate sync callbacks; they contain hashes only. */
final class NotificationDeliveryLedger {
    private static final int MAX=400;
    private static final long AGE=14L*24*60*60*1000;
    private NotificationDeliveryLedger(){}
    private static String hash(NoticeKey key){return NotificationHistory.digest(key.source+'\0'+key.stableId);}
    private static android.content.SharedPreferences prefs(Context context){return context.getApplicationContext().getSharedPreferences("notification-delivery",Context.MODE_PRIVATE);}
    static boolean contains(Context context,String profile,NoticeKey key){synchronized(KeywordRules.LOCK){return read(context,profile).containsKey(hash(key));}}
    static boolean mark(Context context,String profile,List<NoticeKey> keys){synchronized(KeywordRules.LOCK){
        LinkedHashMap<String,Long> entries=read(context,profile);long now=System.currentTimeMillis();for(NoticeKey key:keys){String id=hash(key);entries.remove(id);entries.put(id,now);}while(entries.size()>MAX)entries.remove(entries.keySet().iterator().next());
        try{JSONArray values=new JSONArray();for(Map.Entry<String,Long> item:entries.entrySet())values.put(new JSONObject().put("id",item.getKey()).put("time",item.getValue()));return prefs(context).edit().putString(PreferenceProfile.key(profile),values.toString()).commit();}catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }}
    private static LinkedHashMap<String,Long> read(Context context,String profile){
        LinkedHashMap<String,Long> result=new LinkedHashMap<>();String raw=prefs(context).getString(PreferenceProfile.key(profile),"[]");
        try{if(raw.length()>65536)throw new JSONException("Oversized ledger");JSONArray values=new JSONArray(raw);if(values.length()>MAX)throw new JSONException("Too many receipts");long now=System.currentTimeMillis();for(int i=0;i<values.length();i++){JSONObject item=values.getJSONObject(i);String id=item.getString("id");long time=item.getLong("time");if(!id.matches("[a-f0-9]{64}"))throw new JSONException("Invalid receipt");if(time<=now&&time>=Math.max(0,now-AGE))result.put(id,time);}}
        catch(JSONException invalid){throw new IllegalStateException("Delivery ledger unavailable",invalid);}return result;
    }
}
