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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.campusreader.notices.*;

/** Manual, source-bound extraction. Generated prose never replaces the original notice. */
public final class AiSummaryActivity extends Activity {
    public static final String EXTRA_PROFILE="profile",EXTRA_SOURCE="source",EXTRA_STABLE_ID="stable_id";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private String profile;
    private NoticeKey key;
    private AiSettings settings;
    private PolicyLibrary library;
    private SummaryInput input;
    private AiEvidence.Snapshot evidence;
    private AiSettings.Config config;
    private TextView title,status,policyStatus;
    private LinearLayout results;
    private Button generate,cancel;
    private volatile long sequence;
    private boolean busy;
    private AiTransport.Cancellation cancellation;
    private Future<?> task;
    private AlertDialog openEvidenceDialog,openConfirmation;
    private boolean sourceCheckPending;
    private boolean interruptedByLeaving;
    static final String LEAVING_NOTICE="上次生成因离开摘要页或锁屏已取消，未自动重试；服务方仍可能计费。已有缓存仍可查看。重新生成时请保持本页前台。";
    private long lastSourceCheck;
    private final Runnable monitor=new Runnable(){@Override public void run(){
        if(isDestroyed()||isFinishing())return;
        if(!AiProfile.owns(AiSummaryActivity.this,profile)){cancelActive(false);finish();return;}
        try{
            if(evidence!=null&&!library.isCurrent(evidence.libraryRevision)){
                dismissSourceDialogs();cancelActive(false);results.removeAllViews();evidence=null;
                status.setText("政策资料已变化，正在重新检查本地摘要。");reload();
            }else if(busy&&config!=null&&!settings.isCurrent(config)){
                cancelActive(true);status.setText("AI 已关闭或设置已变化，本次生成已取消。");
            }
            checkVisibleSource();
        }catch(RuntimeException unavailable){
            dismissSourceDialogs();cancelActive(false);results.removeAllViews();evidence=null;input=null;generate.setEnabled(false);
            status.setText("资料暂时无法核对，已隐藏摘要；请返回后重试。");
        }
        main.postDelayed(this,500);
    }};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        interruptedByLeaving=state!=null&&state.getBoolean("ai_interrupted",false);
        profile=getIntent().getStringExtra(EXTRA_PROFILE);
        if(!AiProfile.owns(this,profile)){finish();return;}
        try{key=CampusNoticeStore.summaryKey(getIntent().getStringExtra(EXTRA_SOURCE),getIntent().getStringExtra(EXTRA_STABLE_ID));}
        catch(IllegalArgumentException invalid){finish();return;}
        settings=new AiSettings(this,profile);library=new PolicyLibrary(this,profile);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20),dp(16),dp(20),dp(24));page.setBackgroundColor(Color.WHITE);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        button(page,"‹ 返回通知正文",this::finish);
        note(page,"AI 通知摘要",23,ReaderUi.INK);title=note(page,"正在读取本机正文…",17,ReaderUi.INK);
        note(page,"AI 归类的原文摘录，可能漏选或错选，不作资格或政策效力判断。图片和通知附件未解析；请核对全文及其中的条件、例外和时间。",13,ReaderUi.MUTED);
        policyStatus=note(page,"",13,ReaderUi.MUTED);
        status=note(page,"",14,ReaderUi.BLUE);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        generate=button(page,"生成摘要",this::confirm);cancel=button(page,"取消本次生成",()->cancelActive(true));cancel.setEnabled(false);
        button(page,"政策资料库",()->openSettings(PolicyLibraryActivity.class));
        button(page,"AI 服务设置",()->openSettings(AiSettingsActivity.class));
        results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);page.addView(results);
    }
    private void openSettings(Class<? extends Activity> activity){if(busy)interruptedByLeaving=true;cancelActive(false);startActivity(new Intent(this,activity).putExtra("profile",profile));}
    @Override protected void onResume(){
        super.onResume();if(settings==null)return;if(!AiProfile.owns(this,profile)){finish();return;}
        main.removeCallbacks(monitor);main.post(monitor);reload();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("ai_interrupted",busy||interruptedByLeaving);super.onSaveInstanceState(state);}
    @Override protected void onStop(){if(busy)interruptedByLeaving=true;dismissSourceDialogs();cancelActive(false);main.removeCallbacks(monitor);super.onStop();}
    @Override protected void onDestroy(){cancelActive(false);main.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}

    private void reload(){
        final long token=++sequence;generate.setEnabled(false);results.removeAllViews();
        task=worker.submit(()->{
            try{
                if(!AiProfile.owns(this,profile))return;
                SummaryInput loaded;SummaryRecord cached;AiEvidence.Snapshot context;
                AiSettings.Config service=settings.load();PolicyLibrary.Snapshot policies=library.snapshot();
                try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){
                    loaded=store.summaryInput(key);List<String> names=new ArrayList<>();for(NoticeTag tag:store.listTags())names.add(tag.name);
                    context=loaded==null?null:AiEvidence.build(loaded,policies,names);
                    cached=context==null?null:store.findSummary(key,loaded.contentHash,context.cacheHash(service.configHash));
                }
                AiGroundedSummary parsed=null;
                if(cached!=null)try{parsed=AiGroundedSummary.parseCached(cached.resultJson,context);}catch(org.json.JSONException invalid){/* Regenerate invalid cache only after confirmation. */}
                if(loaded!=null)requireSource(loaded);
                final AiGroundedSummary result=parsed;
                main.post(()->{
                    if(!active(token)||!library.isCurrent(policies.revision))return;
                    input=loaded;evidence=context;config=service;title.setText(loaded==null?"正文未缓存或没有可用文字":loaded.title);
                    if(loaded==null){status.setText("请返回通知重新获取正文。图片通知和附件不参与本版摘要。");generate.setEnabled(false);return;}
                    policyStatus.setText(context.policyCount()==0
                            ?(policies.documents.isEmpty()?"尚未导入政策文件。本次仅分析通知文字；可在政策资料库添加文件。":"本地检索未找到候选政策段落；这不代表没有相关规定，可在资料库核对文件与适用范围。")
                            :"本地检索选入 "+context.policyCount()+" 份政策的部分段落；只作相关参考，未验证适用性或官方效力。发送前可查看完整资料清单。");
                    generate.setEnabled(service.enabled);generate.setText(result==null?"生成摘要":"重新生成（需要再次确认）");
                    status.setText(service.enabled?(result==null?"点击生成后，先核对将要发送的完整资料。":"显示已缓存摘要；尚未重新请求。")
                            :"AI 生成已关闭；可在服务设置启用。已有缓存仍可查看。");
                    if(interruptedByLeaving){status.setText(LEAVING_NOTICE);interruptedByLeaving=false;}
                    if(result!=null)showResult(result,cached.generatedAtMillis,loaded,context);
                });
            }catch(Exception failure){main.post(()->{if(active(token)){
                input=null;evidence=null;status.setText(failure instanceof IllegalArgumentException||failure instanceof IllegalStateException?failure.getMessage():"无法读取正文、摘要或服务设置，请返回后重试。");generate.setEnabled(false);
            }});}
        });
    }

    private void confirm(){
        if(busy||input==null||evidence==null||!AiProfile.owns(this,profile))return;
        final AiSettings.Config service;final AiEvidence.Snapshot context=evidence;final SummaryInput source=input;
        try{
            service=settings.load();if(!service.enabled)throw new IllegalArgumentException("请先在服务设置启用 AI 摘要。");
            service.provider.endpoint(service.endpoint);AiPolicy.model(service.model);AiPolicy.key(service.key);
            if(!library.isCurrent(context.libraryRevision))throw new IllegalArgumentException("政策资料已变化，请返回摘要页重新检查。");
        }catch(RuntimeException failure){status.setText(failure.getMessage());return;}
        if(settings.usedToday()>=AiPolicy.DAILY_LIMIT){status.setText("今日已达到 20 次本机尝试上限，未发送请求。");return;}
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(8),dp(18),dp(8));
        note(content,"发送到："+URI.create(service.endpoint).getHost()+"\n模型："+service.model+"\n通知："+source.title+
                "\n下方为本次将发送的完整资料，包括通知文字、选入的政策段落及其填写信息和候选标签。仅发送这些资料与摘要指令，不发送校园登录凭据、图片或附件文件。",13,ReaderUi.INK);
        note(content,"请确认资料允许交给该服务处理。政策段落由本地有限检索选出，可能遗漏相关文件；文件状态与范围由用户填写，应用未验证。",13,ReaderUi.MUTED);
        note(content,"API 可能收费；今日已尝试 "+settings.usedToday()+" / 20 次。取消或失败也可能计费，无自动重试。",13,0xffb3261e);
        TextView preview=note(content,context.exactText,13,ReaderUi.INK);preview.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("确认发送给 AI 服务？").setView(scroll)
                .setPositiveButton("确认发送一次",(d,w)->startGeneration(source,service,context)).setNegativeButton("取消",null).create();
        openConfirmation=dialog;dialog.setOnDismissListener(d->{if(openConfirmation==dialog)openConfirmation=null;});
        dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getWindow().setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.85));});dialog.show();
    }

    private void requireSource(SummaryInput expected)throws IOException{
        try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){
            SummaryInput now=store.summaryInput(key);
            if(now==null||expected.cacheGeneration!=now.cacheGeneration||!expected.contentHash.equals(now.contentHash))throw new IOException("STALE");
        }
    }
    private void startGeneration(SummaryInput source,AiSettings.Config service,AiEvidence.Snapshot context){
        if(busy||!AiProfile.owns(this,profile)||!settings.isCurrent(service)||!library.isCurrent(context.libraryRevision)){
            status.setText("账号、政策或设置已变化，请重新确认。");return;
        }
        final long token=++sequence;config=service;busy=true;interruptedByLeaving=false;generate.setEnabled(false);cancel.setEnabled(true);status.setText("正在提取；请保持本页前台，离开或锁屏会取消。不会自动重试。");
        final AiTransport.Cancellation stop=new AiTransport.Cancellation();cancellation=stop;
        task=worker.submit(()->{
            try{
                AiTransport.Guard guard=()->{
                    stop.check();if(token!=sequence||!AiProfile.owns(this,profile)||!settings.isCurrent(service)||!library.isCurrent(context.libraryRevision))throw new IOException("STALE");
                    requireSource(source);
                };
                synchronized(AiSettings.LOCK){guard.check();if(!settings.reserve(service))throw new IOException("QUOTA");}
                String json=AiTransport.requestJson(service,AiGroundedSummary.instruction(),context.exactText,stop,guard);
                AiGroundedSummary result=AiGroundedSummary.parse(json,context);guard.check();long generated=System.currentTimeMillis();
                synchronized(AiSettings.LOCK){
                    guard.check();try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){
                        if(!store.saveSummaryIfCurrent(key,source.contentHash,source.cacheGeneration,context.cacheHash(service.configHash),result.json,generated))throw new IOException("STALE");
                    }
                }
                guard.check();
                main.post(()->{
                    if(!active(token)||!settings.isCurrent(service)||!library.isCurrent(context.libraryRevision))return;
                    busy=false;cancel.setEnabled(false);generate.setEnabled(true);generate.setText("重新生成（需要再次确认）");
                    status.setText("已生成并缓存在本机。今日已尝试 "+settings.usedToday()+" / 20 次。");showResult(result,generated,source,context);
                });
            }catch(Exception failure){main.post(()->{if(!active(token))return;busy=false;cancel.setEnabled(false);generate.setEnabled(settings.enabled());status.setText(AiTransport.failureMessage(failure));});}
        });
    }
    private boolean active(long token){return !isDestroyed()&&!isFinishing()&&token==sequence&&AiProfile.owns(this,profile);}
    private void dismissSourceDialogs(){if(openEvidenceDialog!=null)openEvidenceDialog.dismiss();if(openConfirmation!=null)openConfirmation.dismiss();}
    /** One lightweight worker check per second while a cached result is visible. */
    private void checkVisibleSource(){
        long now=android.os.SystemClock.uptimeMillis();
        if(busy||input==null||sourceCheckPending||now-lastSourceCheck<1000)return;
        final long token=sequence;final SummaryInput source=input;sourceCheckPending=true;lastSourceCheck=now;
        worker.execute(()->{
            boolean current=true;try{requireSource(source);}catch(Exception changed){current=false;}
            final boolean valid=current;main.post(()->{sourceCheckPending=false;if(active(token)&&!valid){
                dismissSourceDialogs();cancelActive(false);results.removeAllViews();evidence=null;input=null;
                status.setText("通知正文或摘要缓存已变化，正在重新检查。");reload();
            }});
        });
    }
    private void cancelActive(boolean tell){
        boolean wasBusy=busy;++sequence;if(cancellation!=null)cancellation.cancel();cancellation=null;if(task!=null)task.cancel(true);task=null;busy=false;
        if(cancel!=null)cancel.setEnabled(false);if(generate!=null)generate.setEnabled(input!=null&&evidence!=null&&settings!=null&&settings.enabled());
        if(tell&&wasBusy&&status!=null)status.setText("已取消本次生成；服务方仍可能计费。已有摘要保留。");
    }

    private void showResult(AiGroundedSummary result,long generated,SummaryInput source,AiEvidence.Snapshot context){
        results.removeAllViews();note(results,"生成于 "+android.text.format.DateFormat.format("MM-dd HH:mm",generated),12,ReaderUi.MUTED);
        for(AiGroundedSummary.Field field:result.fields){
            note(results,field.label,16,ReaderUi.INK);
            if(field.evidence.isEmpty())note(results,"未找到可核实摘录，请查看原文",14,ReaderUi.MUTED);
            for(AiEvidence.Unit unit:field.evidence){note(results,unit.text,15,ReaderUi.INK);button(results,"查看原文依据 · "+position(unit),()->showPassage(unit,source,context));}
        }
        note(results,"相关政策参考",17,ReaderUi.INK);
        if(result.links.isEmpty())note(results,"本次没有形成可核对的政策关联，不代表不存在相关规定。",13,ReaderUi.MUTED);
        for(AiGroundedSummary.Link link:result.links){
            note(results,link.policy.title,15,ReaderUi.INK);note(results,"模型建议关联，适用性待核对；不构成通知正式引用或政策效力判断。",13,ReaderUi.MUTED);
            note(results,link.policy.meta,12,ReaderUi.MUTED);note(results,link.policy.text,14,ReaderUi.INK);
            button(results,"核对通知依据 · "+position(link.notice),()->showPassage(link.notice,source,context));
            button(results,"核对政策依据 · "+position(link.policy),()->showPassage(link.policy,source,context));
        }
        note(results,"建议标签",17,ReaderUi.INK);
        if(result.tags.isEmpty())note(results,"没有可核对的标签建议；可以继续使用手动标签。",13,ReaderUi.MUTED);
        for(AiGroundedSummary.Tag tag:result.tags){
            note(results,tag.name,15,ReaderUi.INK);
            for(AiEvidence.Unit unit:tag.evidence)button(results,"标签依据 · "+position(unit),()->showPassage(unit,source,context));
            Button accept=button(results,"采纳标签："+tag.name,()->{});
            accept.setOnClickListener(v->acceptTag(tag.name,source,context,accept));
        }
        if(result.discarded>0)note(results,"有 "+result.discarded+" 项未通过来源或格式校验，已隐藏。",13,0xffb3261e);
        note(results,"逐字摘录只能保证文字来自资料，不能保证 AI 归类正确或条件完整。摘要不自动修改已读、稍后读或标签。",12,ReaderUi.MUTED);
    }
    private void acceptTag(String name,SummaryInput source,AiEvidence.Snapshot context,Button button){
        if(!AiProfile.owns(this,profile)||!library.isCurrent(context.libraryRevision)){status.setText("资料已变化，请重新查看摘要后采纳。");return;}
        final long token=sequence;button.setEnabled(false);
        task=worker.submit(()->{
            try{
                synchronized(AiSettings.LOCK){
                    if(!active(token)||!library.isCurrent(context.libraryRevision))throw new IOException("STALE");
                    try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){
                        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
                        try{
                            SummaryInput now=store.summaryInput(key);
                            if(now==null||source.cacheGeneration!=now.cacheGeneration||!source.contentHash.equals(now.contentHash))throw new IOException("STALE");
                            NoticeTag tag=store.createTag(name);store.addTag(key,tag.id);db.setTransactionSuccessful();
                        }finally{db.endTransaction();}
                    }
                }
                main.post(()->{if(active(token)){button.setText("已采纳："+name);status.setText("标签已添加，已有标签、已读和稍后读状态保留。");}});
            }catch(Exception failure){main.post(()->{if(active(token)){button.setEnabled(true);status.setText("资料已变化或标签未能保存，请返回后重新检查。");}});}
        });
    }
    private static String position(AiEvidence.Unit unit){return unit.kind.equals("policy")?"第 "+unit.page+" 页 · 第 "+unit.paragraph+" 段":unit.paragraph==0?"通知标题":"正文第 "+unit.paragraph+" 段";}
    private void showPassage(AiEvidence.Unit unit,SummaryInput source,AiEvidence.Snapshot context){
        if(busy){status.setText("正在生成，请完成或取消后再展开依据。");return;}
        final long token=sequence;
        task=worker.submit(()->{
            try{
                requireSource(source);if(!library.isCurrent(context.libraryRevision))throw new IOException("STALE");
                main.post(()->{if(active(token)&&library.isCurrent(context.libraryRevision))displayPassage(unit);});
            }catch(Exception changed){main.post(()->{if(active(token)){
                dismissSourceDialogs();results.removeAllViews();status.setText("通知或政策资料已变化，请重新查看摘要。");reload();
            }});}
        });
    }
    private void displayPassage(AiEvidence.Unit unit){
        if(unit.start<0||unit.end>unit.sourceText.length()||unit.end<=unit.start)return;
        SpannableString marked=new SpannableString(unit.sourceText);
        marked.setSpan(new BackgroundColorSpan(0xffffeb99),unit.start,unit.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(12),dp(20),dp(12));
        note(page,unit.title+"\n"+position(unit)+"\n"+unit.meta,13,ReaderUi.MUTED);
        TextView body=note(page,"",16,ReaderUi.INK);body.setText(marked);body.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("原文依据（高亮）").setView(scroll).setPositiveButton("返回摘要",null)
                .setNeutralButton("返回通知正文",(d,w)->finish()).create();
        if(openEvidenceDialog!=null)openEvidenceDialog.dismiss();openEvidenceDialog=dialog;
        dialog.setOnDismissListener(d->{if(openEvidenceDialog==dialog)openEvidenceDialog=null;});
        dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);body.post(()->{if(body.getLayout()!=null)scroll.scrollTo(0,body.getTop()+body.getLayout().getLineTop(body.getLayout().getLineForOffset(unit.start)));});});dialog.show();
    }
    private TextView note(LinearLayout page,String value,int size,int color){TextView text=ReaderUi.text(this,value,size,color);text.setLineSpacing(dp(3),1);text.setPadding(0,dp(8),0,dp(8));page.addView(text);return text;}
    private Button button(LinearLayout page,String value,Runnable action){Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());page.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;}
    private int dp(int value){return ReaderUi.dp(this,value);}
}
