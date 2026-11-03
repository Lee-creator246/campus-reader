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
    public final boolean loadFailed;
    public final List<String> blockedWords,priorityWords;
    public KeywordRules(boolean blockedEnabled,List<String> blockedWords,boolean priorityEnabled,List<String> priorityWords){
        this(blockedEnabled,blockedWords,priorityEnabled,priorityWords,false);
    }
    private KeywordRules(boolean blockedEnabled,List<String> blockedWords,boolean priorityEnabled,List<String> priorityWords,boolean failed){
        this.loadFailed=failed;
        this.blockedEnabled=blockedEnabled;this.priorityEnabled=priorityEnabled;
        this.blockedWords=Collections.unmodifiableList(KeywordTerms.clean(blockedWords));
        this.priorityWords=Collections.unmodifiableList(KeywordTerms.clean(priorityWords));
    }
    public static List<String> parseWords(String text){return KeywordTerms.parse(text);}
    public static KeywordRules load(Context context,String profile){
        try{return loadStrict(context,profile);}catch(IllegalStateException corrupt){return new KeywordRules(false,Collections.emptyList(),false,Collections.emptyList(),true);}
    }
    public static KeywordRules empty(){return new KeywordRules(false,Collections.emptyList(),false,Collections.emptyList());}
    /** Editing and alerts must distinguish absent rules from corrupt storage. */
    public static KeywordRules loadStrict(Context context,String profile){
        synchronized(LOCK){
            try{String data=preferences(context).getString(key(profile),null);return data==null?empty():decode(data);}
            catch(ClassCastException invalidType){throw new IllegalStateException("Saved rules cannot be read",invalidType);}
        }
    }
    static KeywordRules decode(String text){
        try{JSONObject data=new JSONObject(text);
            if(data.length()!=4||!(data.get("blockedEnabled") instanceof Boolean)||!(data.get("priorityEnabled") instanceof Boolean))throw new JSONException("Invalid rule schema");
            return new KeywordRules(data.getBoolean("blockedEnabled"),words(data.getJSONArray("blockedWords")),data.getBoolean("priorityEnabled"),words(data.getJSONArray("priorityWords")));
        }catch(JSONException|IllegalArgumentException error){throw new IllegalStateException("Saved rules cannot be read",error);}
    }
    public boolean save(Context context,String profile){
        if(loadFailed)throw new IllegalStateException("Cannot save an unreadable rule set");
        synchronized(LOCK){SharedPreferences prefs=preferences(context);String k=key(profile),current=prefs.getString(k,null);
            if(current!=null)decode(current); // Never overwrite corrupt data with an empty fallback.
            if(encoded().equals(current))return true;
            boolean saved=prefs.edit().putString(k+":previous",current==null?empty().encoded():current).putString(k,encoded()).commit();
            if(saved)ReaderNotifications.clearNewItems(context);return saved;}
    }
    public static KeywordRules previous(Context context,String profile){synchronized(LOCK){String value=preferences(context).getString(key(profile)+":previous",null);return value==null?null:decode(value);}}
    public static boolean undo(Context context,String profile){synchronized(LOCK){KeywordRules old=previous(context,profile);if(old==null)return false;
        boolean saved=preferences(context).edit().putString(key(profile),old.encoded()).remove(key(profile)+":previous").commit();
        if(saved)ReaderNotifications.clearNewItems(context);return saved;}}
    /** Explicit recovery for unreadable storage; the UI must confirm removal of rule words. */
    public static boolean reset(Context context,String profile){synchronized(LOCK){boolean saved=preferences(context).edit().putString(key(profile),empty().encoded()).remove(key(profile)+":previous").commit();if(saved)ReaderNotifications.clearNewItems(context);return saved;}}
    public NoticeQuery.Builder apply(NoticeQuery.Builder builder,boolean priorityOnly){
        if(loadFailed)return builder.priorityKeywords(Collections.emptyList());
        builder.blockedKeywords(blockedEnabled?blockedWords:Collections.emptyList());
        if(priorityOnly)builder.priorityKeywords(priorityEnabled?priorityWords:Collections.emptyList());
        return builder;
    }
    public boolean hasBlocked(){return blockedEnabled&&!blockedWords.isEmpty();}
    public boolean hasPriority(){return priorityEnabled&&!priorityWords.isEmpty();}
    public String fingerprint(){return loadFailed?"unreadable-rules":encoded();}
    private String encoded(){try{return new JSONObject().put("blockedEnabled",blockedEnabled).put("blockedWords",new JSONArray(blockedWords)).put("priorityEnabled",priorityEnabled).put("priorityWords",new JSONArray(priorityWords)).toString();}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    private static List<String> words(JSONArray array)throws JSONException{List<String> result=new ArrayList<>();if(array!=null)for(int i=0;i<array.length();i++)result.add(array.getString(i));return result;}
    private static SharedPreferences preferences(Context context){return context.getApplicationContext().getSharedPreferences("keyword-rules",Context.MODE_PRIVATE);}
    private static String key(String profile){if(profile==null||!(profile.equals("local")||profile.matches("campus:[a-f0-9]{64}")))throw new IllegalArgumentException("Invalid rule owner");return "rules:"+profile;}
}
