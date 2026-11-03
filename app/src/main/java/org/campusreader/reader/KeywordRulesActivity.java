package org.campusreader.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** Per-profile keyword controls. The caller owns list refresh and notification cleanup. */
public final class KeywordRulesActivity extends Activity {
    public static final String EXTRA_PROFILE="profile";
    private String profile;
    private TextView saveButton,error;
    private Button retry,reset;
    private Switch blockedToggle,priorityToggle;
    private EditText blockedInput,priorityInput;
    private boolean loaded,saving;
    private boolean originalBlocked,originalPriority;
    private String originalBlockedText="",originalPriorityText="";

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        profile=getIntent().getStringExtra(EXTRA_PROFILE);
        if(!ownsProfile()){finish();return;}
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setContentView(R.layout.reader_activity_settings_list);
        getWindow().getDecorView().setBackgroundColor(Color.WHITE);
        TextView title=findViewById(R.id.reader_text_action_bar_title);
        title.setText("关键词规则");title.setTextColor(ReaderUi.INK);
        TextView back=findViewById(R.id.reader_text_action_bar_left);
        back.setVisibility(View.VISIBLE);back.setText("返回");back.setTextColor(ReaderUi.BLUE);back.setMinWidth(dp(48));back.setOnClickListener(v->leave());
        saveButton=findViewById(R.id.reader_text_action_bar_done);
        saveButton.setText("保存");saveButton.setTextColor(ReaderUi.BLUE);saveButton.setMinWidth(dp(48));saveButton.setOnClickListener(v->save());

