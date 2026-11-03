package org.campusreader.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.campusreader.notices.*;

/** Manual one-article flow. Source loading, confirmation, request, and result remain separate. */
public final class AiSummaryActivity extends Activity {
    public static final String EXTRA_PROFILE="profile",EXTRA_SOURCE="source",EXTRA_STABLE_ID="stable_id";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private String profile;
    private NoticeKey key;
    private AiSettings settings;
    private SummaryInput input;
    private AiSettings.Config config;
    private TextView title,status;
    private LinearLayout results;
    private Button generate,cancel;
    private volatile long sequence;
    private boolean busy;
    private AiTransport.Cancellation cancellation;
    private Future<?> task;
    private final Runnable monitor=new Runnable(){@Override public void run(){
        if(isDestroyed()||isFinishing())return;
        if(!AiProfile.owns(AiSummaryActivity.this,profile)){cancelActive(false);finish();return;}
        if(busy&&config!=null&&!settings.isCurrent(config)){cancelActive(true);status.setText("AI 已关闭或设置已变化，本次生成已取消。");}
        main.postDelayed(this,500);
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        profile=getIntent().getStringExtra(EXTRA_PROFILE);
        if(!AiProfile.owns(this,profile)){finish();return;}
        try{key=CampusNoticeStore.summaryKey(getIntent().getStringExtra(EXTRA_SOURCE),getIntent().getStringExtra(EXTRA_STABLE_ID));}catch(IllegalArgumentException invalid){finish();return;}
        settings=new AiSettings(this,profile);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(16),dp(20),dp(24));page.setBackgroundColor(Color.WHITE);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        button(page,"‹ 返回通知正文",this::finish);
        note(page,"AI 通知摘要",23,ReaderUi.INK);title=note(page,"正在读取本机正文…",17,ReaderUi.INK);
        note(page,"只分析缓存中的标题和文字正文，不分析图片或附件。结果可能遗漏或误解；点击依据核对原文，时间和操作要求以通知正文为准。",13,ReaderUi.MUTED);
        status=note(page,"",14,ReaderUi.BLUE);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        generate=button(page,"生成摘要",this::confirm);cancel=button(page,"取消本次生成",()->cancelActive(true));cancel.setEnabled(false);
        button(page,"AI 服务设置",()->{cancelActive(false);startActivity(new Intent(this,AiSettingsActivity.class).putExtra(AiSettingsActivity.EXTRA_PROFILE,profile));});
        results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);page.addView(results);
    }
    @Override protected void onResume(){super.onResume();if(settings==null)return;if(!AiProfile.owns(this,profile)){finish();return;}main.removeCallbacks(monitor);main.post(monitor);reload();}
    @Override protected void onStop(){cancelActive(false);main.removeCallbacks(monitor);super.onStop();}
    @Override protected void onDestroy(){cancelActive(false);main.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}
    private void reload(){
        final long token=++sequence;generate.setEnabled(false);results.removeAllViews();
        task=worker.submit(()->{
            try{if(!AiProfile.owns(this,profile))return;SummaryInput loaded;SummaryRecord cached;AiSettings.Config service=settings.load();
                try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){loaded=store.summaryInput(key);cached=loaded==null?null:store.findSummary(key,loaded.contentHash,service.configHash);}
                final AiSummaryModel result=cached==null?null:AiSummaryModel.parse(cached.resultJson,AiPolicy.sentText(loaded));
                main.post(()->{if(!active(token))return;input=loaded;config=service;title.setText(loaded==null?"正文未缓存或没有可用文字":loaded.title);
                    if(loaded==null){status.setText("请返回通知重新获取正文。图片通知和附件不参与本版摘要。");generate.setEnabled(false);return;}
                    generate.setEnabled(service.enabled);generate.setText(result==null?"生成摘要":"重新生成（需要再次确认）");
                    status.setText(service.enabled?(result==null?"点击生成后，先核对将要发送的完整文字。":"显示已缓存摘要；尚未重新请求。") :"AI 生成已关闭；可在服务设置启用。已有缓存仍可查看。");
                    if(result!=null)showResult(result,cached.generatedAtMillis);
                });
            }catch(Exception failure){main.post(()->{if(active(token)){status.setText(failure instanceof IllegalStateException?failure.getMessage():"无法读取正文、摘要或服务设置，请返回后重试。");generate.setEnabled(false);}});}
        });
    }
    private void confirm(){
        if(busy||input==null||!AiProfile.owns(this,profile))return;
        final AiSettings.Config service;final String exact;
        try{service=settings.load();if(!service.enabled)throw new IllegalArgumentException("请先在服务设置启用 AI 摘要。");AiPolicy.endpoint(service.endpoint);AiPolicy.model(service.model);AiPolicy.key(service.key);exact=AiPolicy.sentText(input);}
        catch(RuntimeException failure){status.setText(failure.getMessage());return;}
        if(settings.usedToday()>=AiPolicy.DAILY_LIMIT){status.setText("今日已达到 20 次本机尝试上限，未发送请求。");return;}
        SummaryInput source=input;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(8),dp(18),dp(8));
        note(content,"发送到："+URI.create(service.endpoint).getHost()+"\n通知："+source.title+"\n下方为本次将发送的完整标题和正文。仅发送这些资料及应用的摘要指令，不发送原文地址、图片、附件或登录凭据。",13,ReaderUi.INK);
        note(content,"API 可能收费；今日已尝试 "+settings.usedToday()+" / 20 次。取消或失败也可能计费，无自动重试。",13,0xffb3261e);
        TextView preview=note(content,exact,13,ReaderUi.INK);preview.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("确认发送给 AI 服务？").setView(scroll)
                .setPositiveButton("确认发送一次",(d,w)->startGeneration(source,service,exact)).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getWindow().setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.85));});dialog.show();
    }
    private void startGeneration(SummaryInput source,AiSettings.Config service,String exact){
        if(busy||!AiProfile.owns(this,profile)||!settings.isCurrent(service)){status.setText("账号或设置已变化，请重新确认。");return;}
        final long token=++sequence;config=service;busy=true;generate.setEnabled(false);cancel.setEnabled(true);status.setText("正在生成；可取消。不会自动重试。");
        final AiTransport.Cancellation stop=new AiTransport.Cancellation();cancellation=stop;
        task=worker.submit(()->{
            try{
                AiTransport.Guard guard=()->{stop.check();if(token!=sequence||!AiProfile.owns(this,profile)||!settings.isCurrent(service))throw new IOException("STALE");};
                guard.check();try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){SummaryInput now=store.summaryInput(key);if(now==null||source.cacheGeneration!=now.cacheGeneration||!source.contentHash.equals(now.contentHash))throw new IOException("STALE");}
                synchronized(AiSettings.LOCK){guard.check();if(!settings.reserve(service))throw new IOException("QUOTA");}
                AiSummaryModel result=AiTransport.generate(service,exact,stop,guard);guard.check();long generated=System.currentTimeMillis();
                synchronized(AiSettings.LOCK){guard.check();try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){if(!store.saveSummaryIfCurrent(key,source.contentHash,source.cacheGeneration,service.configHash,result.json,generated))throw new IOException("STALE");}}
                main.post(()->{if(!active(token)||!settings.isCurrent(service))return;busy=false;cancel.setEnabled(false);generate.setEnabled(true);generate.setText("重新生成（需要再次确认）");status.setText("已生成并缓存在本机。今日已尝试 "+settings.usedToday()+" / 20 次。");showResult(result,generated);});
            }catch(Exception failure){main.post(()->{if(!active(token))return;busy=false;cancel.setEnabled(false);generate.setEnabled(settings.enabled());status.setText(AiTransport.failureMessage(failure));});}
        });
    }
    private boolean active(long token){return !isDestroyed()&&!isFinishing()&&token==sequence&&AiProfile.owns(this,profile);}
    private void cancelActive(boolean tell){
        boolean wasBusy=busy;++sequence;if(cancellation!=null)cancellation.cancel();cancellation=null;if(task!=null)task.cancel(true);task=null;busy=false;
        if(cancel!=null)cancel.setEnabled(false);if(generate!=null)generate.setEnabled(input!=null&&settings!=null&&settings.enabled());
        if(tell&&wasBusy&&status!=null)status.setText("已取消本次生成；服务方仍可能计费。已有摘要保留。");
    }
    private void showResult(AiSummaryModel result,long generated){
        results.removeAllViews();note(results,"生成于 "+android.text.format.DateFormat.format("MM-dd HH:mm",generated),12,ReaderUi.MUTED);
        for(AiSummaryModel.Field field:result.fields){note(results,field.label,16,ReaderUi.INK);note(results,field.value.isEmpty()?"未提及或无法核实":field.value,15,ReaderUi.INK);
            if(!field.quote.isEmpty())button(results,"原文依据："+field.quote,()->showPassage(field.quote));}
        if(result.discarded>0)note(results,"有 "+result.discarded+" 个字段未通过原文引用校验，已隐藏。",13,0xffb3261e);
        note(results,"引文存在不保证解释正确；请核对通知全文。",12,ReaderUi.MUTED);
    }
    private void showPassage(String quote){
        if(input==null)return;String exact=AiPolicy.sentText(input);int index=exact.indexOf(quote);if(index<0)return;
        SpannableString marked=new SpannableString(exact);marked.setSpan(new BackgroundColorSpan(0xffffeb99),index,index+quote.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView body=ReaderUi.text(this,"",16,ReaderUi.INK);body.setText(marked);body.setTextIsSelectable(true);body.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(body);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("原文依据（高亮）").setView(scroll).setPositiveButton("返回摘要",null).setNeutralButton("返回通知正文",(d,w)->finish()).create();
        final int offset=index;dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);body.post(()->{if(body.getLayout()!=null)scroll.scrollTo(0,body.getLayout().getLineTop(body.getLayout().getLineForOffset(offset)));});});dialog.show();
    }
    private TextView note(LinearLayout page,String value,int size,int color){TextView text=ReaderUi.text(this,value,size,color);text.setLineSpacing(dp(3),1);text.setPadding(0,dp(8),0,dp(8));page.addView(text);return text;}
    private Button button(LinearLayout page,String value,Runnable action){Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());page.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;}
    private int dp(int value){return ReaderUi.dp(this,value);}
}
