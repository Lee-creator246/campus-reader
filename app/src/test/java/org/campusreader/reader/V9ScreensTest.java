package org.campusreader.reader;

import android.app.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.campusreader.config.CampusSite;
import org.campusreader.notices.*;
import org.campusreader.portal.CampusPortalParser;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Synthetic native renders and a real reader long-press path; no network or API calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AiUiTest.ShortPathApplication.class,qualifiers="w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class V9ScreensTest {
    private AiUiTest.ShortPathApplication app;
    @Before public void setup(){app=(AiUiTest.ShortPathApplication)RuntimeEnvironment.getApplication();app.getSharedPreferences("portal-account",0).edit().clear().commit();new ReaderPreferences(app).setBackgroundEnabled(false);new ReaderPreferences(app).setRefreshOnOpen(false);}
    @After public void after(){PortalAccess.invalidate();app.dispose();}
    private void render(Activity a,String name)throws Exception{
        View view=a.findViewById(android.R.id.content);view.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));view.layout(0,0,360,800);
        Bitmap bitmap=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File directory=new File("build/verification/ui-v9");assertTrue(directory.isDirectory()||directory.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
    }
    @Test public void firstScreenShowsPortalCredentialsWithoutCampusConfigurationWizard()throws Exception{
        ActivityController<LoginActivity> controller=Robolectric.buildActivity(LoginActivity.class).setup();LoginActivity activity=controller.get();List<String> texts=new ArrayList<>();collect(activity.findViewById(android.R.id.content),texts);
        assertTrue(texts.contains("北邮人信息"));assertTrue(texts.contains("登录"));assertFalse(texts.contains("学校与校园配置"));render(activity,"login");controller.pause().stop().destroy();
    }
    @Test public void nativeReaderShowsTopicsAndLongPressDoesNotMarkNoticeRead()throws Exception{
        CampusSite.install(CampusSite.create("example.org","https://vpn.example.org","http://portal.example.org/list","https://login.example.org/login"));app.getSharedPreferences("portal-account",0).edit().putString("account:"+CampusSite.scopeId(),"synthetic-student").commit();String profile=PortalAccess.currentProfile(app);PreferenceProfile.skip(app,profile);
        NoticeKey key=CampusNoticeStore.summaryKey(CampusPortalParser.SOURCE,"synthetic-preview");
        try(CampusNoticeStore store=new CampusNoticeStore(app,profile)){
            store.upsert(Collections.singletonList(new CampusNotice(key,"关于校园志愿服务报名的通知",System.currentTimeMillis(),"<p>本次为合成测试内容，请通过线上渠道提交报名材料。</p>","http://portal.example.org/info/preview",Collections.emptyList(),Collections.emptyList())),System.currentTimeMillis());
            SummaryInput input=store.summaryInput(key);AiEvidence.Snapshot source=AiEvidence.build(input,new PolicyLibrary.Snapshot(0,Collections.emptyList()),Collections.emptyList());
            org.json.JSONObject reference=new org.json.JSONObject().put("id","N2").put("quote",source.unit("N2").text);
            org.json.JSONObject raw=new org.json.JSONObject().put("schema",3).put("overview",new org.json.JSONObject().put("text","请通过线上渠道提交报名材料。").put("refs",new org.json.JSONArray().put(reference))).put("points",new org.json.JSONArray()).put("tags",new org.json.JSONArray().put("志愿实践")).put("policy_links",new org.json.JSONArray());
            assertTrue(AiCoreCache.save(store,key,input,new AiSettings(app,profile).cacheConfig(),AiBriefSummary.parse(raw.toString(),source),System.currentTimeMillis()));
        }
        ActivityController<ReaderActivity> controller=Robolectric.buildActivity(ReaderActivity.class).setup();ReaderActivity activity=controller.get();
        java.lang.reflect.Field worker=ReaderActivity.class.getDeclaredField("worker");worker.setAccessible(true);
        for(int i=0;i<4;i++){((ExecutorService)worker.get(activity)).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        java.lang.reflect.Field listField=ReaderActivity.class.getDeclaredField("list");listField.setAccessible(true);ListView list=(ListView)listField.get(activity);assertEquals(1,list.getAdapter().getCount()-list.getFooterViewsCount());
        List<String> texts=new ArrayList<>();collect(activity.findViewById(android.R.id.content),texts);assertTrue(texts.contains("为你推荐"));assertTrue(texts.contains("志愿实践"));render(activity,"reader");
        assertTrue(list.getOnItemLongClickListener().onItemLongClick(list,list.getChildAt(0),0,0));Shadows.shadowOf(Looper.getMainLooper()).idle();
        try(CampusNoticeStore store=new CampusNoticeStore(app,profile)){assertFalse(store.find(key).read);assertFalse(store.find(key).later);}
        java.lang.reflect.Field preview=ReaderActivity.class.getDeclaredField("previewDialog");preview.setAccessible(true);Dialog dialog=(Dialog)preview.get(activity);assertTrue(dialog.isShowing());
        awaitPreview(dialog.findViewById(android.R.id.content));Shadows.shadowOf(Looper.getMainLooper()).idle();
        List<TextView> previewTexts=new ArrayList<>();collectViews(dialog.findViewById(android.R.id.content),previewTexts);TextView citation=null;
        for(TextView text:previewTexts)if(text.getText() instanceof android.text.Spanned&&((android.text.Spanned)text.getText()).getSpans(0,text.length(),android.text.style.ClickableSpan.class).length>0){citation=text;break;}
        assertNotNull("The preview must expose a clickable source number",citation);
        android.text.Spanned marked=(android.text.Spanned)citation.getText();marked.getSpans(0,marked.length(),android.text.style.ClickableSpan.class)[0].onClick(citation);
        Dialog evidence=org.robolectric.shadows.ShadowDialog.getLatestDialog();assertNotSame(dialog,evidence);assertTrue(evidence.isShowing());
        try(CampusNoticeStore store=new CampusNoticeStore(app,profile)){assertFalse(store.find(key).read);}
        controller.pause().stop().destroy();
    }
    private void collect(View view,List<String> out){if(view instanceof TextView)out.add(((TextView)view).getText().toString());if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),out);}
    private void collectViews(View view,List<TextView> out){if(view instanceof TextView)out.add((TextView)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectViews(((ViewGroup)view).getChildAt(i),out);}
    private void awaitPreview(View view)throws Exception{if(view.getClass().getSimpleName().equals("Preview")){java.lang.reflect.Field field=view.getClass().getDeclaredField("load");field.setAccessible(true);Future<?> load=(Future<?>)field.get(view);if(load!=null)load.get(10,TimeUnit.SECONDS);}if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)awaitPreview(((ViewGroup)view).getChildAt(i));}
}
