package org.campusreader.reader;
import org.campusreader.auth.ScreenPrivacy;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.campusreader.auth.CampusApplication;
import org.campusreader.auth.CampusAuthActivity;
import org.campusreader.config.CampusSite;
import org.campusreader.portal.CampusPortalParser;
import java.util.concurrent.*;

/** Single-campus entry point: portal account first, VPN only after a failed direct probe. */
public final class LoginActivity extends Activity {
    public static final String EXTRA_MANAGE="login_manage";
    private static final int PORTAL=91,VPN=92;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private EditText account,password;private CheckBox remember;private Button login;private TextView status;
    private boolean busy,closed,attempted,manage;private int generation;private boolean waitingVpn,vpnFallbackTried;
    private String pendingAccount,pendingPassword;private boolean pendingRemember;private long pendingExpires;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ScreenPrivacy.protect(getWindow());
        getWindow().setStatusBarColor(ReaderUi.SURFACE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        manage=getIntent().getBooleanExtra(EXTRA_MANAGE,false);build();
    }
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER_VERTICAL);root.setPadding(dp(28),dp(40),dp(28),dp(32));root.setBackgroundColor(ReaderUi.SURFACE);scroll.addView(root);setContentView(scroll);
        TextView title=text("北邮人信息",32);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);root.addView(title);
        TextView subtitle=text("登录信息门户，发现与你有关的校园消息",15);subtitle.setTextColor(ReaderUi.MUTED);add(root,subtitle,12);
        account=new EditText(this);account.setHint("学工号");account.setSingleLine(true);account.setSaveEnabled(false);account.setText(PortalAccess.account(this));add(root,account,36);
        password=new EditText(this);password.setHint("信息门户密码");password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setSaveEnabled(false);add(root,password,12);
        if(Build.VERSION.SDK_INT>=26){account.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);}
        remember=new CheckBox(this);remember.setChecked(true);remember.setText("保存密码并自动登录，仅加密保存在本机");remember.setTextSize(13);add(root,remember,12);
        login=new Button(this);login.setText("登录");login.setAllCaps(false);login.setTextColor(android.graphics.Color.WHITE);login.setMinHeight(dp(52));android.graphics.drawable.GradientDrawable primary=new android.graphics.drawable.GradientDrawable();primary.setColor(ReaderUi.BLUE);primary.setCornerRadius(dp(12));login.setBackground(primary);add(root,login,24);login.setOnClickListener(v->begin(false));
        status=text("优先直连门户，校外按需连接 VPN",14);status.setTextColor(ReaderUi.MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(root,status,16);
        Button cached=new Button(this);cached.setText("阅读本机缓存");cached.setAllCaps(false);cached.setTextColor(ReaderUi.BLUE);add(root,cached,12);cached.setOnClickListener(v->{if(PortalAccess.account(this).isEmpty()){status.setText("尚无门户账号缓存，请先完成首次登录。");return;}clearPending();enterReader();});
        Button vpn=new Button(this);vpn.setText("校外 VPN / 连接设置");vpn.setAllCaps(false);vpn.setTextColor(ReaderUi.MUTED);add(root,vpn,4);vpn.setOnClickListener(v->{clearPending();pendingAccount=PortalAccess.account(this);waitingVpn=true;startVpn();});
        Button privacy=new Button(this);privacy.setText("本机数据与登录说明");privacy.setAllCaps(false);privacy.setTextSize(12);add(root,privacy,16);privacy.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("登录与本机数据").setMessage("门户密码仅提交到学校 HTTPS 统一认证页面。保存的密码由 Android 密钥库加密；动态密码不保存。直连不启用 VPN SDK，校外连接时再征求服务同意。学校门户回跳和部分通知地址使用 HTTP，正文与相应会话不具备端到端 HTTPS 保护。AI 与问卷功能另行设置。可在设置中删除本机保存的凭据。").setPositiveButton("知道了",null).show());
    }
    @Override protected void onResume(){super.onResume();if(!attempted&&!manage){attempted=true;if(!PortalAccess.account(this).isEmpty())begin(true);}}
    private void begin(boolean automatic){
        if(busy||closed)return;
        vpnFallbackTried=false;
        String user,secret=password.getText().toString();
        try{user=org.campusreader.auth.CampusAccount.profileId(account.getText().toString());}catch(IllegalArgumentException invalid){account.setError("请输入有效学工号");return;}
        if(user.isEmpty()){account.setError("请输入学工号");return;}
        if(!automatic&&secret.isEmpty()){
            try{if(new PortalCredentialStore(this,MediaSafety.readerProfile(user)).load(PortalAccess.loginUrl(),user)==null){password.setError("请输入信息门户密码");return;}}catch(Exception unavailable){password.setError("请输入信息门户密码");return;}
        }
        pendingAccount=user;pendingPassword=secret;pendingRemember=remember.isChecked();pendingExpires=SystemClock.elapsedRealtime()+180_000L;password.setText("");attempted=true;
        try{
            busy=true;login.setEnabled(false);status.setText("正在检查门户账号…");
            PortalAccess.selectAccount(this,user,()->{if(!closed)probe(automatic);});
        }catch(IllegalArgumentException bad){busy=false;login.setEnabled(true);account.setError("请输入有效学工号");clearPending();}
    }
    private void probe(boolean automatic){
        busy=true;login.setEnabled(false);status.setText("正在检查门户直连…");final int token=++generation;
        worker.execute(()->{
            PortalAccess.Route route=PortalAccess.probe(this);boolean session=false;String profile=PortalAccess.currentProfile(this);
            if((route==PortalAccess.Route.DIRECT||route==PortalAccess.Route.VPN)&&!profile.equals("local"))try{
                ReaderHttp.Response response=ReaderHttp.get(PortalAccess.noticeListUrl(),5*1024*1024,true,()->closed||generation!=token);
                new CampusPortalParser().parseList(response.url,response.status,response.text());session=true;
            }catch(Exception ignored){}
            final boolean logged=session;
            runOnUiThread(()->{
                if(closed||token!=generation)return;busy=false;login.setEnabled(true);
                if(logged){new ReaderPreferences(this).markPortalSession(profile);clearPending();enterReader();return;}
                if(route==PortalAccess.Route.DIRECT||route==PortalAccess.Route.VPN){openPortal(automatic);return;}
                if(route==PortalAccess.Route.UNAVAILABLE&&!PortalAccess.hasNetwork(this)){clearPending();status.setText("网络不可用，可阅读缓存并在联网后重试。");return;}
                if(route==PortalAccess.Route.UNAVAILABLE){status.setText("直连不可用，正在打开校外 VPN 连接…");waitingVpn=true;startVpn();return;}
                clearPending();status.setText(PortalAccess.problem()+"。可稍后重试或阅读缓存。");
            });
        });
    }
    private void openPortal(boolean automatic){
        if(SystemClock.elapsedRealtime()>pendingExpires)pendingPassword=null;
        String profile=PortalAccess.currentProfile(this);
        if(pendingPassword!=null&&!pendingPassword.isEmpty())PortalLoginLaunch.put(profile,pendingAccount,pendingPassword,pendingRemember);
        boolean hasPassword=pendingPassword!=null&&!pendingPassword.isEmpty();
        if(!hasPassword&&!automatic&&new ReaderPreferences(this).isPortalAutoRestoreEnabled()&&PortalSessionCoordinator.shouldAttempt(this,profile))try{
            PortalCredentialStore.Credentials saved=new PortalCredentialStore(this,profile).load(PortalAccess.loginUrl(),PortalAccess.account(this));
            if(saved!=null){PortalLoginLaunch.put(profile,saved.account,saved.password,true);hasPassword=true;}
        }catch(Exception unavailable){}
        clearPending();
        if(automatic&&!hasPassword)try{
            if(new PortalCredentialStore(this,profile).load(PortalAccess.loginUrl(),PortalAccess.account(this))==null){status.setText("门户需要登录，请输入密码。");return;}
        }catch(Exception unavailable){status.setText("请重新输入门户密码。");return;}
        startActivityForResult(new Intent(this,PortalLoginActivity.class).putExtra(PortalLoginActivity.EXTRA_PROFILE,profile).putExtra(PortalLoginActivity.EXTRA_AUTOMATIC,automatic&&!hasPassword),PORTAL);
    }
    private void startVpn(){
        if(closed)return;vpnFallbackTried=true;
        startActivityForResult(new Intent(this,CampusAuthActivity.class).putExtra("portalAccount",pendingAccount==null?PortalAccess.account(this):pendingAccount),VPN);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==PORTAL){
            if(result==RESULT_OK){PortalLoginLaunch.clear();enterReader();}
            else if(data!=null&&data.getBooleanExtra(PortalLoginActivity.EXTRA_NETWORK_FAILED,false)&&!vpnFallbackTried){
                PortalAccess.invalidate();pendingAccount=PortalAccess.account(this);waitingVpn=true;status.setText("认证后门户直连失败，正在连接 VPN…");startVpn();
            }else{PortalLoginLaunch.clear();status.setText("门户登录尚未完成，可重新输入密码或阅读缓存。");}
        }
        if(request==VPN){waitingVpn=false;if(result==RESULT_OK&&CampusApplication.auth().isAccessReady()){
            if(pendingAccount==null||pendingAccount.isEmpty()){account.setText(PortalAccess.account(this));clearPending();status.setText("VPN 通道已连接，请填写信息门户账号和密码。");}
            else probe(false);
        }else{clearPending();status.setText("VPN 尚未连接，可重试直连或阅读缓存。");}}
    }
    private void enterReader(){if(closed)return;generation++;clearPending();startActivity(new Intent(this,ReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}
    private void clearPending(){pendingPassword=null;pendingAccount=null;}
    @Override protected void onStop(){if(!waitingVpn)clearPending();super.onStop();}
    @Override protected void onDestroy(){closed=true;generation++;clearPending();worker.shutdownNow();super.onDestroy();}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(ReaderUi.INK);return t;}
    private void add(LinearLayout root,View view,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);root.addView(view,p);}
}
