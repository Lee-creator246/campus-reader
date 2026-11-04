package org.campusreader.reader;

import android.content.Context;
import android.content.SharedPreferences;
import org.campusreader.notices.CachedNotice;
import org.json.*;
import java.util.*;

/** Bounded, explainable content ranking. Reading status is deliberately not an interest signal. */
public final class RecommendationEngine {
    public static final List<String> TOPICS=Collections.unmodifiableList(Arrays.asList("教学教务","奖助评优","志愿实践","就业招聘","科研竞赛","行政公示","生活服务"));
    private static final String[][] TERMS={
        {"教务","教学","选课","考试","课程","学籍","培养","学分","毕业","补考"},
        {"奖学金","助学","资助","评优","评奖","荣誉","困难补助"},
        {"志愿","社会实践","实践活动","支教","公益"},
        {"招聘","就业","实习","宣讲会","双选会","求职"},
        {"科研","竞赛","比赛","项目申报","创新创业","学术","讲座","论坛","大讲堂"},
        {"公示","任职","任免","采购","招标","干部","行政","聘任"},
        {"后勤","食堂","宿舍","校园卡","停水","停电","维修","生活","体检","校车"}
    };
    private static final long DECAY_MS=30L*24*60*60*1000;
    private RecommendationEngine(){}
    public static Set<String> topics(CachedNotice notice){
        String content=text(notice);LinkedHashSet<String> result=new LinkedHashSet<>();
        for(int i=0;i<TERMS.length;i++)for(String word:TERMS[i])if(content.contains(word)){result.add(TOPICS.get(i));break;}
        return Collections.unmodifiableSet(result);
    }
    public static boolean matchesTopic(CachedNotice notice,String topic){return topics(notice).contains(topic);}
    public static int score(Context context,String profile,CachedNotice notice){return decision(notice,PreferenceProfile.load(context,profile),KeywordRules.load(context,profile),feedbackValues(context,profile),System.currentTimeMillis()).score;}
    public static String reason(Context context,String profile,CachedNotice notice){return decision(notice,PreferenceProfile.load(context,profile),KeywordRules.load(context,profile),feedbackValues(context,profile),System.currentTimeMillis()).reason;}
    public static List<CachedNotice> sort(Context context,String profile,List<CachedNotice> input){
        PreferenceProfile prefs=PreferenceProfile.load(context,profile);KeywordRules rules=KeywordRules.load(context,profile);Map<String,Integer> feedback=feedbackValues(context,profile);long now=System.currentTimeMillis();
        List<CachedNotice> result=new ArrayList<>();Map<CachedNotice,Integer> scores=new IdentityHashMap<>();
        for(CachedNotice row:input){Decision decision=decision(row,prefs,rules,feedback,now);if(decision.score==Integer.MIN_VALUE)continue;result.add(row);scores.put(row,decision.score);}
        result.sort((a,b)->{int byScore=Integer.compare(scores.get(b),scores.get(a));return byScore!=0?byScore:Long.compare(b.content.publishedAtMillis,a.content.publishedAtMillis);});return result;
    }
    /** Only explicit interests may promote an alert. Learned feedback never enables a high-priority channel. */
    public static String priorityReason(Context context,String profile,CachedNotice notice,KeywordRules rules){
        if(rules.loadFailed||blocked(notice,rules))return "";
        String word=match(text(notice),rules.hasPriority()?rules.priorityWords:Collections.emptyList());if(!word.isEmpty())return word;
        PreferenceProfile prefs=PreferenceProfile.load(context,profile);if(prefs.loadFailed||!prefs.themeAlerts)return "";
        for(String topic:topics(notice))if(prefs.topics.contains(topic))return "主题："+topic;return "";
    }
    static final class Decision {final int score;final String reason;Decision(int score,String reason){this.score=score;this.reason=reason;}}
    static Decision decision(CachedNotice row,PreferenceProfile prefs,KeywordRules rules,Map<String,Integer> feedback,long now){
        if(rules.loadFailed)return new Decision(Integer.MIN_VALUE,"规则暂时无法读取");
        if(blocked(row,rules))return new Decision(Integer.MIN_VALUE,"命中屏蔽规则");
        String title=normalize(row.content.title),text=text(row);String word=match(title,rules.hasPriority()?rules.priorityWords:Collections.emptyList());
        int score=0;String reason="新近通知，供你发现";
        if(!word.isEmpty()){score+=70;reason="标题匹配关注词："+word;}
        else {word=match(text,rules.hasPriority()?rules.priorityWords:Collections.emptyList());if(!word.isEmpty()){score+=35;reason="匹配关注词："+word;}}
        Set<String> topics=topics(row);int matching=0,learned=0;
        for(String topic:topics){if(prefs.topics.contains(topic)){matching++;if(score==0)reason="你关注的主题："+topic;}if(prefs.learningEnabled)learned+=feedback.getOrDefault(topic,0);}
        score+=Math.min(40,matching*20);score+=Math.max(-12,Math.min(12,learned*3));
        if(score==learned*3&&learned>0)reason="根据你对同类通知的反馈";
        if(!prefs.audience.equals("未选择")&&audienceMatches(text,prefs.audience)){score+=5;if(score==5)reason="提及你选择的适用对象";}
        long age=now-row.content.publishedAtMillis;if(age>=0&&age<=24*60*60*1000L)score+=6;else if(age>=0&&age<=3*24*60*60*1000L)score+=3;
        return new Decision(score,reason);
    }
    private static boolean audienceMatches(String text,String audience){if(audience.equals("研究生"))return text.contains("研究生")||text.contains("硕士")||text.contains("博士");if(audience.equals("教职工"))return text.contains("教职工")||text.contains("教师")||text.contains("教工");return text.contains("本科生")||text.contains("本科");}
    public static boolean blocked(CachedNotice notice,KeywordRules rules){return rules.hasBlocked()&&!match(text(notice),rules.blockedWords).isEmpty();}
    private static String normalize(String value){StringBuilder folded=new StringBuilder(value);for(int i=0;i<folded.length();i++){char c=folded.charAt(i);if(c>='A'&&c<='Z')folded.setCharAt(i,(char)(c+32));}return folded.toString();}
    @SuppressWarnings("deprecation") private static String text(CachedNotice notice){
        // Use the store's plain-text conversion so literal block matching has the same boundaries.
        String plain="";if(notice.bodyCached){String html=notice.content.bodyHtml.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>","");plain=android.text.Html.fromHtml(html).toString().replace('\u00a0',' ').trim();}
        return normalize(notice.content.title+"\n"+plain);
    }
    private static String match(String text,List<String> words){for(String word:words)if(text.contains(normalize(word)))return word;return "";}
    private static SharedPreferences store(Context context){return context.getApplicationContext().getSharedPreferences("recommendation-feedback",Context.MODE_PRIVATE);}
    private static String key(String profile){return PreferenceProfile.key(profile);}
    /** Explicit feedback changes only small topic weights. No title, body, reading trail or identity is stored. */
    public static boolean feedback(Context context,String profile,CachedNotice notice,boolean more){
        synchronized(KeywordRules.LOCK){
            PreferenceProfile prefs=PreferenceProfile.load(context,profile);if(!prefs.learningEnabled||prefs.loadFailed)return false;
            Set<String> topics=topics(notice);if(topics.isEmpty())return false;
            Map<String,Integer> weights=feedbackValues(context,profile);long now=System.currentTimeMillis();
            try {JSONObject data=new JSONObject().put("version",1).put("time",now);JSONObject values=new JSONObject();for(String topic:TOPICS){int value=weights.getOrDefault(topic,0);if(topics.contains(topic))value=Math.max(-4,Math.min(4,value+(more?1:-1)));values.put(topic,value);}data.put("values",values);return store(context).edit().putString(key(profile),data.toString()).commit();}
            catch(JSONException impossible){throw new IllegalStateException(impossible);}
        }
    }
    public static boolean resetFeedback(Context context,String profile){synchronized(KeywordRules.LOCK){return store(context).edit().remove(key(profile)).commit();}}
    private static Map<String,Integer> feedbackValues(Context context,String profile){
        synchronized(KeywordRules.LOCK){Map<String,Integer> result=new HashMap<>();try{String raw=store(context).getString(key(profile),null);if(raw==null||raw.length()>4096)return result;JSONObject data=new JSONObject(raw);long elapsed=System.currentTimeMillis()-data.getLong("time");if(elapsed<0||elapsed>=DECAY_MS)return result;JSONObject values=data.getJSONObject("values");for(String topic:TOPICS){int value=values.optInt(topic,0);result.put(topic,(int)Math.round(Math.max(-4,Math.min(4,value))*(1d-elapsed/(double)DECAY_MS)));}}catch(RuntimeException|JSONException ignored){}return result;}
    }
}
