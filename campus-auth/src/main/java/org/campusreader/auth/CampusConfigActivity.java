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
import android.text.Editable;
import android.text.TextWatcher;
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
import org.campusreader.config.SchoolDirectory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native setup. Values stay inside private app storage and are never included in Activity results. */
public final class CampusConfigActivity extends Activity {
    public static final int RESULT_RESTART_REQUIRED = RESULT_FIRST_USER;
    private static final int IMPORT_FILE = 3401, IMPORT_DIRECTORY = 3402;
    private static final int BLUE=0xff245bc4, INK=0xff19232d, MUTED=0xff56616f, BACKGROUND=0xfff4f6f8;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private EditText domain, vpn, list, portalLogin;
    private CheckBox consent;
    private TextView status;
    private Button save, back, pasteImport, fileImport;
    private Button directoryFile, directoryUrl, advancedButton;
    private EditText schoolSearch;
    private LinearLayout schoolRows, advanced;
    private TextView selectedSchool;
    private SchoolDirectory directory;
    private String selectedName="",selectedScope="";
    private volatile HttpURLConnection directoryConnection;
    private boolean saving, importing;
    private final Runnable clearDeadline = () -> showSaveFailure();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScreenPrivacy.protect(getWindow());
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
        TextView heading=text("选择你的学校",24,INK);heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);page.addView(heading);
        add(page,text("选好学校后，依次完成校园连接和信息门户登录。已有校园配置可直接继续使用。",14,MUTED),10);
        directory=new SchoolDirectoryStore(this).load();
        selectedSchool=text("尚未选择学校",16,INK);add(page,selectedSchool,20);
        schoolSearch=new EditText(this);schoolSearch.setSingleLine(true);schoolSearch.setHint("搜索学校名称或域名");schoolSearch.setTextSize(16);schoolSearch.setMinHeight(dp(52));schoolSearch.setSaveEnabled(false);schoolSearch.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});add(page,schoolSearch,12);
        schoolRows=column();add(page,schoolRows,8);
        LinearLayout directoryActions=new LinearLayout(this);
        directoryFile=button("导入学校目录",false);directoryUrl=button("下载学校目录",false);
        LinearLayout.LayoutParams directoryLeft=new LinearLayout.LayoutParams(0,-2,1);directoryLeft.rightMargin=dp(6);directoryActions.addView(directoryFile,directoryLeft);
        LinearLayout.LayoutParams directoryRight=new LinearLayout.LayoutParams(0,-2,1);directoryRight.leftMargin=dp(6);directoryActions.addView(directoryUrl,directoryRight);add(page,directoryActions,12);
        add(page,text("导入学校列表文件后，直接搜索并选择学校，无需打开文件编辑。目录可离线使用；实际连接和通知结构仍需验证。",13,MUTED),8);
        directoryFile.setOnClickListener(v->chooseFile(IMPORT_DIRECTORY));directoryUrl.setOnClickListener(v->downloadDirectory());
        schoolSearch.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){renderSchools();}public void afterTextChanged(Editable e){}});
        advancedButton=button("高级配置与单校导入",false);add(page,advancedButton,20);
        advanced=column();advanced.setVisibility(View.GONE);add(page,advanced,8);
        advancedButton.setOnClickListener(v->{boolean opened=advanced.getVisibility()!=View.VISIBLE;advanced.setVisibility(opened?View.VISIBLE:View.GONE);advancedButton.setText(opened?"收起高级配置":"高级配置与单校导入");});

        LinearLayout importActions=new LinearLayout(this);importActions.setGravity(Gravity.CENTER_VERTICAL);
        pasteImport=button("粘贴配置",false);fileImport=button("从文件导入",false);
        LinearLayout.LayoutParams importLeft=new LinearLayout.LayoutParams(0,-2,1);importLeft.rightMargin=dp(6);
        LinearLayout.LayoutParams importRight=new LinearLayout.LayoutParams(0,-2,1);importRight.leftMargin=dp(6);
        importActions.addView(pasteImport,importLeft);importActions.addView(fileImport,importRight);add(advanced,importActions,8);
        add(advanced,text("单校配置支持 version 1 JSON（UTF-8，最多 16 KiB）。预览后填入表单，点击保存才会更改校园访问。",13,MUTED),8);
        pasteImport.setOnClickListener(v->pasteConfiguration());
        fileImport.setOnClickListener(v->chooseConfigurationFile());

        LinearLayout form=column();form.setPadding(dp(16),dp(16),dp(16),dp(16));form.setBackground(shape(Color.WHITE,0xffdce2e8,8));add(advanced,form,16);
        domain=field(form,"校园认证域","例如 campus.example.edu",false);
        vpn=field(form,"VPN HTTPS 入口","例如 https://vpn.campus.example.edu",true);
        list=field(form,"门户通知列表地址","例如 https://portal.campus.example.edu/notices",true);
        portalLogin=field(form,"门户登录地址（可选）","例如 https://sso.campus.example.edu/login",true);
        add(form,text("认证域不含 https:// 或路径。VPN 必须使用学校确认的 HTTPS 地址。通知列表应属于认证域或其子域；门户登录须使用域内 HTTPS，或与通知列表同主机的 HTTP。地址须使用标准端口，HTTP 未加密。请勿填写密码、验证码或带登录票据的地址。",12,MUTED),12);
        CampusSite.Profile current=CampusSite.current();
        if(current.configured){fillForm(current);selectedName=getSharedPreferences("school-selection",MODE_PRIVATE).getString("name:"+current.scopeId,"当前学校");selectedScope=current.scopeId;selectedSchool.setText(selectedName+" · 当前使用");}

        add(page,text("连接服务说明",16,INK),22);
        add(page,text("校园连接使用深信服 Sangfor 第三方 SDK，可能收集设备、网络状态及学校安全策略要求的信息，用于认证和建立通道，部分信息可能发送给服务方。Android 可能请求 VPN 或设备权限。校园配置保存在本机；门户登录可按你的选择加密保存密码，网页与连接组件可能保留会话。",13,MUTED),10);
        consent=new CheckBox(this);consent.setText("我已了解并同意启用第三方校园连接 SDK");consent.setTextColor(INK);consent.setTextSize(14);consent.setSaveEnabled(false);add(page,consent,12);
        consent.setChecked(CampusConfiguration.hasConsent());
        if(CampusSite.isConfigured())add(page,text("保持原配置会直接返回阅读。更换学校或访问地址需重新认证；缓存保留。连接服务已启动时，修改保存后需重新打开应用。",13,MUTED),12);
        status=text("",14,0xffb3261e);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(page,status,12);
        save=button(current.configured?"确认并继续":"保存并继续校园认证",true);add(page,save,16);
        save.setOnClickListener(v->saveConfiguration());
        setContentView(root);
        renderSchools();
        if(CampusConfiguration.requiresRestart())showRestartRequired();
    }

    private void saveConfiguration() {
        if(saving||importing)return;
        if(CampusConfiguration.requiresRestart()){finishWithResult(RESULT_RESTART_REQUIRED);return;}
        if(!consent.isChecked()){status.setText("请先阅读并勾选连接服务说明；同意前不会启动连接 SDK。");return;}
        final CampusSite.Profile next;
        try{next=CampusSite.create(domain.getText().toString(),vpn.getText().toString(),list.getText().toString(),portalLogin.getText().toString());}
        catch(IllegalArgumentException invalid){status.setText("配置格式不正确。请检查认证域、地址协议、所属域和端口，并移除账号、密码或登录票据。");return;}
        if(CampusConfiguration.isUnchanged(next)){rememberSelection(next);finishWithResult(RESULT_CANCELED);return;}
        saving=true;setFormEnabled(false);status.setText("正在退出已有连接并清除网页登录状态…");
        try{
            CampusConfiguration.beginChange(this);
            main.postDelayed(clearDeadline,15_000);
            clearWebSession(()->{
                if(!saving)return;
                main.removeCallbacks(clearDeadline);
                try{
                    boolean restart=CampusConfiguration.save(this,next,true);
                    rememberSelection(next);
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
        domain.setEnabled(enabled);vpn.setEnabled(enabled);list.setEnabled(enabled);portalLogin.setEnabled(enabled);consent.setEnabled(enabled);save.setEnabled(enabled);back.setEnabled(enabled||importing);
        pasteImport.setEnabled(enabled);fileImport.setEnabled(enabled);
        directoryFile.setEnabled(enabled);directoryUrl.setEnabled(enabled);schoolSearch.setEnabled(enabled);advancedButton.setEnabled(enabled);
        for(int i=0;i<schoolRows.getChildCount();i++)schoolRows.getChildAt(i).setEnabled(enabled);
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
            ScreenPrivacy.protect(dialog.getWindow());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try{CampusSite.Profile next=CampusConfigImport.parse(input.getText().toString());dialog.dismiss();previewImport(next);}
                catch(IllegalArgumentException invalid){input.setError(invalid.getMessage());}
            });
        });dialog.show();
    }
    private void chooseConfigurationFile(){
        chooseFile(IMPORT_FILE);
    }
    private void chooseFile(int code){
        if(saving||importing||CampusConfiguration.requiresRestart())return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});
        try{startActivityForResult(intent,code);}
        catch(RuntimeException unavailable){status.setText("无法打开文件选择器，请改用粘贴配置。");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if((request!=IMPORT_FILE&&request!=IMPORT_DIRECTORY)||result!=RESULT_OK||data==null||data.getData()==null)return;
        if(saving||importing||CampusConfiguration.requiresRestart())return;
        final android.net.Uri uri=data.getData();
        if(!"content".equals(uri.getScheme())){status.setText("请选择系统文件选择器提供的配置文件。");return;}
        importing=true;setFormEnabled(false);status.setText("正在读取配置文件；当前配置尚未更改…");
        imports.execute(()->{
            try(InputStream input=getContentResolver().openInputStream(uri)){
                if(request==IMPORT_DIRECTORY){String document=SchoolDirectory.readText(input);SchoolDirectory parsed=SchoolDirectory.parse(document);main.post(()->{if(isDestroyed()||isFinishing())return;importing=false;setFormEnabled(true);status.setText("");previewDirectory(document,parsed);});return;}
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
                    selectedName="";selectedScope="";selectedSchool.setText("自定义校园 · 尚未保存");consent.setChecked(CampusConfiguration.isUnchanged(next));status.setText("已填入表单，尚未保存。请核对入口与连接服务说明，再点击保存。");
                }).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(ignored->ScreenPrivacy.protect(dialog.getWindow()));dialog.show();
    }
    private void previewField(StringBuilder out,String label,String before,String after){
        String current=before.trim();boolean same=current.equals(after);
        out.append(label).append(same?" · 相同\n":" · 将更改\n");
        if(!same)out.append("当前：").append(current.isEmpty()?"未填写":current).append('\n');
        out.append("导入：").append(after).append("\n\n");
    }
    private void fillForm(CampusSite.Profile next){domain.setText(next.domain);vpn.setText(next.vpnUrl);list.setText(next.portalListUrl);portalLogin.setText(next.loginUrl);}
    private void rememberSelection(CampusSite.Profile next){
        String label=next.scopeId.equals(selectedScope)&&!selectedName.isEmpty()?selectedName:"自定义校园";
        getSharedPreferences("school-selection",MODE_PRIVATE).edit().putString("name:"+next.scopeId,label).apply();
    }
    private void renderSchools(){
        if(schoolRows==null)return;schoolRows.removeAllViews();String query=schoolSearch.getText().toString();
        CampusSite.Profile current=CampusSite.current();
        if(current.configured&&(query.trim().isEmpty()||("当前学校 "+current.domain).contains(query.trim()))){
            Button currentRow=button("当前学校  ·  "+current.domain,false);currentRow.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);currentRow.setPadding(dp(16),0,dp(16),0);
            currentRow.setOnClickListener(v->{if(saving||importing)return;fillForm(current);selectedScope=current.scopeId;selectedName=getSharedPreferences("school-selection",MODE_PRIVATE).getString("name:"+current.scopeId,"当前学校");selectedSchool.setText(selectedName+" · 当前使用");consent.setChecked(CampusConfiguration.hasConsent());status.setText("配置未改变，可直接继续使用。");});add(schoolRows,currentRow,4);
        }
        if(directory==null){add(schoolRows,text("还没有学校目录。导入提供给你的目录文件，即可按名称选校；也可展开高级配置。",14,MUTED),8);return;}
        List<SchoolDirectory.Entry> matches=directory.search(query);int shown=0;
        for(SchoolDirectory.Entry entry:matches){if(shown++>=20)break;
            Button row=button(entry.name+"\n"+entry.profile.domain,false);row.setTextSize(15);row.setMinHeight(dp(64));row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(8),dp(16),dp(8));row.setEnabled(!saving&&!importing);row.setOnClickListener(v->selectSchool(entry));add(schoolRows,row,6);
        }
        if(matches.isEmpty())add(schoolRows,text("未找到学校。可更换关键词，或从高级配置添加。",14,MUTED),8);
        else if(matches.size()>20)add(schoolRows,text("显示前 20 项，请输入学校名称缩小范围。",13,MUTED),8);
    }
    private void selectSchool(SchoolDirectory.Entry entry){
        if(saving||importing||CampusConfiguration.requiresRestart())return;
        CampusSite.Profile next=entry.profile;
        String description="请核对学校公开入口。选中只填入表单，点击保存后才生效。\n\n校园连接："+next.vpnUrl+"\n\n通知列表："+next.portalListUrl+"\n\n门户登录："+next.loginUrl+"\n\n当前支持的连接与门户模板；目录不代表学校官方背书或实际连接验证。";
        if(next.portalListUrl.startsWith("http:")||next.loginUrl.startsWith("http:"))description+="\n此配置含 HTTP 门户地址，网页传输可能未加密；自动密码登录仅可用于已确认的 HTTPS 认证页面。";
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(entry.name).setMessage(description).setNegativeButton("取消",null).setPositiveButton("选择学校",(d,w)->{
            fillForm(next);selectedName=entry.name;selectedScope=next.scopeId;selectedSchool.setText(entry.name+" · "+(CampusConfiguration.isUnchanged(next)?"当前使用":"待保存"));consent.setChecked(CampusConfiguration.isUnchanged(next));status.setText("已选择学校，请核对连接服务说明后继续。");
        }).create();dialog.show();ScreenPrivacy.protect(dialog.getWindow());
    }
    private void previewDirectory(String document,SchoolDirectory parsed){
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("导入学校目录")
                .setMessage("目录包含 "+parsed.schools.size()+" 所学校的配置模板。确认后保存在本机并替换旧目录，可离线搜索。当前学校和登录状态保持不变；选择学校时请核对实际服务地址。")
                .setNegativeButton("取消",null).setPositiveButton("导入目录",(d,w)->{
                    try{new SchoolDirectoryStore(this).save(document);directory=parsed;renderSchools();status.setText("学校目录已导入，请搜索并选择学校。");}
                    catch(Exception failure){status.setText("目录未能保存，请重试。当前校园配置未更改。");}
                }).create();dialog.show();ScreenPrivacy.protect(dialog.getWindow());
    }
    private void downloadDirectory(){
        if(saving||importing||CampusConfiguration.requiresRestart())return;
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("https://目录提供方/学校目录.json");input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);input.setTextSize(15);input.setSaveEnabled(false);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        LinearLayout content=column();content.setPadding(dp(20),dp(8),dp(20),0);content.addView(text("填写你信任的目录提供方地址。只下载配置数据，下载后仍需确认导入。",14,MUTED));add(content,input,12);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("下载学校目录").setView(content).setNegativeButton("取消",null).setPositiveButton("下载",null).create();
        dialog.setOnShowListener(ignored->{ScreenPrivacy.protect(dialog.getWindow());dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            final String address;try{address=SchoolDirectory.downloadUrl(input.getText().toString());}catch(IllegalArgumentException invalid){input.setError(invalid.getMessage());return;}
            dialog.dismiss();importing=true;setFormEnabled(false);status.setText("正在下载学校目录…");
            imports.execute(()->{
                try{
                    HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();directoryConnection=connection;
                    connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setUseCaches(false);connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Cookie","");
                    if(Thread.currentThread().isInterrupted()||connection.getResponseCode()!=200||connection.getContentLength()>SchoolDirectory.MAX_BYTES)throw new java.io.IOException("Invalid directory response");
                    final long deadline=android.os.SystemClock.elapsedRealtime()+30000;
                    final String document;
                    try(InputStream raw=connection.getInputStream();InputStream bounded=new java.io.FilterInputStream(raw){@Override public int read(byte[] b,int off,int len)throws java.io.IOException{if(Thread.currentThread().isInterrupted()||android.os.SystemClock.elapsedRealtime()>deadline)throw new java.io.IOException("Directory timeout");return super.read(b,off,len);}}){document=SchoolDirectory.readText(bounded);}
                    SchoolDirectory parsed=SchoolDirectory.parse(document);
                    main.post(()->{if(isFinishing()||isDestroyed())return;importing=false;setFormEnabled(true);status.setText("");previewDirectory(document,parsed);});
                }catch(Exception failure){main.post(()->{if(isFinishing()||isDestroyed())return;importing=false;setFormEnabled(true);status.setText("目录下载失败。请检查地址或改用文件导入；重定向和不支持的目录会被拒绝。");});}
                finally{HttpURLConnection connection=directoryConnection;directoryConnection=null;if(connection!=null)connection.disconnect();}
            });
        });});dialog.show();
    }
    private void finishWithResult(int result){
        if(getCallingActivity()==null){
            if(result==RESULT_RESTART_REQUIRED){finishAffinity();main.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),200);return;}
            if(result==RESULT_OK)startActivity(new Intent(this,CampusAuthActivity.class));
        }
        setResult(result);
        finish();
    }
    @Override public void onBackPressed(){if(saving)return;if(CampusConfiguration.requiresRestart())finishWithResult(RESULT_RESTART_REQUIRED);else super.onBackPressed();}
    @Override protected void onDestroy(){main.removeCallbacks(clearDeadline);imports.shutdownNow();HttpURLConnection connection=directoryConnection;if(connection!=null)connection.disconnect();super.onDestroy();}
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
