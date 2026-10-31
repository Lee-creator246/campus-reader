package org.campusreader.reader;
import android.content.SharedPreferences;
import org.campusreader.notices.NoticeQuery;
import java.util.*;
import org.json.*;

/** Account-local query choices; filtering never deletes notices. */
final class ReaderFilterState {
    String search="",include="",exclude="";boolean allTags=false;final List<Long> tags=new ArrayList<>();
    static ReaderFilterState load(SharedPreferences prefs,String profile){ReaderFilterState s=new ReaderFilterState();try{JSONObject o=new JSONObject(prefs.getString("filters:"+profile,"{}"));s.search=o.optString("search");s.include=o.optString("include");s.exclude=o.optString("exclude");s.allTags=o.optBoolean("allTags");JSONArray a=o.optJSONArray("tags");if(a!=null)for(int i=0;i<a.length();i++)s.tags.add(a.getLong(i));}catch(JSONException ignored){}return s;}
    void save(SharedPreferences prefs,String profile){try{JSONObject o=new JSONObject();o.put("search",search);o.put("include",include);o.put("exclude",exclude);o.put("allTags",allTags);JSONArray a=new JSONArray();for(Long id:tags)a.put(id);o.put("tags",a);prefs.edit().putString("filters:"+profile,o.toString()).apply();}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    NoticeQuery query(String source,int filter,int limit){return NoticeQuery.builder(source).search(search).includeKeywords(terms(include)).excludeKeywords(terms(exclude)).tagIds(tags,allTags).unreadOnly(filter==1).laterOnly(filter==2).page(limit,0).build();}
    NoticeQuery query(String source,int filter,int limit,KeywordRules rules,boolean priorityOnly){return rules.apply(NoticeQuery.builder(source).search(search).includeKeywords(terms(include)).excludeKeywords(terms(exclude)).tagIds(tags,allTags).unreadOnly(filter==1).laterOnly(filter==2).page(limit,0),priorityOnly).build();}
    boolean active(){return !search.isEmpty()||!include.isEmpty()||!exclude.isEmpty()||!tags.isEmpty();}
    static List<String> terms(String value){return Arrays.asList(value.split("[,，\\n]+"));}
}
