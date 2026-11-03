package org.campusreader.reader;

import android.content.Context;
import android.content.SharedPreferences;
import org.campusreader.notices.NoticeKey;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Small local decision journal. Never retains titles, bodies, URLs, accounts, or exception messages. */
public final class NotificationHistory {
    public static final int MAX_ENTRIES=200;
    public static final long MAX_AGE_MS=14L*24*60*60*1000;
    private static final Object LOCK=new Object();
    public enum Reason {
        REFRESH, NO_NEW_ITEMS, FOREGROUND_ONLY, ACCOUNT_CHANGED, MISSING, ALREADY_READ, BLOCKED,
        APP_DISABLED, PERMISSION_DENIED, SYSTEM_DISABLED, CHANNEL_DISABLED, RULES_ERROR,
        NORMAL_SUBMITTED, PRIORITY_SUBMITTED, POST_FAILED, SESSION_SUBMITTED, SESSION_ALREADY_REMINDED,
        BACKGROUND_DISABLED, WIFI_REQUIRED, JOB_STOPPED, INTERNAL_ERROR
    }
    public static final class Entry {
        public final long timeMillis;
        public final Reason reason;
        public final String noticeHash,ruleHash,matchedWord,status,channel;
        public final int saved,added,failed;
        public final boolean background;
        Entry(long timeMillis,Reason reason,String noticeHash,String ruleHash,String matchedWord,String status,String channel,int saved,int added,int failed,boolean background){
            this.timeMillis=timeMillis;this.reason=reason;this.noticeHash=noticeHash;this.ruleHash=ruleHash;this.matchedWord=matchedWord;
            this.status=status;this.channel=channel;this.saved=saved;this.added=added;this.failed=failed;this.background=background;
        }
    }
    private NotificationHistory(){}
    static boolean validProfile(String profile){return profile!=null&&(profile.equals("local")||profile.matches("campus:[a-f0-9]{64}"));}
    static String digest(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public static void record(Context context,String profile,Reason reason,NoticeKey key,KeywordRules rules,String word,String channel){
        if(!validProfile(profile))return;
        Entry entry=new Entry(System.currentTimeMillis(),reason,key==null?"":digest(key.source+'\0'+key.stableId),rules==null?"":digest(rules.fingerprint()),
                word==null?"":word,"",channel==null?"":channel,0,0,0,false);
        appendSafely(context,profile,entry);
    }
    public static void refresh(Context context,CampusSyncEngine.Result result,boolean background){
        if(!validProfile(result.profile))return;
        appendSafely(context,result.profile,new Entry(System.currentTimeMillis(),Reason.REFRESH,"","","",result.status.name(),"",result.saved,result.newCount,result.failed,background));
        if(result.insertedKeys.isEmpty()){
            if(result.status==CampusSyncEngine.Status.SUCCESS||result.status==CampusSyncEngine.Status.PARTIAL)
                record(context,result.profile,Reason.NO_NEW_ITEMS,null,null,null,null);
        }else if(!background)for(NoticeKey key:new LinkedHashSet<>(result.insertedKeys))record(context,result.profile,Reason.FOREGROUND_ONLY,key,null,null,null);
    }
    private static void appendSafely(Context context,String profile,Entry entry){
        try{append(context,profile,entry,System.currentTimeMillis());}catch(RuntimeException failed){android.util.Log.w("CampusNotify","Decision journal unavailable");}
    }
    static void append(Context context,String profile,Entry entry,long now){
        requireProfile(profile);validate(entry);
        synchronized(LOCK){List<Entry> entries;
            try{entries=decode(prefs(context).getString(profile,"[]"),now);}catch(IllegalStateException corrupt){entries=new ArrayList<>();}
            entries.add(0,entry);entries=retain(entries,now);
            if(!prefs(context).edit().putString(profile,encode(entries)).commit())throw new IllegalStateException("Journal write failed");
        }
    }
    public static List<Entry> read(Context context,String profile){
        requireProfile(profile);synchronized(LOCK){List<Entry> entries=decode(prefs(context).getString(profile,"[]"),System.currentTimeMillis());
            // Also prune persistent data when a quiet account has no new refreshes.
            prefs(context).edit().putString(profile,encode(entries)).apply();return Collections.unmodifiableList(entries);}
    }
    public static boolean clear(Context context,String profile){requireProfile(profile);synchronized(LOCK){return prefs(context).edit().remove(profile).commit();}}
    private static void requireProfile(String profile){if(!validProfile(profile))throw new IllegalArgumentException("Invalid journal owner");}
    private static SharedPreferences prefs(Context context){return context.getApplicationContext().getSharedPreferences("notification-history",Context.MODE_PRIVATE);}
    private static List<Entry> retain(List<Entry> entries,long now){List<Entry> result=new ArrayList<>();for(Entry entry:entries){if(entry.timeMillis<=now&&entry.timeMillis>=Math.max(0,now-MAX_AGE_MS))result.add(entry);if(result.size()==MAX_ENTRIES)break;}return result;}
    private static void validate(Entry e){
        if(e.timeMillis<0||e.reason==null||!(e.noticeHash.isEmpty()||e.noticeHash.matches("[a-f0-9]{64}"))||!(e.ruleHash.isEmpty()||e.ruleHash.matches("[a-f0-9]{64}")))throw new IllegalArgumentException("Invalid journal record");
        if(!e.status.isEmpty())CampusSyncEngine.Status.valueOf(e.status);
        if(!Arrays.asList("","normal","priority","session").contains(e.channel)||e.saved<0||e.added<0||e.failed<0)throw new IllegalArgumentException("Invalid journal metadata");
        if(e.matchedWord.codePointCount(0,e.matchedWord.length())>100)throw new IllegalArgumentException("Word too long");
        for(int i=0;i<e.matchedWord.length();i++)if(Character.isISOControl(e.matchedWord.charAt(i)))throw new IllegalArgumentException("Invalid word");
    }
    private static String encode(List<Entry> entries){try{JSONArray data=new JSONArray();for(Entry e:entries)data.put(new JSONObject().put("time",e.timeMillis).put("reason",e.reason.name()).put("notice",e.noticeHash).put("rule",e.ruleHash).put("word",e.matchedWord).put("status",e.status).put("channel",e.channel).put("saved",e.saved).put("added",e.added).put("failed",e.failed).put("background",e.background));return data.toString();}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    private static List<Entry> decode(String json,long now){try{
        if(json==null||json.length()>256000)throw new JSONException("Journal too large");JSONArray data=new JSONArray(json);if(data.length()>MAX_ENTRIES)throw new JSONException("Too many journal entries");
        List<Entry> entries=new ArrayList<>();for(int i=0;i<data.length();i++){JSONObject o=data.getJSONObject(i);Entry e=new Entry(o.getLong("time"),Reason.valueOf(o.getString("reason")),o.getString("notice"),o.getString("rule"),o.getString("word"),o.getString("status"),o.getString("channel"),o.getInt("saved"),o.getInt("added"),o.getInt("failed"),o.getBoolean("background"));validate(e);entries.add(e);}return retain(entries,now);
    }catch(JSONException|IllegalArgumentException invalid){throw new IllegalStateException("Journal cannot be read",invalid);}}
    public static String reasonText(Reason reason){switch(reason){
        case REFRESH:return "刷新检查";case NO_NEW_ITEMS:return "没有新的通知身份，未重复提醒";case FOREGROUND_ONLY:return "前台新增已显示在列表，不另发后台提醒";
        case ACCOUNT_CHANGED:return "账号已变化，本次没有提醒";case MISSING:return "记录已不存在";case ALREADY_READ:return "已读，未提醒";case BLOCKED:return "命中屏蔽规则，未提醒";
        case APP_DISABLED:return "应用内提醒已关闭";case PERMISSION_DENIED:return "未授予通知权限";case SYSTEM_DISABLED:return "系统已关闭应用通知";case CHANNEL_DISABLED:return "该通知渠道已关闭";
        case RULES_ERROR:return "规则或缓存无法读取，已停止提醒";case NORMAL_SUBMITTED:return "普通提醒已提交系统";case PRIORITY_SUBMITTED:return "优先提醒已提交系统";
        case POST_FAILED:return "提交系统失败";case SESSION_SUBMITTED:return "连接提醒已提交系统";case SESSION_ALREADY_REMINDED:return "连接提醒已合并，不重复发送";
        case BACKGROUND_DISABLED:return "后台刷新已关闭";case WIFI_REQUIRED:return "等待 Wi-Fi，未请求网络";case JOB_STOPPED:return "系统停止本次后台任务";default:return "本次后台检查未完成";
    }}
    public static String statusText(String status){try{switch(CampusSyncEngine.Status.valueOf(status)){
        case SUCCESS:return "成功";case PARTIAL:return "部分完成";case BUSY:return "已有刷新任务";case NO_PROFILE:return "尚未选择账号";case TUNNEL_REQUIRED:return "校园通道未就绪";
        case PORTAL_LOGIN_REQUIRED:return "门户需要登录";case ACCOUNT_CHANGED:return "账号已变化";case CANCELLED:return "已取消或超时";case NETWORK_ERROR:return "网络或本地处理失败";default:return "门户页面暂不兼容";
    }}catch(IllegalArgumentException invalid){return "";}}
}
