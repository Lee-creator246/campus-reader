package org.campusreader.reader;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Local notification decisions and recovery links; does not mark any notice as read. */
public final class NotificationHistoryActivity extends Activity {
    public static final String EXTRA_PROFILE="profile";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String profile;private LinearLayout page;private TextView status;private int generation;
    private List<NotificationHistory.Entry> entries=Collections.emptyList();private int shown;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);profile=getIntent().getStringExtra(EXTRA_PROFILE);
        if(!ownsProfile()){finish();return;}
        getWindow().setStatusBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(R.layout.reader_activity_settings_list);
        ((TextView)findViewById(R.id.reader_text_action_bar_title)).setText("刷新与提醒记录");
        TextView back=findViewById(R.id.reader_text_action_bar_left);back.setText("返回");back.setVisibility(View.VISIBLE);back.setOnClickListener(v->finish());
        TextView clear=findViewById(R.id.reader_text_action_bar_done);clear.setText("清空");clear.setOnClickListener(v->confirmClear());
        page=findViewById(R.id.reader_ll_container);page.setPadding(dp(20),dp(12),dp(20),dp(24));load();
    }
    private boolean ownsProfile(){if(!NotificationHistory.validProfile(profile))return false;String current=CampusSyncEngine.currentProfile();if(current.isEmpty())current=getSharedPreferences("reader-local",MODE_PRIVATE).getString(ReaderPreferences.lastProfileKey(),"local");return profile.equals(current);}
    @Override public void onResume(){super.onResume();if(!ownsProfile())finish();}
    private void load(){int request=++generation;page.removeAllViews();status=ReaderUi.text(this,"正在读取本地记录…",14,ReaderUi.MUTED);page.addView(status);
        worker.execute(()->{try{List<NotificationHistory.Entry> result=NotificationHistory.read(this,profile);runOnUiThread(()->{if(isDestroyed()||request!=generation||!ownsProfile())return;entries=result;shown=0;render();});}
            catch(RuntimeException failure){runOnUiThread(()->{if(!isDestroyed()&&request==generation)status.setText("暂时无法读取记录。可清空这份诊断记录后重新积累；通知和阅读状态不受影响。");});}});
    }
    private void render(){page.removeAllViews();
        TextView note=ReaderUi.text(this,"仅保留当前账号最近 14 天、最多 200 条记录。\n“已提交系统”不代表你已经看到提醒。关闭后台刷新、系统限制或未运行时，不会产生新的检查结果。",13,ReaderUi.MUTED);note.setLineSpacing(dp(4),1);page.addView(note);
        LinearLayout actions=new LinearLayout(this);actions.addView(button("通知设置",()->{if(!SettingsSupport.openNotifications(this))Toast.makeText(this,"暂时无法打开通知设置",Toast.LENGTH_SHORT).show();}),new LinearLayout.LayoutParams(0,dp(48),1));
        actions.addView(button("关键词规则",()->startActivity(new Intent(this,KeywordRulesActivity.class).putExtra("profile",profile))),new LinearLayout.LayoutParams(0,dp(48),1));page.addView(actions);
        if(entries.isEmpty()){TextView empty=ReaderUi.text(this,"还没有记录。完成一次刷新后，可在这里查看结果与提醒原因。",15,ReaderUi.INK);empty.setPadding(0,dp(24),0,0);page.addView(empty);return;}
        shown=Math.min(entries.size(),Math.max(50,shown));for(int i=0;i<shown;i++)addEntry(entries.get(i));
        if(shown<entries.size())page.addView(button("加载较早记录（"+(entries.size()-shown)+"）",()->{shown=Math.min(entries.size(),shown+50);render();}),new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void addEntry(NotificationHistory.Entry entry){
        LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setPadding(0,dp(14),0,dp(14));cell.setMinimumHeight(dp(64));
        TextView title=ReaderUi.text(this,NotificationHistory.reasonText(entry.reason),15,ReaderUi.INK);cell.addView(title);
        String detail=android.text.format.DateFormat.format("MM-dd HH:mm:ss",entry.timeMillis).toString();
        if(entry.reason==NotificationHistory.Reason.REFRESH)detail+=(entry.background?" · 后台":" · 前台")+" · "+NotificationHistory.statusText(entry.status)+"\n保存 "+entry.saved+" · 新增 "+entry.added+" · 未完成 "+entry.failed;
        if(!entry.matchedWord.isEmpty())detail+="\n命中词："+entry.matchedWord;
        if(!entry.channel.isEmpty())detail+=" · "+(entry.channel.equals("priority")?"优先渠道":entry.channel.equals("session")?"连接渠道":"普通渠道");
        if(!entry.noticeHash.isEmpty())detail+="\n通知指纹 "+entry.noticeHash.substring(0,12);
        if(!entry.ruleHash.isEmpty())detail+=" · 规则版本 "+entry.ruleHash.substring(0,12);
        TextView text=ReaderUi.text(this,detail,12,ReaderUi.MUTED);text.setLineSpacing(dp(3),1);cell.addView(text);page.addView(cell);
        View line=new View(this);line.setBackgroundColor(ReaderUi.LINE);page.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void confirmClear(){new AlertDialog.Builder(this).setTitle("清空当前账号的诊断记录？").setMessage("只清空这里的记录，通知缓存、已读、稍后读和标签都会保留。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{
        if(!ownsProfile()){finish();return;}int request=++generation;worker.execute(()->{boolean cleared=NotificationHistory.clear(this,profile);runOnUiThread(()->{if(isDestroyed()||request!=generation||!ownsProfile())return;if(cleared)load();else Toast.makeText(this,"清空失败，请重试",Toast.LENGTH_SHORT).show();});});}).show();}
    private Button button(String text,Runnable action){Button button=new Button(this);button.setText(text);button.setAllCaps(false);button.setTextColor(ReaderUi.BLUE);button.setOnClickListener(v->{if(ownsProfile())action.run();else finish();});return button;}
    private int dp(int value){return ReaderUi.dp(this,value);}
    @Override public void onDestroy(){generation++;worker.shutdownNow();super.onDestroy();}
}
