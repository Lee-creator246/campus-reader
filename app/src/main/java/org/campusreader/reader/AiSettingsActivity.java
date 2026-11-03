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
    private EditText endpoint,model,key;
    private TextView status;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        profile=getIntent().getStringExtra(EXTRA_PROFILE);if(!AiProfile.owns(this,profile)){finish();return;}
        settings=new AiSettings(this,profile);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(20),dp(20),dp(24));page.setBackgroundColor(Color.WHITE);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        button(page,"‹ 返回阅读",this::finish);
        note(page,"AI 摘要服务",23,ReaderUi.INK);
        note(page,"默认关闭，仅在单篇通知内由你确认后发送标题和文字正文。不读取图片、附件或校园登录凭据。校内内容会交给你填写的服务方处理，请仅使用可信服务并确认有权发送。",14,ReaderUi.MUTED);
        enabled=new Switch(this);enabled.setText("启用手动 AI 摘要");enabled.setChecked(settings.enabled());enabled.setMinHeight(dp(56));page.addView(enabled);
        endpoint=field(page,"完整 HTTPS API 地址","例如 https://api.example.com/v1/chat/completions",false);endpoint.setText(settings.endpoint());
        model=field(page,"模型名称","填写服务方提供的模型 ID",false);model.setText(settings.model());
        key=field(page,"API Key",settings.hasKey()?"已安全保存；留空保留，输入可替换":"填写自己的 API Key",true);
        note(page,"服务须兼容 chat/completions 与 JSON 对象输出。密钥加密保存在本机，不随配置导入或分享。",12,ReaderUi.MUTED);
        note(page,"每次最多发送 12000 字符，输出上限 1000 tokens；每个账号每天最多 20 次本机尝试（失败或取消也计入）。API 可能收费，实际费用、额度、数据留存和删除由服务方决定；应用不查询余额、不自动重试。",13,ReaderUi.MUTED);
        status=note(page,"",13,ReaderUi.BLUE);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(page,"保存设置",()->{
            if(!AiProfile.owns(this,profile)){finish();return;}
            try{settings.save(enabled.isChecked(),endpoint.getText().toString(),model.getText().toString(),key.getText().toString());key.setText("");key.setHint(settings.hasKey()?"已安全保存；留空保留，输入可替换":"填写自己的 API Key");status.setText("已保存。今日已尝试 "+settings.usedToday()+" / 20 次；未发送测试请求。");}
            catch(RuntimeException failure){status.setText(failure.getMessage());}
        });
        enabled.setOnCheckedChangeListener((view,on)->{if(!on)try{settings.disable();status.setText("AI 生成已关闭；已缓存摘要仍可查看。开启时请再保存。");}catch(RuntimeException failed){status.setText("关闭未能保存，请重试。");}});
        button(page,"关闭并删除 API Key",()->{
            if(!AiProfile.owns(this,profile)){finish();return;}
            try{settings.deleteKey();enabled.setChecked(false);key.setText("");key.setHint("填写自己的 API Key");status.setText("已关闭 AI 并删除本机 API Key。已缓存摘要可单独清理。");}
            catch(RuntimeException failure){status.setText(failure.getMessage());}
        });
        button(page,"清空当前账号的摘要缓存",()->new AlertDialog.Builder(this).setTitle("清空摘要缓存？").setMessage("仅清理当前站点及账号的摘要，通知正文、已读、稍后读和标签保留。")
                .setPositiveButton("清空",(d,w)->worker.execute(()->{
                    try{int count;synchronized(AiSettings.LOCK){if(!AiProfile.owns(this,profile))return;settings.invalidateRequests();try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){count=store.clearSummaries();}}
                        runOnUiThread(()->{if(!isDestroyed()&&AiProfile.owns(this,profile))status.setText("已清理 "+count+" 条摘要。");});}
                    catch(RuntimeException failure){runOnUiThread(()->{if(!isDestroyed())status.setText("摘要缓存未能清理，请重试。");});}
                })).setNegativeButton("取消",null).show());
        note(page,"关闭或删除本机密钥不会撤回已发送的资料。摘要可能遗漏或误解原文；引用校验只证明引文存在，不保证解释正确，请始终核对通知正文。",12,ReaderUi.MUTED);
    }
    @Override protected void onResume(){super.onResume();if(profile!=null&&!AiProfile.owns(this,profile))finish();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
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
