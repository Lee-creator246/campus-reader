package org.campusreader.reader;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.campusreader.notices.*;
import java.util.*;
import java.util.concurrent.*;

/** A snapshot of draft matches. No writes, network, automatic reading, or notifications. */
public final class RulePreviewActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String profile; private KeywordRules rules; private LinearLayout page; private TextView status;
    private boolean blockedOnly;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);profile=getIntent().getStringExtra("profile");
        if(!ownsProfile()){finish();return;}
        try{rules=KeywordRules.decode(getIntent().getStringExtra("rules"));}catch(RuntimeException invalid){finish();return;}
        blockedOnly=getIntent().getBooleanExtra("blockedOnly",false);
        getWindow().setStatusBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(R.layout.reader_activity_settings_list);
        ((TextView)findViewById(R.id.reader_text_action_bar_title)).setText(blockedOnly?"已屏蔽内容":"规则预览");
        TextView back=findViewById(R.id.reader_text_action_bar_left);back.setText("返回");back.setVisibility(View.VISIBLE);back.setOnClickListener(v->finish());
        findViewById(R.id.reader_text_action_bar_done).setVisibility(View.GONE);
        page=findViewById(R.id.reader_ll_container);page.setPadding(dp(20),dp(12),dp(20),dp(24));
        status=label("正在检查本地内容…",14);page.addView(status);
        worker.execute(this::load);
    }
    private boolean ownsProfile(){if(profile==null)return false;String current=CampusSyncEngine.currentProfile();if(current.isEmpty())current=getSharedPreferences("reader-local",MODE_PRIVATE).getString(ReaderPreferences.lastProfileKey(),"local");return profile.equals(current);}
    @Override public void onResume(){super.onResume();if(!ownsProfile())finish();}
    private void load(){
        try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){
            NoticeQuery blocked=NoticeQuery.builder(null).priorityKeywords(rules.blockedEnabled?rules.blockedWords:Collections.emptyList()).page(30,0).build();
            NoticeQuery priority=rules.apply(NoticeQuery.builder(null).page(30,0),true).build();
            long total=store.count(NoticeQuery.builder(null).build()),hidden=store.count(blocked),focused=store.count(priority);
            List<CachedNotice> hiddenRows=store.query(blocked),priorityRows=blockedOnly?Collections.emptyList():store.query(priority);
            Map<NoticeKey,String> reasons=new HashMap<>();
            for(CachedNotice row:hiddenRows)reasons.put(row.content.key,reason(store,row,rules.blockedWords));
            for(CachedNotice row:priorityRows)reasons.put(row.content.key,reason(store,row,rules.priorityWords));
            runOnUiThread(()->{if(isDestroyed()||!ownsProfile())return;
                status.setText("已检查 "+total+" 条本地记录\n隐藏 "+hidden+" 条 · 重点关注 "+focused+" 条\n屏蔽优先；每组展示最新 30 条。预览不修改任何记录。");
                group("屏蔽命中",hiddenRows,reasons);
                if(!blockedOnly)group("优先关注（已排除屏蔽项）",priorityRows,reasons);
            });
        }catch(RuntimeException failure){runOnUiThread(()->{if(!isDestroyed())status.setText("暂时无法读取预览，现有规则和阅读状态未改变。请返回重试。");});}
    }
    private String reason(CampusNoticeStore store,CachedNotice row,List<String> words){
        List<String> hits=new ArrayList<>();
        for(String word:words)if(store.matches(row.content.key,NoticeQuery.builder(null).search(word).build()))hits.add(word+(asciiFold(row.content.title).contains(asciiFold(word))?"（标题）":"（正文）"));
        return "命中："+android.text.TextUtils.join("、",hits)+(row.bodyCached?" · 标题及缓存正文":" · 仅检查标题（正文未缓存）");
    }
    private static String asciiFold(String value){StringBuilder out=new StringBuilder(value);for(int i=0;i<out.length();i++){char c=out.charAt(i);if(c>='A'&&c<='Z')out.setCharAt(i,(char)(c+32));}return out.toString();}
    private void group(String title,List<CachedNotice> rows,Map<NoticeKey,String> reasons){
        TextView heading=label(title,17);heading.setPadding(0,dp(24),0,dp(8));page.addView(heading);
        if(rows.isEmpty()){page.addView(label("没有命中内容",14));return;}
        for(CachedNotice row:rows){LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setPadding(0,dp(14),0,dp(14));cell.setMinimumHeight(dp(64));
            cell.addView(label(row.content.title,16));TextView reason=label(reasons.get(row.content.key),12);reason.setTextColor(ReaderUi.MUTED);cell.addView(reason);
            cell.setOnClickListener(v->{String body=row.bodyCached?org.jsoup.Jsoup.parse(row.content.bodyHtml).text():"正文已清理。可返回列表联网获取。";if(body.length()>8000)body=body.substring(0,8000)+"\n（预览到此，完整内容请打开原文）";
                new AlertDialog.Builder(this).setTitle(row.content.title).setMessage(reasons.get(row.content.key)+"\n\n"+body+"\n\n本预览不会标为已读。").setNegativeButton("返回",null).show();});
            cell.setFocusable(true);cell.setContentDescription(row.content.title+"，"+reasons.get(row.content.key));page.addView(cell);
            View line=new View(this);line.setBackgroundColor(ReaderUi.LINE);page.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        }
    }
    private TextView label(String text,int size){TextView view=ReaderUi.text(this,text,size,ReaderUi.INK);view.setLineSpacing(dp(4),1);return view;}
    private int dp(int value){return ReaderUi.dp(this,value);}
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
