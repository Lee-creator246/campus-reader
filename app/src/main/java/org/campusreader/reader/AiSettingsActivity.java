package org.campusreader.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.campusreader.notices.CampusNoticeStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Device-local explicit opt-in; no service probe or account connection happens here. */
public final class AiSettingsActivity extends Activity {
    public static final String EXTRA_PROFILE="profile";
    private String profile;
    private AiSettings settings;
    private Switch enabled;
    private CheckBox automatic,automaticPolicies;
    private SeekBar dailyLimit;
    private EditText endpoint,model,key;
    private AiProvider provider;
    private TextView providerLabel;
    private TextView status;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        profile=getIntent().getStringExtra(EXTRA_PROFILE);if(!AiProfile.owns(this,profile)){finish();return;}
        settings=new AiSettings(this,profile);
        provider=settings.provider();
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(20),dp(20),dp(24));page.setBackgroundColor(Color.WHITE);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        button(page,"‹ 返回阅读",this::finish);
        note(page,"AI 摘要服务",23,ReaderUi.INK);
        note(page,"配置服务后，可一次授权自动摘要：当前页和新通知的文字将发送给所选服务。未启用自动摘要时，仍可逐篇预览并确认。校园密码、画像、图片和附件文件不发送。",14,ReaderUi.MUTED);
        enabled=new Switch(this);enabled.setText("启用 AI 服务");enabled.setChecked(settings.enabled());enabled.setMinHeight(dp(56));page.addView(enabled);
        automatic=new CheckBox(this);automatic.setText("自动摘要：允许发送当前页和新增通知文字");automatic.setChecked(settings.automaticEnabled());automatic.setMinHeight(dp(48));page.addView(automatic);
        automaticPolicies=new CheckBox(this);automaticPolicies.setText("自动摘要时，允许发送检索命中的政策片段及其资料信息");automaticPolicies.setChecked(settings.automaticPolicies());automaticPolicies.setMinHeight(dp(48));page.addView(automaticPolicies);
        providerLabel=note(page,"当前接口："+provider.label,14,ReaderUi.INK);
        button(page,"使用 DeepSeek 官方预设",()->selectProvider(AiProvider.DEEPSEEK));
        button(page,"使用自定义兼容接口",()->selectProvider(AiProvider.CUSTOM));
        endpoint=field(page,"完整 HTTPS API 地址","例如 https://api.example.com/v1/chat/completions",false);endpoint.setText(settings.endpoint());
        model=field(page,"模型名称","填写服务方提供的模型 ID",false);model.setText(settings.model());
        key=field(page,"API Key",settings.hasKey()?"已安全保存；留空保留，输入可替换":"填写自己的 API Key",true);
        TextView quota=note(page,"每日尝试额度："+settings.dailyLimit()+" 次",14,ReaderUi.INK);dailyLimit=new SeekBar(this);dailyLimit.setMax(99);dailyLimit.setProgress(settings.dailyLimit()-1);dailyLimit.setContentDescription("每日AI尝试额度，1至100次");page.addView(dailyLimit,new LinearLayout.LayoutParams(-1,dp(48)));
        dailyLimit.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean user){quota.setText("每日尝试额度："+(value+1)+" 次");}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
        note(page,"DeepSeek 预设使用 deepseek-flash，关闭思考模式，生成附短句引用的简洁摘要；模型名称可以修改。自定义服务须兼容 chat/completions 与 JSON 对象输出。密钥加密保存在本机。",12,ReaderUi.MUTED);
        note(page,"通知文字最多 12000 字符，含政策片段的发送资料最多 "+AiPolicy.MAX_CONTEXT_CHARS+" 字符，输出上限 "+AiPolicy.MAX_OUTPUT_TOKENS+" tokens；每日本机尝试额度可设置为1至100次，默认20次，失败或取消也计入。修改额度不会清零今日次数。API 可能收费，实际费用和数据留存由服务方决定。",13,ReaderUi.MUTED);
        status=note(page,"",13,ReaderUi.BLUE);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        note(page,"自动任务最多保留60条，单次优先处理当前页10条；每次后台执行至多请求3篇。只读预览不发请求，结果未知时不会自动重发。API 可能收费，请确认校内资料允许发送给该服务。",12,ReaderUi.MUTED);
        button(page,"保存设置",this::save);
        button(page,"保存并开启自动摘要",()->{enabled.setChecked(true);automatic.setChecked(true);save();});
        enabled.setOnCheckedChangeListener((view,on)->{if(!on)try{settings.disable();AiAutoSummaries.settingsChanged(this,profile);status.setText("AI 生成已关闭；已缓存摘要仍可查看。开启时请再保存。");}catch(RuntimeException failed){status.setText("关闭未能保存，请重试。");}});
        button(page,"关闭并删除 API Key",()->{
            if(!AiProfile.owns(this,profile)){finish();return;}
            try{settings.deleteKey();AiAutoSummaries.settingsChanged(this,profile);enabled.setChecked(false);key.setText("");key.setHint("填写自己的 API Key");status.setText("已关闭 AI 并删除本机 API Key。已缓存摘要可单独清理。");}
            catch(RuntimeException failure){status.setText(failure.getMessage());}
        });
        button(page,"清空当前账号的摘要缓存",()->new AlertDialog.Builder(this).setTitle("清空摘要缓存？").setMessage("清理摘要和任务状态，保留通知及阅读状态。自动摘要开启时，后续浏览会重新生成，可能再次计费。")
                .setPositiveButton("清空",(d,w)->worker.execute(()->{
                    try{int count;synchronized(AiSettings.LOCK){if(!AiProfile.owns(this,profile))return;settings.invalidateRequests();AiAutoSummaries.cancelAndClear(this,profile);try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){count=Math.max(store.clearSummaries(),AiCoreCache.clear(store));}}
                        runOnUiThread(()->{if(!isDestroyed()&&AiProfile.owns(this,profile))status.setText("已清理 "+count+" 条摘要。");});}
                    catch(RuntimeException failure){runOnUiThread(()->{if(!isDestroyed())status.setText("摘要缓存未能清理，请重试。");});}
                })).setNegativeButton("取消",null).show());
        note(page,"关闭或删除本机密钥不会撤回已发送的资料。摘要可能遗漏或误解原文；引用校验只证明引文存在，不保证解释正确，请始终核对通知正文。",12,ReaderUi.MUTED);
    }
    private void save(){
        if(!AiProfile.owns(this,profile)){finish();return;}
        try{settings.save(enabled.isChecked(),endpoint.getText().toString(),model.getText().toString(),key.getText().toString(),provider);settings.setDailyLimit(dailyLimit.getProgress()+1);settings.configureAutomation(enabled.isChecked()&&automatic.isChecked(),automaticPolicies.isChecked());AiAutoSummaries.settingsChanged(this,profile);
            key.setText("");key.setHint(settings.hasKey()?"已安全保存；留空保留，输入可替换":"填写自己的 API Key");status.setText(settings.automaticEnabled()?"已授权自动摘要，返回通知列表后处理当前页及新增内容。今日已尝试 "+settings.usedToday()+" / "+settings.dailyLimit()+" 次。":"已保存。今日已尝试 "+settings.usedToday()+" / "+settings.dailyLimit()+" 次；未发送测试请求。");
        }catch(RuntimeException failure){status.setText(failure.getMessage());}
    }
    @Override protected void onResume(){super.onResume();if(profile!=null&&!AiProfile.owns(this,profile))finish();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private void selectProvider(AiProvider next){
        provider=next;providerLabel.setText("当前接口："+provider.label);
        key.setText("");
        if(provider==AiProvider.DEEPSEEK){endpoint.setText(provider.defaultEndpoint);model.setText(provider.defaultModel);}
        status.setText("已选择 "+provider.label+"，尚未保存，也未发送请求。更换 API 地址后需重新填写对应密钥；启用状态由上方开关决定。");
    }
    private EditText field(LinearLayout page,String label,String hint,boolean secret){
        note(page,label,14,ReaderUi.INK);EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(14);input.setHint(hint);input.setSaveEnabled(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_URI));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(secret?513:2049)});
        if(android.os.Build.VERSION.SDK_INT>=26)input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);page.addView(input,new LinearLayout.LayoutParams(-1,dp(56)));return input;
    }
    private TextView note(LinearLayout page,String value,int size,int color){TextView text=ReaderUi.text(this,value,size,color);text.setLineSpacing(dp(3),1);text.setPadding(0,dp(10),0,dp(8));page.addView(text);return text;}
    private void button(LinearLayout page,String label,Runnable action){Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());page.addView(button,new LinearLayout.LayoutParams(-1,-2));}
    private int dp(int value){return ReaderUi.dp(this,value);}
}
