package org.campusreader.reader;

import android.content.Context;
import android.content.SharedPreferences;
import org.campusreader.notices.NoticeQuery;
import java.util.*;
import org.json.*;

/** Account-local visibility and alert policy, independent of temporary search filters. */
public final class KeywordRules {
    public static final Object LOCK=new Object();
    public final boolean blockedEnabled,priorityEnabled;
    public final List<String> blockedWords,priorityWords;
    public KeywordRules(boolean blockedEnabled,List<String> blockedWords,boolean priorityEnabled,List<String> priorityWords){
        this.blockedEnabled=blockedEnabled;this.priorityEnabled=priorityEnabled;
        this.blockedWords=Collections.unmodifiableList(KeywordTerms.clean(blockedWords));
        this.priorityWords=Collections.unmodifiableList(KeywordTerms.clean(priorityWords));
    }
    public static List<String> parseWords(String text){return KeywordTerms.parse(text);}
    public static KeywordRules load(Context context,String profile){
        synchronized(LOCK){
            try{JSONObject data=new JSONObject(preferences(context).getString(key(profile),"{}"));
                return new KeywordRules(data.optBoolean("blockedEnabled"),words(data.optJSONArray("blockedWords")),data.optBoolean("priorityEnabled"),words(data.optJSONArray("priorityWords")));
            }catch(JSONException|IllegalArgumentException error){return new KeywordRules(false,Collections.emptyList(),false,Collections.emptyList());}
        }
    }
    public boolean save(Context context,String profile){
        synchronized(LOCK){boolean saved=preferences(context).edit().putString(key(profile),encoded()).commit();if(saved)ReaderNotifications.clearNewItems(context);return saved;}
    }
    public NoticeQuery.Builder apply(NoticeQuery.Builder builder,boolean priorityOnly){
        builder.blockedKeywords(blockedEnabled?blockedWords:Collections.emptyList());
        if(priorityOnly)builder.priorityKeywords(priorityEnabled?priorityWords:Collections.emptyList());
        return builder;
    }
    public boolean hasBlocked(){return blockedEnabled&&!blockedWords.isEmpty();}
    public boolean hasPriority(){return priorityEnabled&&!priorityWords.isEmpty();}
    public String fingerprint(){return encoded();}
    private String encoded(){try{return new JSONObject().put("blockedEnabled",blockedEnabled).put("blockedWords",new JSONArray(blockedWords)).put("priorityEnabled",priorityEnabled).put("priorityWords",new JSONArray(priorityWords)).toString();}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    private static List<String> words(JSONArray array)throws JSONException{List<String> result=new ArrayList<>();if(array!=null)for(int i=0;i<array.length();i++)result.add(array.getString(i));return result;}
    private static SharedPreferences preferences(Context context){return context.getApplicationContext().getSharedPreferences("keyword-rules",Context.MODE_PRIVATE);}
    private static String key(String profile){if(profile==null||!(profile.equals("local")||profile.matches("campus:[a-f0-9]{64}")))throw new IllegalArgumentException("Invalid rule owner");return "rules:"+profile;}
}