        LinearLayout page=findViewById(R.id.reader_ll_container);
        page.setPadding(dp(20),dp(8),dp(20),dp(24));
        note(page,"分别设置不想看到的内容和需要优先关注的内容，规则仅用于当前账号。",14);
        error=ReaderUi.text(this,"",13,0xffb3261e);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);error.setVisibility(View.GONE);page.addView(error);
        retry=action("重新读取规则",()->load(null));retry.setVisibility(View.GONE);page.addView(retry,new LinearLayout.LayoutParams(-1,dp(48)));
        reset=action("重置无法读取的规则",()->new AlertDialog.Builder(this).setTitle("重置关键词规则？").setMessage("当前账号的屏蔽词、优先提醒词和上一版规则将移除。通知缓存、已读、稍后读和标签保留。也可先尝试下方的撤销上一次保存。")
                .setNegativeButton("取消",null).setPositiveButton("重置规则",(d,w)->{if(!ownsProfile()){finish();return;}if(KeywordRules.reset(this,profile)){load(null);setResult(RESULT_OK);}else Toast.makeText(this,"重置失败，请重试",Toast.LENGTH_LONG).show();}).show());
        reset.setVisibility(View.GONE);page.addView(reset,new LinearLayout.LayoutParams(-1,dp(48)));

        blockedToggle=toggle(page,"启用屏蔽词");
        note(page,"命中任一屏蔽词的通知，将从列表和新通知提醒中隐藏。",13);
        blockedInput=editor(page,"屏蔽词","例如：任职\n干部任免");
        note(page,"每行一词，也可用中英文逗号分隔。最多 50 个词，每词最多 100 字。",12);
        divider(page);

        priorityToggle=toggle(page,"启用关键词优先提醒");
        note(page,"新抓取的通知命中任一关键词时，优先提醒。已有通知不会因为修改规则而重新提醒。",13);
        priorityInput=editor(page,"优先提醒关键词","例如：志愿活动\n志愿者招募");
        note(page,"每行一词，也可用中英文逗号分隔。最多 50 个词，每词最多 100 字。",12);
        divider(page);

        TextView explanation=ReaderUi.text(this,"规则如何生效",15,ReaderUi.INK);explanation.setTypeface(Typeface.DEFAULT,Typeface.BOLD);explanation.setPadding(0,dp(8),0,dp(8));page.addView(explanation);
        note(page,"• 按字词匹配标题和仍缓存的正文；正文已清理时，只能匹配标题。图片内文字不参与匹配。\n• 同时命中两类词时，屏蔽规则优先。\n• 屏蔽不删除缓存，也不改变已读、稍后读或标签。关闭屏蔽后可重新看到内容。\n• 后台按刷新设置检查新通知，不是实时推送。提醒声音及弹出方式由系统通知渠道控制。",13);
        Button notifications=action("通知权限与渠道",()->{if(!SettingsSupport.openNotifications(this))Toast.makeText(this,"暂时无法打开系统通知设置",Toast.LENGTH_SHORT).show();});
        page.addView(notifications,new LinearLayout.LayoutParams(-1,dp(48)));
        divider(page);
        page.addView(action("预览当前草稿的效果",()->preview(false)),new LinearLayout.LayoutParams(-1,dp(48)));
        page.addView(action("查看已被屏蔽的内容",()->preview(true)),new LinearLayout.LayoutParams(-1,dp(48)));
        page.addView(action("撤销上一次保存",this::undo),new LinearLayout.LayoutParams(-1,dp(48)));
        note(page,"预览只检查本机已有内容，不保存规则、不改变阅读状态。撤销恢复上一版已保存规则，不重新提醒历史通知。",12);
        blockedToggle.setOnCheckedChangeListener((button,value)->updateEnabled());
        priorityToggle.setOnCheckedChangeListener((button,value)->updateEnabled());
        load(state);
    }

    private boolean ownsProfile(){
        if(profile==null||profile.trim().isEmpty())return false;
        String current=CampusSyncEngine.currentProfile();
        if(current==null||current.isEmpty())current=getSharedPreferences("reader-local",MODE_PRIVATE).getString(ReaderPreferences.lastProfileKey(),"local");
        return profile.equals(current);
    }
    @Override protected void onResume(){
        super.onResume();
        if(!isFinishing()&&!ownsProfile()){
            Toast.makeText(this,"账号已变化，请从当前账号重新打开关键词规则",Toast.LENGTH_LONG).show();
            finish();
        }
    }
    private void load(Bundle state){
        if(!ownsProfile()){finish();return;}
        loaded=false;updateEnabled();
        try{
            KeywordRules rules=KeywordRules.loadStrict(this,profile);
            originalBlocked=rules.blockedEnabled;originalPriority=rules.priorityEnabled;
            originalBlockedText=TextUtils.join("\n",rules.blockedWords);originalPriorityText=TextUtils.join("\n",rules.priorityWords);
            blockedToggle.setChecked(originalBlocked);priorityToggle.setChecked(originalPriority);
            blockedInput.setText(originalBlockedText);priorityInput.setText(originalPriorityText);
            if(state!=null&&profile.equals(state.getString("rules_profile"))){
                blockedToggle.setChecked(state.getBoolean("blocked_enabled",originalBlocked));
                priorityToggle.setChecked(state.getBoolean("priority_enabled",originalPriority));
                blockedInput.setText(state.getString("blocked_text",originalBlockedText));
                priorityInput.setText(state.getString("priority_text",originalPriorityText));
            }
            loaded=true;error.setVisibility(View.GONE);retry.setVisibility(View.GONE);reset.setVisibility(View.GONE);
        }catch(RuntimeException failure){
            error.setText("未能读取已保存的规则。请重试，当前内容不会覆盖已有规则。");error.setVisibility(View.VISIBLE);retry.setVisibility(View.VISIBLE);reset.setVisibility(View.VISIBLE);
        }
        updateEnabled();
    }
    private void updateEnabled(){
        boolean editable=loaded&&!saving;
        if(saveButton!=null){saveButton.setEnabled(editable);saveButton.setAlpha(editable?1f:.4f);saveButton.setText(saving?"保存中":"保存");}
        if(blockedToggle!=null)blockedToggle.setEnabled(editable);
        if(priorityToggle!=null)priorityToggle.setEnabled(editable);
        if(blockedInput!=null){blockedInput.setEnabled(editable&&blockedToggle.isChecked());blockedInput.setAlpha(blockedToggle.isChecked()?1f:.6f);}
        if(priorityInput!=null){priorityInput.setEnabled(editable&&priorityToggle.isChecked());priorityInput.setAlpha(priorityToggle.isChecked()?1f:.6f);}
    }
    private KeywordRules draft(){
        List<String> blocked=KeywordRules.parseWords(blockedInput.getText().toString()),priority=KeywordRules.parseWords(priorityInput.getText().toString());
        if(blockedToggle.isChecked()&&blocked.isEmpty())throw new IllegalArgumentException("请填写屏蔽词，或关闭屏蔽开关");
        if(priorityToggle.isChecked()&&priority.isEmpty())throw new IllegalArgumentException("请填写优先提醒词，或关闭优先提醒开关");
        return new KeywordRules(blockedToggle.isChecked(),blocked,priorityToggle.isChecked(),priority);
    }
    private void preview(boolean saved){
        if(!loaded||saving||!ownsProfile())return;
        try{KeywordRules rules=saved?KeywordRules.loadStrict(this,profile):draft();
            startActivity(new android.content.Intent(this,RulePreviewActivity.class).putExtra("profile",profile)
                    .putExtra("rules",rules.fingerprint()).putExtra("blockedOnly",saved));
        }catch(RuntimeException failure){Toast.makeText(this,failure instanceof IllegalArgumentException?failure.getMessage():"规则暂时无法读取，请重试",Toast.LENGTH_LONG).show();}
    }
    private void undo(){
        if(saving||!ownsProfile())return;
        try{KeywordRules old=KeywordRules.previous(this,profile);
            if(old==null){Toast.makeText(this,"还没有可以恢复的上一版规则",Toast.LENGTH_SHORT).show();return;}
            String summary="将恢复为：\n屏蔽："+(old.blockedEnabled?TextUtils.join("、",old.blockedWords):"已暂停")+"\n优先提醒："+(old.priorityEnabled?TextUtils.join("、",old.priorityWords):"已暂停")+"\n\n当前草稿将被替换，阅读状态和标签保留。";
            new AlertDialog.Builder(this).setTitle("撤销上一次保存？").setMessage(summary).setNegativeButton("取消",null).setPositiveButton("恢复上一版",(d,w)->{
                if(!ownsProfile()){finish();return;}
                if(KeywordRules.undo(this,profile)){load(null);setResult(RESULT_OK);Toast.makeText(this,"已恢复上一版规则",Toast.LENGTH_SHORT).show();}
                else Toast.makeText(this,"恢复失败，请重试",Toast.LENGTH_LONG).show();
            }).show();
        }catch(RuntimeException failed){Toast.makeText(this,"上一版规则无法读取；没有更改现有内容",Toast.LENGTH_LONG).show();}
    }
    private void save(){
        if(!loaded||saving)return;
        if(!ownsProfile()){Toast.makeText(this,"账号已变化，规则未保存",Toast.LENGTH_LONG).show();finish();return;}
        blockedInput.setError(null);priorityInput.setError(null);
        final List<String> blocked,priority;
        try{blocked=KeywordRules.parseWords(blockedInput.getText().toString());}
        catch(IllegalArgumentException invalid){invalidInput(blockedInput,"屏蔽词最多 50 个，每词最多 100 字");return;}
        try{priority=KeywordRules.parseWords(priorityInput.getText().toString());}
        catch(IllegalArgumentException invalid){invalidInput(priorityInput,"优先提醒词最多 50 个，每词最多 100 字");return;}
        if(blockedToggle.isChecked()&&blocked.isEmpty()){invalidInput(blockedInput,"请输入至少一个屏蔽词，或关闭此规则");return;}
        if(priorityToggle.isChecked()&&priority.isEmpty()){invalidInput(priorityInput,"请输入至少一个优先提醒词，或关闭此规则");return;}
        saving=true;updateEnabled();
        try{
            KeywordRules rules=new KeywordRules(blockedToggle.isChecked(),blocked,priorityToggle.isChecked(),priority);
            if(!ownsProfile()){Toast.makeText(this,"账号已变化，规则未保存",Toast.LENGTH_LONG).show();finish();return;}
            if(!rules.save(this,profile))throw new IllegalStateException("Rule persistence failed");
            setResult(RESULT_OK);Toast.makeText(this,"关键词规则已保存",Toast.LENGTH_SHORT).show();finish();
        }catch(RuntimeException failure){
            saving=false;updateEnabled();error.setText("保存失败，输入的词仍保留在此页面。请重试。");error.setVisibility(View.VISIBLE);Toast.makeText(this,"规则未保存，请重试",Toast.LENGTH_LONG).show();
        }
    }
    private void invalidInput(EditText input,String message){
        // A paused rule can still contain invalid text restored from an unfinished edit.
        if(!input.isEnabled()){if(input==blockedInput)blockedToggle.setChecked(true);else priorityToggle.setChecked(true);}
        input.setError(message);input.requestFocus();
    }
    private boolean hasChanges(){return loaded&&(blockedToggle.isChecked()!=originalBlocked||priorityToggle.isChecked()!=originalPriority||!originalBlockedText.contentEquals(blockedInput.getText())||!originalPriorityText.contentEquals(priorityInput.getText()));}
    private void leave(){
        if(saving)return;
        if(!hasChanges()){finish();return;}
        new AlertDialog.Builder(this).setTitle("放弃尚未保存的更改？").setMessage("当前账号已保存的规则不会改变。")
                .setPositiveButton("放弃更改",(dialog,which)->finish()).setNegativeButton("继续编辑",null).show();
    }
    @Override public void onBackPressed(){leave();}
    @Override protected void onSaveInstanceState(Bundle state){
        if(loaded){state.putString("rules_profile",profile);state.putBoolean("blocked_enabled",blockedToggle.isChecked());state.putBoolean("priority_enabled",priorityToggle.isChecked());state.putString("blocked_text",blockedInput.getText().toString());state.putString("priority_text",priorityInput.getText().toString());}
        super.onSaveInstanceState(state);
    }
    private Switch toggle(LinearLayout page,String label){
        Switch toggle=new Switch(this);toggle.setText(label);toggle.setTextColor(ReaderUi.INK);toggle.setTextSize(16);toggle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);toggle.setPadding(0,dp(8),0,0);toggle.setSwitchPadding(dp(12));toggle.setMinHeight(dp(56));page.addView(toggle,new LinearLayout.LayoutParams(-1,-2));return toggle;
    }
    private EditText editor(LinearLayout page,String label,String hint){
        EditText input=new EditText(this);input.setId(View.generateViewId());input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setSingleLine(false);input.setMinLines(3);input.setMaxLines(5);input.setGravity(Gravity.TOP|Gravity.START);input.setTextSize(15);input.setTextColor(ReaderUi.INK);input.setHintTextColor(ReaderUi.MUTED);input.setHint(hint);input.setContentDescription(label);input.setPadding(dp(12),dp(10),dp(12),dp(10));
        GradientDrawable background=ReaderUi.background(0xfffafbfc,6,this);background.setStroke(dp(1),ReaderUi.LINE);input.setBackground(background);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(8),0,dp(4));page.addView(input,params);return input;
    }
    private void note(LinearLayout page,String value,int size){TextView text=ReaderUi.text(this,value,size,ReaderUi.MUTED);text.setLineSpacing(dp(3),1);text.setPadding(0,dp(4),0,dp(8));page.addView(text,new LinearLayout.LayoutParams(-1,-2));}
    private void divider(LinearLayout page){View line=new View(this);line.setBackgroundColor(ReaderUi.LINE);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(1));params.setMargins(0,dp(16),0,dp(8));page.addView(line,params);}
    private Button action(String label,Runnable action){Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(14);button.setTextColor(ReaderUi.BLUE);button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);button.setPadding(0,0,0,0);button.setMinHeight(dp(48));android.util.TypedValue value=new android.util.TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackground,value,true);if(value.resourceId!=0)button.setBackgroundResource(value.resourceId);button.setOnClickListener(v->action.run());return button;}
    private int dp(int value){return ReaderUi.dp(this,value);}
}
