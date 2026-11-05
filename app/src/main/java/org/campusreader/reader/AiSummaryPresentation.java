package org.campusreader.reader;
import org.campusreader.auth.ScreenPrivacy;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.campusreader.notices.*;

/** Cache-only reader integration. Creating or opening a preview never sends an API request. */
public final class AiSummaryPresentation {
    private static final ExecutorService READERS=Executors.newFixedThreadPool(2);
    private static final Map<String,Memo> MEMO=new LinkedHashMap<String,Memo>(128,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,Memo> e){return size()>128;}};
    private static final class Memo{final Cached cached;final long at=System.currentTimeMillis();Memo(Cached cached){this.cached=cached;}}
    static void invalidateCache(){synchronized(MEMO){MEMO.clear();}}
    public static final class Cached {
        public final String snippet;public final AiBriefSummary summary;public final AiGroundedSummary legacy;
        public final AiEvidence.Snapshot context;public final SummaryInput source;public final long generatedAtMillis,settingsRevision;public final boolean coreOnly;
        Cached(AiBriefSummary summary,AiGroundedSummary legacy,AiEvidence.Snapshot context,SummaryInput source,long at,long revision){
            this(summary,legacy,context,source,at,revision,false);
        }
        Cached(AiBriefSummary summary,AiGroundedSummary legacy,AiEvidence.Snapshot context,SummaryInput source,long at,long revision,boolean coreOnly){
            this.summary=summary;this.legacy=legacy;this.context=context;this.source=source;generatedAtMillis=at;settingsRevision=revision;this.coreOnly=coreOnly;
            if(summary!=null)snippet=summary.snippet();else{String text="";for(AiGroundedSummary.Field f:legacy.fields)if(!f.evidence.isEmpty()){text=f.evidence.get(0).text;break;}snippet=text.length()>100?text.substring(0,100)+"…":text;}
        }
    }
    private AiSummaryPresentation(){}
    public static Cached loadCached(Context c,String profile,NoticeKey key){
        if(!AiProfile.owns(c,profile))return null;
        try(CampusNoticeStore store=new CampusNoticeStore(c,profile)){
            SummaryInput input=store.summaryInput(key);if(input==null)return null;
            AiSettings settings=new AiSettings(c,profile);AiSettings.Config config=settings.cacheConfig();
            String memoKey=profile+"|"+key.source+"|"+key.stableId+"|"+input.contentHash+"|"+input.cacheGeneration+"|"+config.revision;
            synchronized(MEMO){Memo memo=MEMO.get(memoKey);if(memo!=null&&(memo.cached!=null||System.currentTimeMillis()-memo.at<3000))return memo.cached;}
            // Most list rows have no generated result: do not scan the policy library for them.
            boolean full=AiCoreCache.hasFull(store,key);SummaryRecord core=AiCoreCache.find(store,key,input,config);
            Cached cached=null;
            if(full){
                PolicyLibrary.Snapshot policies=new PolicyLibrary(c,profile).snapshot();
                AiEvidence.Snapshot current=AiAutoSummaries.context(input,policies,settings.automaticPolicies());
                SummaryRecord record=store.findSummary(key,input.contentHash,AiAutoSummaries.cacheHash(current,config));
                if(record==null){AiEvidence.Snapshot other=AiAutoSummaries.context(input,policies,!settings.automaticPolicies());SummaryRecord previous=store.findSummary(key,input.contentHash,AiAutoSummaries.cacheHash(other,config));if(previous!=null){current=other;record=previous;}}
                if(record!=null)try{cached=new Cached(AiBriefSummary.parseCached(record.resultJson,current),null,current,input,record.generatedAtMillis,config.revision);}catch(org.json.JSONException invalid){}
                if(cached==null){
                    List<String> tags=new ArrayList<>();for(NoticeTag tag:store.listTags())tags.add(tag.name);
                    AiEvidence.Snapshot old=AiEvidence.build(input,policies,tags);record=store.findSummary(key,input.contentHash,old.cacheHash(config.configHash));
                    if(record!=null)try{cached=new Cached(null,AiGroundedSummary.parseCached(record.resultJson,old),old,input,record.generatedAtMillis,config.revision);}catch(org.json.JSONException invalid){}
                }
            }
            if(cached==null&&core!=null){
                AiEvidence.Snapshot notice=AiAutoSummaries.context(input,new PolicyLibrary.Snapshot(0,Collections.emptyList()),false);
                cached=new Cached(AiBriefSummary.parseCached(core.resultJson,notice),null,notice,input,core.generatedAtMillis,config.revision,true);
            }
            if(!AiProfile.owns(c,profile))return null;
            synchronized(MEMO){MEMO.put(memoKey,new Memo(cached));}return cached;
        }catch(Exception unavailable){return null;}
    }
    public static View view(Activity activity,String profile,NoticeKey key){return new Preview(activity,profile,key);}
    private static final class Preview extends LinearLayout {
        final Activity activity;final String profile;final NoticeKey key;final Context app;final Handler main=new Handler(Looper.getMainLooper());
        long generation;boolean registered,attached;Future<?> load;
        final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){reload();}};
        final Runnable accountCheck=new Runnable(){@Override public void run(){if(!attached)return;if(!AiProfile.owns(activity,profile)){generation++;removeAllViews();note(Preview.this,"账号已变化，请重新打开预览。",14,ReaderUi.MUTED);return;}main.postDelayed(this,1000);}};
        Preview(Activity activity,String profile,NoticeKey key){super(activity);this.activity=activity;this.profile=profile;this.key=key;app=activity.getApplicationContext();setOrientation(VERTICAL);setPadding(dp(activity,16),dp(activity,8),dp(activity,16),dp(activity,12));note(this,"正在读取本机摘要…",14,ReaderUi.MUTED);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;if(!registered){IntentFilter filter=new IntentFilter(AiAutoSummaries.ACTION_UPDATED);if(Build.VERSION.SDK_INT>=33)app.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else app.registerReceiver(receiver,filter);registered=true;}reload();main.post(accountCheck);}
        @Override protected void onDetachedFromWindow(){attached=false;generation++;if(registered){app.unregisterReceiver(receiver);registered=false;}if(load!=null)load.cancel(true);main.removeCallbacks(accountCheck);super.onDetachedFromWindow();}
        void reload(){if(!attached)return;final long token=++generation;if(load!=null)load.cancel(true);load=READERS.submit(()->{Cached cached=loadCached(activity,profile,key);main.post(()->{
            if(!attached||generation!=token||activity.isDestroyed())return;removeAllViews();if(!AiProfile.owns(activity,profile)){note(this,"账号已变化，请重新打开预览。",14,ReaderUi.MUTED);return;}
            LinearLayout content=new LinearLayout(activity);content.setOrientation(VERTICAL);addView(content,new LinearLayout.LayoutParams(-1,-2));
            if(cached==null){note(content,new AiSettings(activity,profile).automaticEnabled()?AiAutoSummaries.status(activity,profile,key):"暂无摘要，可在 AI 服务设置开启自动摘要。",14,ReaderUi.MUTED);return;}
            render(content,activity,profile,key,cached);
        });});}
    }
    static void render(LinearLayout page,Activity activity,String profile,NoticeKey key,Cached cached){
        note(page,"AI 摘要",13,ReaderUi.BLUE);
        if(cached.coreOnly)note(page,"通知概要已保留；政策参考需重新生成后核对。",12,ReaderUi.MUTED);
        LinkedHashMap<String,Integer> numbers=new LinkedHashMap<>();ArrayList<AiBriefSummary.Reference> ordered=new ArrayList<>();
        if(cached.summary!=null){
            addClaim(page,activity,profile,key,cached,cached.summary.overview,numbers,ordered,17);
            for(AiBriefSummary.Claim claim:cached.summary.points){note(page,claim.kind,12,ReaderUi.MUTED);addClaim(page,activity,profile,key,cached,claim,numbers,ordered,15);}
            if(!cached.summary.tags.isEmpty())note(page,String.join("  ·  ",cached.summary.tags),12,ReaderUi.BLUE);
            if(!cached.summary.links.isEmpty()){
                Button refs=new Button(activity);refs.setText("相关政策参考 · "+cached.summary.links.size()+" 项 ▾");refs.setAllCaps(false);page.addView(refs);
                LinearLayout policy=new LinearLayout(activity);policy.setOrientation(LinearLayout.VERTICAL);policy.setVisibility(View.GONE);page.addView(policy);
                refs.setOnClickListener(v->policy.setVisibility(policy.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
                for(AiGroundedSummary.Link link:cached.summary.links){TextView item=note(policy,link.policy.title+" · 适用性待核对",14,ReaderUi.BLUE);item.setMinHeight(dp(activity,48));item.setOnClickListener(v->showEvidence(activity,profile,key,cached,new AiBriefSummary.Reference(link.policy,link.policy.text)));}
            }
            if(cached.summary.discarded>0)note(page,"部分表述未通过校验，已隐藏或改为原文摘录。",12,ReaderUi.MUTED);
        }else{
            // Legacy caches are quoted, not silently presented as newly generated short prose.
            note(page,"原文摘录（旧版缓存）",12,ReaderUi.MUTED);
            for(AiGroundedSummary.Field field:cached.legacy.fields)if(!field.evidence.isEmpty()){
                AiEvidence.Unit unit=field.evidence.get(0);String q=unit.text.length()>180?unit.text.substring(0,180):unit.text;
                addClaim(page,activity,profile,key,cached,new AiBriefSummary.Claim(field.label,q,new ArrayList<>(Collections.singletonList(new AiBriefSummary.Reference(unit,q))),true),numbers,ordered,15);
            }
        }
        note(page,"点击右上角数字核对依据 · AI 可能遗漏或误解条件",12,ReaderUi.MUTED);
        final Runnable check=new Runnable(){@Override public void run(){
            if(!page.isAttachedToWindow())return;
            if(!current(activity,profile,key,cached)){page.removeAllViews();note(page,"资料或账号已变化，请重新打开预览。",14,ReaderUi.MUTED);return;}
            page.postDelayed(this,1500);
        }};
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){page.postDelayed(check,1500);}public void onViewDetachedFromWindow(View v){page.removeCallbacks(check);}});
        if(page.isAttachedToWindow())page.postDelayed(check,1500);
    }
    private static void addClaim(LinearLayout page,Activity activity,String profile,NoticeKey key,Cached cached,AiBriefSummary.Claim claim,Map<String,Integer> numbers,List<AiBriefSummary.Reference> ordered,int size){
        SpannableStringBuilder text=new SpannableStringBuilder(claim.text);
        for(AiBriefSummary.Reference reference:claim.references){String identity=reference.unit.id+"\u0000"+reference.quote;Integer number=numbers.get(identity);if(number==null){number=ordered.size()+1;numbers.put(identity,number);ordered.add(reference);}
            int from=text.length();text.append(" ").append(Integer.toString(number)).append(" ");int to=text.length();
            text.setSpan(new SuperscriptSpan(),from,to,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);text.setSpan(new RelativeSizeSpan(.78f),from,to,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ClickableSpan(){@Override public void onClick(View widget){showEvidence(activity,profile,key,cached,reference);}},from,to,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView view=note(page,"",size,ReaderUi.INK);view.setText(text);view.setMovementMethod(LinkMovementMethod.getInstance());view.setHighlightColor(0xffe7efff);
        if(claim.excerpt)note(page,"原文摘录",11,ReaderUi.MUTED);
    }
    private static boolean current(Activity activity,String profile,NoticeKey key,Cached cached){
        if(activity.isDestroyed()||!AiProfile.owns(activity,profile)||new AiSettings(activity,profile).revision()!=cached.settingsRevision)return false;
        try(CampusNoticeStore store=new CampusNoticeStore(activity,profile)){SummaryInput now=store.summaryInput(key);if(now==null||now.cacheGeneration!=cached.source.cacheGeneration||!now.contentHash.equals(cached.source.contentHash))return false;
            return cached.context.policyCount()==0||new PolicyLibrary(activity,profile).isCurrent(cached.context.libraryRevision);
        }catch(RuntimeException unavailable){return false;}
    }
    private static void showEvidence(Activity activity,String profile,NoticeKey key,Cached cached,AiBriefSummary.Reference reference){
        if(!current(activity,profile,key,cached)){Toast.makeText(activity,"资料已变化，请重新打开摘要",Toast.LENGTH_SHORT).show();return;}
        AiEvidence.Unit unit=reference.unit;LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(activity,20),dp(activity,12),dp(activity,20),dp(activity,20));
        note(content,unit.kind.equals("policy")?"政策参考 · 适用性待核对":"通知原文",13,ReaderUi.BLUE);
        note(content,unit.title,17,ReaderUi.INK);note(content,(unit.kind.equals("policy")?"第 "+unit.page+" 页 · ":"")+"第 "+unit.paragraph+" 段\n"+unit.meta,12,ReaderUi.MUTED);
        SpannableString passage=new SpannableString(unit.sourceText);if(reference.start>=0&&reference.end<=passage.length())passage.setSpan(new BackgroundColorSpan(0xffffeb99),reference.start,reference.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView body=note(content,"",15,ReaderUi.INK);body.setText(passage);body.setTextIsSelectable(true);ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("原文依据").setView(scroll).setPositiveButton("返回预览",null).create();
        Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
            public void onActivityStopped(Activity a){if(a==activity)dialog.dismiss();}
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){if(a==activity)dialog.dismiss();}
        };
        dialog.setOnShowListener(d->{Window window=dialog.getWindow();if(window!=null){ScreenPrivacy.protect(window);window.setGravity(Gravity.BOTTOM);window.setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.68));}body.post(()->{if(body.getLayout()!=null)scroll.scrollTo(0,body.getTop()+body.getLayout().getLineTop(body.getLayout().getLineForOffset(Math.min(reference.start,body.length()))));});});
        Runnable check=new Runnable(){@Override public void run(){if(!dialog.isShowing())return;if(!current(activity,profile,key,cached)){dialog.dismiss();return;}content.postDelayed(this,1000);}};
        dialog.setOnDismissListener(d->{content.removeCallbacks(check);activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);});activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);dialog.show();content.postDelayed(check,1000);
    }
    private static TextView note(LinearLayout parent,String value,int size,int color){TextView t=ReaderUi.text(parent.getContext(),value,size,color);t.setPadding(0,dp(parent.getContext(),6),0,dp(parent.getContext(),6));t.setLineSpacing(dp(parent.getContext(),3),1);parent.addView(t);return t;}
    private static int dp(Context c,int value){return ReaderUi.dp(c,value);}
}
