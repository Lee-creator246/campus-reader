package org.campusreader.reader;
import org.campusreader.auth.ScreenPrivacy;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import java.util.*;

/** A brief optional onboarding screen; preferences and explicit words remain on this device. */
public final class InterestsActivity extends Activity {
    public static final String EXTRA_PROFILE="profile";
    private String profile;
    private final List<CheckBox> topics=new ArrayList<>();
    private EditText priority,blocked;
    private Spinner audience;
    private Switch learning,themeAlerts;
    private boolean unreadable;
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);profile=getIntent().getStringExtra(EXTRA_PROFILE);if(!NotificationHistory.validProfile(profile)||!current()){finish();return;}
        ScreenPrivacy.protect(getWindow());getWindow().setStatusBarColor(ReaderUi.SURFACE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout root=column();root.setBackgroundColor(ReaderUi.SURFACE);setContentView(root);
        LinearLayout bar=new LinearLayout(this);bar.setPadding(dp(12),dp(8),dp(12),dp(8));bar.setGravity(Gravity.CENTER_VERTICAL);root.addView(bar);
        Button back=button("返回",()->finish());bar.addView(back);TextView title=ReaderUi.text(this,"关注偏好",23,ReaderUi.INK);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout content=column();content.setPadding(dp(20),dp(8),dp(20),dp(24));scroll.addView(content);
        note(content,"选几个你关注的主题，也可以稍后设置。偏好仅保存在本机，不会作为画像发送给 AI，也不参与体验问卷回收。");
        PreferenceProfile existing=PreferenceProfile.load(this,profile);KeywordRules rules=KeywordRules.load(this,profile);unreadable=existing.loadFailed||rules.loadFailed;
        if(unreadable)note(content,"已有偏好或关键词暂时无法读取。保存前需要明确确认替换；返回不会改动原数据。");
        heading(content,"关注主题（建议 3—5 个）");
        for(String name:RecommendationEngine.TOPICS){CheckBox choice=new CheckBox(this);choice.setText(name);choice.setTextSize(16);choice.setMinHeight(dp(48));choice.setChecked(existing.topics.contains(name));content.addView(choice);topics.add(choice);}
        heading(content,"优先关注关键词");priority=editor(content,"如：志愿服务、奖学金、创新竞赛",String.join("\n",rules.priorityWords));
        note(content,"每行一个，也可用逗号分隔。匹配词语会提高相关通知的推荐和提醒优先级。");
        heading(content,"屏蔽词（选填）");blocked=editor(content,"填写你明确不想看到的词语",String.join("\n",rules.blockedWords));
        note(content,"屏蔽词匹配标题和已缓存正文，优先于关注词；不会自动扩展同义词。保存前可预览本机命中示例。");
        content.addView(button("预览关键词命中",this::preview));
        heading(content,"适用对象（选填）");audience=new Spinner(this);audience.setContentDescription("适用对象");ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,PreferenceProfile.AUDIENCES);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);audience.setAdapter(adapter);audience.setSelection(PreferenceProfile.AUDIENCES.indexOf(existing.audience));content.addView(audience,new LinearLayout.LayoutParams(-1,dp(52)));
        learning=toggle(content,"根据主动反馈优化推荐（仅本机）",existing.learningEnabled);themeAlerts=toggle(content,"关注主题优先提醒",!existing.onboardingDone||existing.themeAlerts);
        note(content,"“多推荐此类 / 不感兴趣”影响排序。已读和未读不代表喜好，学习结果不会自行升级为响铃提醒。系统通知仍受权限、免打扰与后台限制影响。");
        content.addView(button("重置推荐学习",()->new AlertDialog.Builder(this).setTitle("重置推荐学习？").setMessage("删除本机主动反馈形成的主题权重，保留关注主题、关键词、通知和阅读状态。").setPositiveButton("重置",(d,w)->{if(current())Toast.makeText(this,RecommendationEngine.resetFeedback(this,profile)?"已重置推荐学习":"重置失败，请重试",Toast.LENGTH_SHORT).show();}).setNegativeButton("取消",null).show()));
        content.addView(button("保存关注偏好",this::save));
        if(!existing.onboardingDone)content.addView(button("暂时跳过",()->{if(current()&&PreferenceProfile.skip(this,profile)){setResult(RESULT_OK);finish();}}));
    }
    private boolean current(){return PreferenceProfile.ownsCurrent(this,profile);}
    private void save(){
        if(!current()){finish();return;}
        try{KeywordRules.parseWords(blocked.getText().toString());KeywordRules.parseWords(priority.getText().toString());}catch(IllegalArgumentException invalid){Toast.makeText(this,invalid.getMessage(),Toast.LENGTH_LONG).show();return;}
        if(unreadable){new AlertDialog.Builder(this).setTitle("替换无法读取的偏好？").setMessage("将用当前填写的关注主题和词语替换本机旧配置。已有通知、标签和阅读状态会保留。").setPositiveButton("替换并保存",(d,w)->{if(!current())return;KeywordRules.reset(this,profile);unreadable=false;persist();}).setNegativeButton("返回检查",null).show();return;}persist();
    }
    private void persist(){
        try{
            List<String> block=KeywordRules.parseWords(blocked.getText().toString()),focus=KeywordRules.parseWords(priority.getText().toString());LinkedHashSet<String> selected=new LinkedHashSet<>();for(int i=0;i<topics.size();i++)if(topics.get(i).isChecked())selected.add(RecommendationEngine.TOPICS.get(i));
            synchronized(KeywordRules.LOCK){if(!current()){finish();return;}if(!new KeywordRules(!block.isEmpty(),block,!focus.isEmpty(),focus).save(this,profile)||!PreferenceProfile.create(selected,(String)audience.getSelectedItem(),learning.isChecked(),themeAlerts.isChecked()).save(this,profile))throw new IllegalStateException();}
            setResult(RESULT_OK);finish();
        }catch(RuntimeException failed){Toast.makeText(this,"未能完整保存，请检查词语数量和长度后重试",Toast.LENGTH_LONG).show();}
    }
    private void preview(){
        if(!current()){finish();return;}
        final KeywordRules draft;try{draft=new KeywordRules(true,KeywordRules.parseWords(blocked.getText().toString()),true,KeywordRules.parseWords(priority.getText().toString()));}catch(RuntimeException invalid){Toast.makeText(this,"无法预览，请检查词语格式",Toast.LENGTH_LONG).show();return;}
        Toast.makeText(this,"正在检查本机关键词命中…",Toast.LENGTH_SHORT).show();
        worker.execute(()->{try{StringBuilder text=new StringBuilder();long blockedCount,priorityCount;
            try(org.campusreader.notices.CampusNoticeStore store=new org.campusreader.notices.CampusNoticeStore(this,profile)){
                org.campusreader.notices.NoticeQuery hidden=org.campusreader.notices.NoticeQuery.builder(null).priorityKeywords(draft.blockedWords).page(5,0).build();
                org.campusreader.notices.NoticeQuery focused=draft.apply(org.campusreader.notices.NoticeQuery.builder(null).page(5,0),true).build();
                blockedCount=store.count(hidden);priorityCount=store.count(focused);
                for(org.campusreader.notices.CachedNotice row:store.query(hidden))text.append("屏蔽：").append(row.content.title).append('\n');
                for(org.campusreader.notices.CachedNotice row:store.query(focused))text.append("关注：").append(row.content.title).append('\n');
            }
            final String value="屏蔽 "+blockedCount+" 条，关注 "+priorityCount+" 条。每组最多展示 5 个最新示例，仅检查本机缓存，不改变阅读状态。\n\n"+text;
            runOnUiThread(()->{if(!isDestroyed()&&!isFinishing()&&current())new AlertDialog.Builder(this).setTitle("关键词命中预览").setMessage(value).setPositiveButton("返回设置",null).show();});
        }catch(RuntimeException invalid){runOnUiThread(()->{if(!isDestroyed()&&!isFinishing())Toast.makeText(this,"暂时无法读取缓存，请稍后再试",Toast.LENGTH_LONG).show();});}});
    }
    private LinearLayout column(){LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);return page;}
    private Button button(String title,Runnable action){Button view=new Button(this);view.setText(title);view.setAllCaps(false);view.setMinHeight(dp(48));view.setOnClickListener(v->action.run());return view;}
    private void heading(LinearLayout page,String value){TextView view=ReaderUi.text(this,value,17,ReaderUi.INK);view.setPadding(0,dp(22),0,dp(8));page.addView(view);}
    private void note(LinearLayout page,String value){TextView view=ReaderUi.text(this,value,14,ReaderUi.MUTED);view.setLineSpacing(dp(4),1);view.setPadding(0,dp(6),0,dp(10));page.addView(view);}
    private EditText editor(LinearLayout page,String hint,String value){EditText input=new EditText(this);input.setHint(hint);input.setContentDescription(hint);input.setText(value);input.setTextSize(16);input.setMinLines(2);input.setMaxLines(6);input.setGravity(Gravity.TOP);input.setSaveEnabled(false);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6000)});page.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;}
    private Switch toggle(LinearLayout page,String label,boolean checked){Switch view=new Switch(this);view.setText(label);view.setTextSize(15);view.setMinHeight(dp(60));view.setChecked(checked);page.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private int dp(int value){return ReaderUi.dp(this,value);}
    @Override protected void onResume(){super.onResume();if(profile!=null&&!current())finish();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
