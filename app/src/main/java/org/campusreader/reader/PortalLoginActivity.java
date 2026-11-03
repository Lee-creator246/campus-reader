package org.campusreader.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.concurrent.*;
import org.campusreader.auth.CampusApplication;
import org.campusreader.config.CampusSite;
import org.campusreader.portal.CampusPortalParser;
import org.json.*;

/** Foreground-only SSO recovery with a locally implemented, narrowly checked CAS adapter. */
public class PortalLoginActivity extends Activity {
    public static final String EXTRA_PROFILE="portal_profile",EXTRA_AUTOMATIC="portal_automatic",EXTRA_MANAGE="portal_manage";
    private final java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String profile,site,account;
    private boolean automatic,manage,resumed,closed,submitting,submitted,checking,owned;
    private long generation;
    private volatile long attempt;

    private SyncGate.Lease lease;
    private ReaderPreferences prefs;
    private PortalCredentialStore vault;
    private WebView web;
    private TextView status,target;
    private LinearLayout nativeForm;
    private ScrollView formScroll;
    private EditText username,password;
    private CheckBox remember;
    private Button submit;
    private JSONObject form;
    private PortalCredentialStore.Credentials pending;
    private boolean pendingRemember;
    private final Runnable deadline=()->{if(!closed){setStatus("门户恢复超时，可返回缓存后手动重试。");if(automatic)finish();}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        automatic=getIntent().getBooleanExtra(EXTRA_AUTOMATIC,false);manage=getIntent().getBooleanExtra(EXTRA_MANAGE,false);
        profile=getIntent().getStringExtra(EXTRA_PROFILE);site=CampusSite.scopeId();account=CampusApplication.auth().getProfileId();
        if(profile==null||!profile.matches("campus:[a-f0-9]{64}")||!profile.equals(MediaSafety.readerProfile(account))||!CampusApplication.auth().isAccessReady()){finish();return;}
        prefs=new ReaderPreferences(this);generation=prefs.getPortalGeneration();vault=new PortalCredentialStore(this,profile);
        if(!PortalSessionCoordinator.acquire(this,profile,automatic)){finish();return;}owned=true;
        lease=SyncGate.tryAcquire(profile);if(lease==null){finish();return;}
        build();handler.postDelayed(deadline,automatic?30_000L:180_000L);
    }
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);setContentView(root);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(12),dp(8),dp(12),0);root.addView(bar);
        Button back=new Button(this);back.setText("返回");back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(dp(72),dp(48)));
        TextView title=text(manage?"门户账号":automatic?"恢复门户连接":"登录信息门户",22);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        status=text("先检查已有登录状态，缓存仍可阅读。",14);status.setPadding(dp(20),dp(8),dp(20),dp(12));root.addView(status);
        nativeForm=new LinearLayout(this);nativeForm.setOrientation(LinearLayout.VERTICAL);nativeForm.setPadding(dp(20),0,dp(20),dp(12));nativeForm.setVisibility(View.GONE);
        formScroll=new ScrollView(this);formScroll.setFillViewport(false);formScroll.setVisibility(View.GONE);formScroll.addView(nativeForm);root.addView(formScroll,new LinearLayout.LayoutParams(-1,0,1));
        target=text("",13);nativeForm.addView(target);
        username=field("学工号",InputType.TYPE_CLASS_TEXT);username.setText(account);password=field("门户密码",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        remember=new CheckBox(this);remember.setText("记住密码，仅加密保存在这台手机");remember.setTextSize(14);remember.setChecked(true);nativeForm.addView(remember);
        submit=new Button(this);submit.setText("保存并登录");nativeForm.addView(submit,new LinearLayout.LayoutParams(-1,dp(48)));submit.setOnClickListener(v->submit(false));
        remember.setOnCheckedChangeListener((b,checked)->submit.setText(checked?"保存并登录":"仅本次登录"));
        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(12),0,dp(12),0);root.addView(actions);
        Button forget=new Button(this);forget.setText("删除已保存密码");actions.addView(forget,new LinearLayout.LayoutParams(0,dp(48),1));forget.setOnClickListener(v->{cancelPending();password.setText("");try{vault.clear();PortalSessionCoordinator.pause(this,profile);setStatus("已删除本机保存的密码。现有网页登录仍可使用。");}catch(Exception error){setStatus("密码文件已清理；系统密钥清理未完成，请重试。");}});
        Button done=new Button(this);done.setText("检查并返回通知");actions.addView(done,new LinearLayout.LayoutParams(0,dp(48),1));done.setOnClickListener(v->verify(true));
        web=new WebView(this);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setUserAgentString(ReaderHttp.USER_AGENT);s.setSaveFormData(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);web.setSaveEnabled(false);
        if(Build.VERSION.SDK_INT>=26)web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        // A manual edit/navigation is a new attempt. It cannot authorize saving an older native password.
        web.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)cancelPending();return false;});
        username.setOnFocusChangeListener((v,focus)->{if(focus)cancelPending();});
        password.setOnFocusChangeListener((v,focus)->{if(focus)cancelPending();});
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->{
            if(closed)return;int available=root.getHeight();int maximum=Math.max(dp(112),available/2);
            if(nativeForm.getVisibility()==View.VISIBLE&&formScroll.getLayoutParams().height!=maximum){formScroll.getLayoutParams().height=maximum;formScroll.requestLayout();}
            else if(nativeForm.getVisibility()!=View.VISIBLE&&formScroll.getLayoutParams().height!=0){formScroll.getLayoutParams().height=0;formScroll.requestLayout();}
        });
        for(Button button:new Button[]{back,submit,forget,done}){button.setTextColor(ReaderUi.BLUE);button.setTextSize(14);ReaderUi.selectable(button);}
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return navigate(request.getUrl().toString());}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return navigate(url);}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){return CampusSite.allowsLoginUrl(request.getUrl().toString())?null:new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){form=null;nativeForm.setVisibility(View.GONE);formScroll.setVisibility(View.GONE);}
            @Override public void onPageFinished(WebView v,String url){if(!live())return;if(CampusSite.allowsPortalUrl(url)){verify(false);return;}probe();}
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError error){h.cancel();pending=null;setStatus("认证页面证书验证失败，已停止登录。");if(automatic)finish();}
            @Override public void onReceivedError(WebView v,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()){pending=null;setStatus("登录页面加载失败，请检查校园连接后重试。");if(automatic)finish();}}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest request,WebResourceResponse response){if(request.isForMainFrame()&&response.getStatusCode()>=400){pending=null;setStatus("认证服务暂不可用，请稍后重试。");if(automatic)finish();}}
        });
    }
    private boolean navigate(String url){
        if(!live())return true;
        String upgraded=upgradeLoginUrl(url);
        if(!upgraded.equals(url)){web.loadUrl(upgraded);return true;}
        if(CampusSite.allowsLoginUrl(url))return false;
        pending=null;setStatus("已停止跳转：地址超出当前学校认证范围。");if(automatic)finish();return true;
    }
    static String upgradeLoginUrl(String url){
        try{URI u=URI.create(url);if("http".equalsIgnoreCase(u.getScheme())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==80)
                    &&u.getPath()!=null&&u.getPath().endsWith("/login")){
                URI next=new URI("https",null,u.getHost(),-1,u.getPath(),u.getQuery(),null);
                if(CampusSite.allowsLoginUrl(next.toString()))return next.toString();
            }}catch(Exception ignored){}return url;
    }
    private void probe(){
        if(!live()||submitting)return;
        web.evaluateJavascript(PortalLoginPolicy.PROBE,value->{if(!live())return;try{
            Object decoded=new JSONTokener(value).nextValue();JSONObject f=new JSONObject(decoded instanceof String?(String)decoded:"{}");
            String page=f.optString("page"),action=f.optString("action");
            if(!page.equals(web.getUrl())||!CampusSite.allowsLoginUrl(page)||!PortalLoginPolicy.accepts(page,action,f.optString("id"),f.optString("method"),f.optInt("users"),f.optInt("passwords"),f.optBoolean("execution"),f.optBoolean("event"))){
                if(pending!=null)cancelPending();setStatus("请在学校网页使用当前校园连接的学工号登录；此页面暂不支持保存密码。切换账号请先退出校园连接。");if(automatic)finish();return;
            }
            if(f.optBoolean("challenge")||f.optBoolean("error")){
                if(pending!=null)cancelPending();if(submitted)PortalSessionCoordinator.pause(this,profile);
                setStatus("学校要求验证码、额外验证或更正登录信息，请在网页完成。");if(automatic)finish();return;
            }
            if(submitted&&pending!=null)cancelPending(); // Returning to a form means the earlier submit is not verified.
            form=f;target.setText("密码仅提交至："+PortalLoginPolicy.binding(page));
            if(automatic){
                if(submitted){PortalSessionCoordinator.pause(this,profile);finish();return;}
                PortalCredentialStore.Credentials saved=vault.load(page,account);
                if(saved==null){finish();return;}username.setText(saved.account);password.setText(saved.password);submit(true);
            }else{nativeForm.setVisibility(View.VISIBLE);formScroll.setVisibility(View.VISIBLE);setStatus("可使用上方表单登录并保存密码，也可直接使用学校网页。");}
        }catch(Exception unavailable){pending=null;setStatus("已保存凭据无法使用，请手动登录或删除后重新保存。");if(automatic){PortalSessionCoordinator.pause(this,profile);finish();}}});
    }
    private void submit(boolean fromSaved){
        if(!live()||form==null||submitting||(automatic&&submitted))return;
        try{
            String user=PortalLoginPolicy.account(username.getText().toString(),account),secret=PortalLoginPolicy.password(password.getText().toString());
            String page=form.getString("page"),action=form.getString("action"),id=form.getString("id");
            if(!page.equals(web.getUrl())||!CampusSite.allowsLoginUrl(page)||!PortalLoginPolicy.accepts(page,action,id,form.getString("method"),form.getInt("users"),form.getInt("passwords"),form.getBoolean("execution"),form.getBoolean("event")))throw new IllegalArgumentException("认证页面已变化，请等待重新识别。");
            attempt++;pending=new PortalCredentialStore.Credentials(user,secret,PortalLoginPolicy.binding(page));pendingRemember=fromSaved||remember.isChecked();submitting=true;submitted=true;submit.setEnabled(false);
            String script=PortalLoginPolicy.submitScript(page,action,id,user,secret);password.setText("");setStatus("正在登录并验证通知访问…");
            web.evaluateJavascript(script,result->{submitting=false;submit.setEnabled(true);if(!live())return;if(!"true".equals(result)){pending=null;setStatus("页面需要手动验证，请在下方网页继续。");if(automatic)finish();}});
        }catch(Exception invalid){setStatus(invalid instanceof IllegalArgumentException?invalid.getMessage():"认证页面已变化，请手动登录。");}
    }
    private void verify(boolean explicit){
        if(!live()||checking)return;checking=true;final long requestAttempt=attempt;
        worker.execute(()->{boolean success=false;try{ReaderHttp.Response r=ReaderHttp.get(CampusSite.portalListUrl(),5*1024*1024,true,()->cancelled.get()||prefs.getPortalGeneration()!=generation||attempt!=requestAttempt);new CampusPortalParser().parseList(r.url,r.status,r.text());success=true;}catch(Exception ignored){}final boolean valid=success;
            runOnUiThread(()->{checking=false;if(!live()||requestAttempt!=attempt)return;if(!valid){if(explicit)setStatus("门户尚未验证通过，请先完成学校网页登录。");else probe();return;}
                if(!prefs.markPortalSessionIfCurrent(profile,generation))return;
                CookieManager.getInstance().flush();CampusApplication.auth().clearPortalAuthenticationRequired();PortalSessionCoordinator.success(this,profile);
                if(pending!=null){try{if(pendingRemember)vault.save(pending.account,pending.password,pending.binding);else vault.clear();}catch(Exception failure){setStatus("登录已成功，但系统安全存储不可用，密码未保存。");android.widget.Toast.makeText(this,"登录成功，密码未保存",Toast.LENGTH_LONG).show();}finally{pending=null;}}
                if(manage&&!explicit){setStatus("门户访问有效。可删除保存的密码，或返回通知。");return;}
                setResult(RESULT_OK,new Intent().putExtra(EXTRA_PROFILE,profile));finish();
            });
        });
    }
    private boolean live(){return !closed&&resumed&&!isFinishing()&&site.equals(CampusSite.scopeId())&&profile.equals(MediaSafety.readerProfile(CampusApplication.auth().getProfileId()))&&CampusApplication.auth().isAccessReady()&&prefs.getPortalGeneration()==generation;}
    @Override protected void onResume(){super.onResume();resumed=true;if(web!=null&&web.getUrl()==null&&live())web.loadUrl(upgradeLoginUrl(CampusSite.loginUrl()));}
    @Override protected void onPause(){cancelled.set(true);resumed=false;cancelPending();if(web!=null)web.stopLoading();super.onPause();if(!isFinishing())finish();}
    @Override protected void onDestroy(){cancelled.set(true);closed=true;pending=null;handler.removeCallbacksAndMessages(null);worker.shutdownNow();if(password!=null)password.setText("");if(web!=null){web.stopLoading();web.destroy();web=null;}if(lease!=null)lease.close();if(owned)PortalSessionCoordinator.finished(profile);super.onDestroy();}
    private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(ReaderUi.INK);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private EditText field(String hint,int type){EditText v=new EditText(this);v.setHint(hint);v.setTextSize(16);v.setSingleLine(true);v.setInputType(type);v.setSaveEnabled(false);if(Build.VERSION.SDK_INT>=26)v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);nativeForm.addView(v,new LinearLayout.LayoutParams(-1,dp(52)));return v;}
    private void setStatus(String value){if(status!=null)status.setText(value);}
    private void cancelPending(){pending=null;attempt++;}
}
