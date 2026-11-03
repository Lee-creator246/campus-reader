package org.campusreader.auth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.campusreader.config.CampusConfigImport;
import org.campusreader.config.CampusSite;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native setup. Values stay inside private app storage and are never included in Activity results. */
public final class CampusConfigActivity extends Activity {
    public static final int RESULT_RESTART_REQUIRED = RESULT_FIRST_USER;
    private static final int IMPORT_FILE = 3401;
    private static final int BLUE=0xff2767d8, INK=0xff202124, MUTED=0xff647082, BACKGROUND=0xfff5f6f8;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private EditText domain, vpn, list, portalLogin;
    private CheckBox consent;
    private TextView status;
    private Button save, back, pasteImport, fileImport;
    private boolean saving, importing;
    private final Runnable clearDeadline = () -> showSaveFailure();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 26 ? Color.WHITE : INK);
        int flags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(Build.VERSION.SDK_INT>=26)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);

        LinearLayout root=column(); root.setBackgroundColor(BACKGROUND);
        if(Build.VERSION.SDK_INT>=26)root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setBackgroundColor(Color.WHITE);toolbar.setPadding(dp(8),dp(4),dp(16),dp(4));
        back=button("‹",false);back.setTextSize(30);back.setContentDescription("返回阅读");back.setBackgroundColor(Color.TRANSPARENT);back.setOnClickListener(v->onBackPressed());toolbar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=text("校园配置",20,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(toolbar);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout page=column();page.setPadding(dp(20),dp(24),dp(20),dp(24));scroll.addView(page);
        TextView heading=text("设置你的校园访问入口",22,INK);heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);page.addView(heading);
        add(page,text("向学校确认以下地址后填写。示例仅帮助理解格式，不会自动填入或连接。保存后先完成校园连接，再单独登录信息门户。",14,MUTED),10);

        LinearLayout importActions=new LinearLayout(this);importActions.setGravity(Gravity.CENTER_VERTICAL);
        pasteImport=button("粘贴配置",false);fileImport=button("从文件导入",false);
        LinearLayout.LayoutParams importLeft=new LinearLayout.LayoutParams(0,-2,1);importLeft.rightMargin=dp(6);
        LinearLayout.LayoutParams importRight=new LinearLayout.LayoutParams(0,-2,1);importRight.leftMargin=dp(6);
        importActions.addView(pasteImport,importLeft);importActions.addView(fileImport,importRight);add(page,importActions,18);
        add(page,text("支持 version 1 的校园阅读 JSON 配置（UTF-8，最多 16 KiB）。先预览差异，再填入下方；只有点击保存才会更改校园访问。配置不得含账号、密码或登录票据。",12,MUTED),8);
        pasteImport.setOnClickListener(v->pasteConfiguration());
        fileImport.setOnClickListener(v->chooseConfigurationFile());

        LinearLayout form=column();form.setPadding(dp(18),dp(18),dp(18),dp(18));form.setBackground(shape(Color.WHITE,0xffe5e8ee,14));add(page,form,24);
        domain=field(form,"校园认证域","例如 campus.example.edu",false);
        vpn=field(form,"VPN HTTPS 入口","例如 https://vpn.campus.example.edu",true);
        list=field(form,"门户通知列表地址","例如 https://portal.campus.example.edu/notices",true);
        portalLogin=field(form,"门户登录地址（可选）","例如 https://sso.campus.example.edu/login",true);
        add(form,text("认证域不含 https:// 或路径。VPN 必须使用学校确认的 HTTPS 地址。通知列表应属于认证域或其子域；门户登录须使用域内 HTTPS，或与通知列表同主机的 HTTP。地址须使用标准端口，HTTP 未加密。请勿填写密码、验证码或带登录票据的地址。",12,MUTED),12);
        CampusSite.Profile current=CampusSite.current();
        if(current.configured){domain.setText(current.domain);vpn.setText(current.vpnUrl);list.setText(current.portalListUrl);portalLogin.setText(current.loginUrl);}

        add(page,text("连接服务说明",16,INK),22);
        add(page,text("校园连接使用深信服 Sangfor 第三方连接 SDK。连接时，该 SDK 可能访问设备标识、设备与系统信息、网络状态及学校安全策略要求的信息，用于认证、建立通道和安全检查；部分设备和网络诊断信息可能发送给服务提供方。Android 可能另行请求 VPN 或设备权限。请仅配置你信任的学校服务。校园配置仅保存在本机应用私有存储中。本项目代码不记录或导出密码；门户网页与连接组件可能保留会话信息。",13,MUTED),10);
        consent=new CheckBox(this);consent.setText("我已了解并同意启用第三方校园连接 SDK");consent.setTextColor(INK);consent.setTextSize(14);consent.setSaveEnabled(false);add(page,consent,12);
        if(CampusSite.isConfigured())add(page,text("保存会退出现有校园连接和门户登录，并清除网页登录状态；已缓存的通知保留。连接服务已启动时，保存后应用会关闭，请从桌面重新打开。",13,MUTED),12);
        status=text("",14,0xffb3261e);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(page,status,12);
        save=button(CampusSdk.wasInitializationAttempted()?"保存并关闭应用":"保存并继续校园认证",true);add(page,save,16);
        save.setOnClickListener(v->saveConfiguration());
        setContentView(root);
        if(CampusConfiguration.requiresRestart())showRestartRequired();
    }

    private void saveConfiguration() {
        if(saving||importing)return;
        if(CampusConfiguration.requiresRestart()){finishWithResult(RESULT_RESTART_REQUIRED);return;}
        if(!consent.isChecked()){status.setText("请先阅读并勾选连接服务说明；同意前不会启动连接 SDK。");return;}
        final CampusSite.Profile next;
        try{next=CampusSite.create(domain.getText().toString(),vpn.getText().toString(),list.getText().toString(),portalLogin.getText().toString());}
        catch(IllegalArgumentException invalid){status.setText("配置格式不正确。请检查认证域、地址协议、所属域和端口，并移除账号、密码或登录票据。");return;}
        saving=true;setFormEnabled(false);status.setText("正在退出已有连接并清除网页登录状态…");
        try{
            CampusConfiguration.beginChange(this);
            main.postDelayed(clearDeadline,15_000);
            clearWebSession(()->{
                if(!saving)return;
                main.removeCallbacks(clearDeadline);
                try{
                    boolean restart=CampusConfiguration.save(this,next,true);
                    saving=false;
                    finishWithResult(restart?RESULT_RESTART_REQUIRED:RESULT_OK);
                }catch(RuntimeException failure){showSaveFailure();}
            });
        }catch(RuntimeException failure){showSaveFailure();}
    }

    private void clearWebSession(Runnable done) {
        WebStorage.getInstance().deleteAllData();
        WebViewDatabase database=WebViewDatabase.getInstance(this);
        database.clearHttpAuthUsernamePassword();database.clearUsernamePassword();database.clearFormData();
        WebView temporary=new WebView(this);
        temporary.clearCache(true);temporary.clearHistory();temporary.clearFormData();temporary.clearSslPreferences();temporary.destroy();
        // Both asynchronous erasures must complete before persisting/returning a new profile.
        int[] remaining={2};
        Runnable completed=()->{if(--remaining[0]==0)done.run();};
        CookieManager cookies=CookieManager.getInstance();
        cookies.removeAllCookies(removed->{cookies.flush();completed.run();});
        WebView.clearClientCertPreferences(completed);
    }
    private void showSaveFailure() {
        if(!saving)return;
        saving=false;main.removeCallbacks(clearDeadline);CampusConfiguration.abortChange();
        setFormEnabled(true);
        status.setText("未能完成配置保存，请重试。现有缓存继续保留。");
        if(CampusConfiguration.requiresRestart())showRestartRequired();
    }
    private void showRestartRequired(){
        setFormEnabled(false);save.setEnabled(true);back.setEnabled(true);
        status.setText("请关闭应用后从桌面重新打开，再查看或继续校园配置。");
        save.setText("关闭应用");
    }
    private void setFormEnabled(boolean enabled){
        domain.setEnabled(enabled);vpn.setEnabled(enabled);list.setEnabled(enabled);portalLogin.setEnabled(enabled);consent.setEnabled(enabled);save.setEnabled(enabled);back.setEnabled(enabled);
        pasteImport.setEnabled(enabled);fileImport.setEnabled(enabled);
    }
    private void pasteConfiguration(){
        if(saving||importing||CampusConfiguration.requiresRestart())return;
        LinearLayout content=column();content.setPadding(dp(20),dp(8),dp(20),0);
        TextView help=text("粘贴站点 JSON 后点击预览。预览只校验内容，不会保存或连接校园服务。",13,MUTED);content.addView(help);
        EditText input=new EditText(this);input.setHint("{\"format\":\"campus-reader-site\",\"version\":1,…}");input.setMinLines(5);input.setMaxLines(10);input.setGravity(Gravity.TOP|Gravity.START);input.setTextSize(13);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSaveEnabled(false);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(CampusConfigImport.MAX_BYTES+1)});
        if(Build.VERSION.SDK_INT>=26)input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        add(content,input,8);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("粘贴校园配置").setView(content)
                .setPositiveButton("预览差异",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(ignored->{
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try{CampusSite.Profile next=CampusConfigImport.parse(input.getText().toString());dialog.dismiss();previewImport(next);}
                catch(IllegalArgumentException invalid){input.setError(invalid.getMessage());}
            });
        });dialog.show();
    }
    private void chooseConfigurationFile(){
        if(saving||importing||CampusConfiguration.requiresRestart())return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});
        try{startActivityForResult(intent,IMPORT_FILE);}
        catch(RuntimeException unavailable){status.setText("无法打开文件选择器，请改用粘贴配置。");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=IMPORT_FILE||result!=RESULT_OK||data==null||data.getData()==null)return;
        if(saving||importing||CampusConfiguration.requiresRestart())return;
        final android.net.Uri uri=data.getData();
        if(!"content".equals(uri.getScheme())){status.setText("请选择系统文件选择器提供的配置文件。");return;}
        importing=true;setFormEnabled(false);status.setText("正在读取配置文件；当前配置尚未更改…");
        imports.execute(()->{
            try(InputStream input=getContentResolver().openInputStream(uri)){
                CampusSite.Profile next=CampusConfigImport.read(input);
                main.post(()->{if(isDestroyed()||isFinishing())return;importing=false;setFormEnabled(true);status.setText("");previewImport(next);});
            }catch(Exception failure){
                String message=failure instanceof IllegalArgumentException?failure.getMessage():"未能读取配置文件，请重新选择，或改用粘贴配置。";
                main.post(()->{if(isDestroyed()||isFinishing())return;importing=false;setFormEnabled(true);status.setText(message);});
            }
        });
    }
    private void previewImport(CampusSite.Profile next){
        if(saving||importing||CampusConfiguration.requiresRestart())return;
        StringBuilder preview=new StringBuilder("以下对比当前表单。填入只替换表单内容，不保存、不退出连接。\n\n");
        previewField(preview,"校园认证域",domain.getText().toString(),next.domain);
        previewField(preview,"VPN HTTPS 入口",vpn.getText().toString(),next.vpnUrl);
        previewField(preview,"门户通知列表",list.getText().toString(),next.portalListUrl);
        previewField(preview,"门户登录入口",portalLogin.getText().toString(),next.loginUrl);
        preview.append("保存后需重新认证，已有缓存按站点和账号隔离保留。导入地址不保证门户页面结构兼容。");
        if(next.portalListUrl.startsWith("http://")||next.loginUrl.startsWith("http://"))preview.append("\n此配置包含未加密的 HTTP 门户，请向学校确认。");
        ScrollView scroll=new ScrollView(this);TextView body=text(preview.toString(),13,INK);body.setTextIsSelectable(true);body.setPadding(dp(20),dp(12),dp(20),dp(12));scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("配置预览 · 尚未保存").setView(scroll)
                .setPositiveButton("填入表单",(d,which)->{
                    domain.setText(next.domain);vpn.setText(next.vpnUrl);list.setText(next.portalListUrl);portalLogin.setText(next.loginUrl);
                    consent.setChecked(false);status.setText("已填入表单，尚未保存。请核对入口并重新勾选连接服务说明，再点击保存。");
                }).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(ignored->dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));dialog.show();
    }
    private void previewField(StringBuilder out,String label,String before,String after){
        String current=before.trim();boolean same=current.equals(after);
        out.append(label).append(same?" · 相同\n":" · 将更改\n");
        if(!same)out.append("当前：").append(current.isEmpty()?"未填写":current).append('\n');
        out.append("导入：").append(after).append("\n\n");
    }
    private void finishWithResult(int result){
        if(getCallingActivity()==null){
            if(result==RESULT_RESTART_REQUIRED){finishAffinity();main.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),200);return;}
            if(result==RESULT_OK)startActivity(new Intent(this,CampusAuthActivity.class));
        }
        setResult(result);
        finish();
    }
    @Override public void onBackPressed(){if(saving||importing)return;if(CampusConfiguration.requiresRestart())finishWithResult(RESULT_RESTART_REQUIRED);else super.onBackPressed();}
    @Override protected void onDestroy(){main.removeCallbacks(clearDeadline);imports.shutdownNow();super.onDestroy();}
    private EditText field(LinearLayout form,String label,String hint,boolean url){
        EditText edit=new EditText(this);edit.setId(View.generateViewId());edit.setSingleLine(true);edit.setHint(hint);edit.setSaveEnabled(false);
        edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|(url?InputType.TYPE_TEXT_VARIATION_URI:InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        if(Build.VERSION.SDK_INT>=26)edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        edit.setTextSize(15);edit.setTextColor(INK);edit.setHintTextColor(0xff8b93a1);edit.setMinHeight(dp(56));edit.setPadding(dp(14),dp(12),dp(14),dp(12));edit.setBackground(shape(BACKGROUND,0xffdce1e9,10));
        TextView name=text(label,14,INK);name.setLabelFor(edit.getId());add(form,name,form.getChildCount()==0?0:18);add(form,edit,8);return edit;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(3),1);return view;}
    private GradientDrawable shape(int color,int border,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));if(border!=0)shape.setStroke(dp(1),border);return shape;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(top);parent.addView(child,params);}
    private Button button(String value,boolean primary){Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setTextSize(15);button.setTextColor(primary?Color.WHITE:INK);button.setMinHeight(dp(52));button.setBackground(shape(primary?BLUE:Color.WHITE,primary?0:0xffdce1e9,10));return button;}
}
