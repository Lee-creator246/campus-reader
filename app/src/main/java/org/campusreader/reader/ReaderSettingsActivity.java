package org.campusreader.reader;

import android.app.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.campusreader.notices.*;
import java.util.concurrent.*;

/** Local settings shared by foreground refresh and the periodic job. */
public final class ReaderSettingsActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ReaderPreferences prefs;private String profile;private TextView usage;
    private Switch background,wifi,notifications,refreshOnOpen,portalRestore;private Spinner interval,entries,body,age,pictures;
    private final int[] minutes={15,30,60,180,360},counts={100,300,500,1000};
    private final long[] bodyLimits={5L<<20,20L<<20,50L<<20},ages={0,86400000L,7*86400000L,-1},imageLimits={0,16L<<20,40L<<20,80L<<20};
    private LinearLayout pageContainer;
    private TextView pageTitle,done,left;
    private final java.util.List<LinearLayout> categories=new java.util.ArrayList<>();
    private int selectedCategory=-1;
    private boolean policyLoaded,saving;
    private Spinner textSize;
    private final int[] zooms={90,100,110,125,140};
    private final String[] categoryTitles={"阅读显示","刷新与提醒","离线存储","AI 摘要","关于"};
    private final String[] categoryDescriptions={"正文字号与列表显示","自动刷新、登录恢复和通知权限","缓存容量、清理与下载","配置服务和管理摘要","版本与本地数据说明"};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=new ReaderPreferences(this);profile=getIntent().getStringExtra("profile");if(profile==null){finish();return;}
        getWindow().setStatusBarColor(ReaderUi.SURFACE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        // Public-project native settings categories share a reusable list layout.
        setContentView(R.layout.reader_activity_settings_list);
        getWindow().getDecorView().setBackgroundColor(ReaderUi.SURFACE);
        pageContainer=findViewById(R.id.reader_ll_container);
        pageTitle=findViewById(R.id.reader_text_action_bar_title);
        done=findViewById(R.id.reader_text_action_bar_done);
        left=findViewById(R.id.reader_text_action_bar_left);
        pageTitle.setTextColor(ReaderUi.INK);done.setTextColor(ReaderUi.BLUE);left.setTextColor(ReaderUi.BLUE);
        ReaderUi.selectable(done);ReaderUi.selectable(left);pageTitle.setTextSize(22);done.setMinWidth(dp(48));left.setMinWidth(dp(48));left.setText("返回");left.setOnClickListener(v->showCategories());
        done.setOnClickListener(v->{if(selectedCategory<0)finish();else if(policyLoaded&&!saving)save();});

        LinearLayout appearance=category();
        section(appearance,"阅读界面");LinearLayout visual=group(appearance);
        textSize=select(visual,"正文字号",new String[]{"较小  90%","标准  100%","较大  110%","大  125%","特大  140%"},index(zooms,getSharedPreferences("reader-local",MODE_PRIVATE).getInt("reader-text-zoom",100)));
        note(appearance,"列表、杂志和卡片显示方式可在通知列表上方的阅读选项中切换。");

        LinearLayout notificationsPage=category();
        section(notificationsPage,"打开 App 时");LinearLayout launch=group(notificationsPage);
        refreshOnOpen=toggle(launch,"打开时刷新","先展示缓存，再检查新通知",prefs.isRefreshOnOpen());
        portalRestore=toggle(launch,"自动恢复门户登录","只在前台恢复；需要密码时使用已保存且验证成功的凭据",prefs.isPortalAutoRestoreEnabled());
        section(notificationsPage,"后台刷新");LinearLayout sync=group(notificationsPage);
        background=toggle(sync,"后台定时刷新","连接有效时自动检查新的校内通知",prefs.isBackgroundEnabled());
        interval=select(sync,"刷新间隔",new String[]{"15 分钟","30 分钟","1 小时","3 小时","6 小时"},index(minutes,prefs.getIntervalMinutes()));
        wifi=toggle(sync,"仅在 Wi-Fi 下刷新","减少后台移动数据使用",prefs.isWifiOnly());
        notifications=toggle(sync,"新通知提醒","有新内容时在通知栏提醒",prefs.isNotificationsEnabled());
        note(notificationsPage,"后台刷新由系统安排，省电模式可能使其延迟。认证失效后，打开 App 可尝试恢复；需要验证码时仍须本人完成。修改后请点右上角“保存”。");
        section(notificationsPage,"门户账号");LinearLayout credentials=group(notificationsPage);
        action(credentials,"删除本机门户密码","离线也可删除；保留通知与阅读状态",()->new AlertDialog.Builder(this).setTitle("删除已保存的门户密码？")
                .setMessage("将停止使用当前账号的已保存密码自动登录，已有网页登录会话仍可使用。")
                .setPositiveButton("删除密码",(d,w)->{try{if(profile!=null&&profile.matches("campus:[a-f0-9]{64}")){new PortalCredentialStore(this,profile).clear();PortalSessionCoordinator.pause(this,profile);}Toast.makeText(this,"已清除本机门户密码",Toast.LENGTH_SHORT).show();}catch(Exception failure){Toast.makeText(this,"凭据清理未完成，请重试",Toast.LENGTH_LONG).show();}}).setNegativeButton("取消",null).show(),true);
        section(notificationsPage,"系统权限");LinearLayout access=group(notificationsPage);
        action(access,"刷新与提醒记录","查看为什么提醒或没有提醒",()->startActivity(new android.content.Intent(this,NotificationHistoryActivity.class).putExtra(NotificationHistoryActivity.EXTRA_PROFILE,profile)),false);
        action(access,"通知权限与通知渠道","管理系统允许的提醒方式",()->SettingsSupport.openNotifications(this),false);
        action(access,"发送测试通知","按已保存的提醒设置发送一条测试",()->Toast.makeText(this,ReaderNotifications.postTest(this)?"测试通知已发出，请查看通知栏":"通知未获允许或已关闭；请先保存设置并检查系统权限",Toast.LENGTH_LONG).show(),false);
        action(access,"电池与后台运行设置","查看系统对后台活动的限制",()->SettingsSupport.openBatterySettings(this),false);
        action(access,"自启动设置说明","不同手机的设置位置可能不同",()->new AlertDialog.Builder(this).setTitle("允许后台运行 / 自启动")
                .setMessage("请在本机应用信息或电池管理中，为校园阅读允许后台活动、自启动（若有此项）。强行停止后需重新打开 App，才能恢复定时任务。系统设置不会由 App 自动开启。")
                .setPositiveButton("打开应用信息",(d,w)->SettingsSupport.openAppDetails(this)).setNegativeButton("返回",null).show(),false);

        LinearLayout offline=category();section(offline,"阅读缓存");LinearLayout cache=group(offline);
        entries=select(cache,"最多保留的完整正文",new String[]{"100 条","300 条","500 条","1000 条"},index(counts,prefs.getMaxCachedEntries()));
        body=select(cache,"正文总量上限",new String[]{"5 MiB","20 MiB","50 MiB"},index(bodyLimits,prefs.getMaxBodyBytes()));
        age=select(cache,"已读正文自动清理",new String[]{"读后立即清理","读后 24 小时","读后 7 天","仅达到容量上限时清理"},index(ages,prefs.getReadRetentionMs()));
        pictures=select(cache,"图片缓存上限",new String[]{"不缓存图片","16 MiB","40 MiB","80 MiB"},index(imageLimits,prefs.getImageCacheLimitBytes()));
        note(offline,"清理正文仍保留标题、原文地址、阅读状态和标签。稍后读及带标签的正文优先保留，但仍受容量上限约束。已保存到相册或导出的文件不受影响。");
        section(offline,"存储与运行状态");LinearLayout storage=group(offline);
        usage=ReaderUi.text(this,"正在读取缓存用量…",13,ReaderUi.MUTED);usage.setLineSpacing(dp(5),1);usage.setPadding(dp(16),dp(16),dp(16),dp(16));storage.addView(usage);
        action(storage,"清理已读正文与图片","保留稍后读、带标签正文和全部阅读状态",()->clean(false),false);
        action(storage,"清理全部正文缓存","保留标题、阅读状态和标签",()->new AlertDialog.Builder(this).setTitle("清理全部正文缓存？")
                .setMessage("再次阅读需要联网获取正文。稍后读和带标签内容的正文也会清理，但阅读状态与标签保留；已保存到相册或导出的文件不受影响。")
                .setPositiveButton("清理正文缓存",(d,w)->clean(true)).setNegativeButton("取消",null).show(),true);
        action(storage,"下载文件管理","查看、导出或清理图片与附件",()->MediaActions.viewDownloads(this),false);

        LinearLayout about=category();section(about,"校园阅读");
        note(about,"校园阅读  0.7.0-campus\n信息门户与本地阅读管理。");
        note(about,"通知缓存、已读、稍后读和标签保存在本机。校园连接和信息门户登录可从侧边栏进入。");
        showCategories();
        worker.execute(()->{
            try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){
                RetentionPolicy policy=store.getRetentionPolicy();
                runOnUiThread(()->{if(isDestroyed())return;entries.setSelection(index(counts,policy.maxCachedEntries));body.setSelection(index(bodyLimits,policy.maxBodyBytes));age.setSelection(index(ages,policy.readRetentionMillis));policyLoaded=true;updateDone();});
            }catch(RuntimeException failed){runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,"读取当前账号设置失败，请返回后重试",Toast.LENGTH_LONG).show();});}
            updateUsage();
        });
    }
    private LinearLayout category(){LinearLayout panel=column();panel.setBackgroundColor(ReaderUi.SURFACE);categories.add(panel);return panel;}
    private void showCategories(){
        selectedCategory=-1;pageTitle.setText("设置");left.setVisibility(View.INVISIBLE);pageContainer.removeAllViews();
        String[] icons={"settings_interface","settings_notifications","settings_miscellaneous","summary","settings_about"};
        for(int i=0;i<categoryTitles.length;i++){
            final int selected=i;View row=getLayoutInflater().inflate(R.layout.reader_item_settings,pageContainer,false);
            ((TextView)row.findViewById(R.id.reader_main_text)).setText(categoryTitles[i]);
            ((TextView)row.findViewById(R.id.reader_main_text)).setTextColor(ReaderUi.INK);
            ((TextView)row.findViewById(R.id.reader_settings_description)).setText(categoryDescriptions[i]);
            ((ImageView)row.findViewById(R.id.reader_left_icon)).setImageDrawable(ReaderUi.icon(this,icons[i],ReaderUi.INK));
            ((ImageView)row.findViewById(R.id.reader_right_icon)).setColorFilter(ReaderUi.MUTED);
            row.findViewById(R.id.reader_line).setBackgroundColor(ReaderUi.LINE);selectable(row);
            row.setOnClickListener(v->showCategory(selected));pageContainer.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        updateDone();scrollToTop();
    }
    private void showCategory(int index){if(index==3){startActivity(new android.content.Intent(this,AiSettingsActivity.class).putExtra(AiSettingsActivity.EXTRA_PROFILE,profile));return;}selectedCategory=index;pageTitle.setText(categoryTitles[index]);left.setVisibility(View.VISIBLE);pageContainer.removeAllViews();pageContainer.addView(categories.get(index==4?3:index));updateDone();scrollToTop();}
    private void updateDone(){boolean root=selectedCategory<0,about=selectedCategory==4;done.setText(root||about?"完成":saving?"保存中…":"保存");done.setEnabled(root||about||policyLoaded&&!saving);done.setAlpha(done.isEnabled()?1f:.4f);if(about)done.setOnClickListener(v->showCategories());else done.setOnClickListener(v->{if(selectedCategory<0)finish();else if(policyLoaded&&!saving)save();});}
    private void scrollToTop(){View parent=(View)pageContainer.getParent();if(parent instanceof ScrollView)parent.post(()->((ScrollView)parent).scrollTo(0,0));}
    private void selectable(View view){ReaderUi.selectable(view);}
    private void save(){
        if(!policyLoaded||saving)return;saving=true;updateDone();
        final RetentionPolicy policy=new RetentionPolicy(counts[entries.getSelectedItemPosition()],bodyLimits[body.getSelectedItemPosition()],ages[age.getSelectedItemPosition()]);
        prefs.setRefreshOnOpen(refreshOnOpen.isChecked());prefs.setPortalAutoRestoreEnabled(portalRestore.isChecked());
        prefs.setBackgroundEnabled(background.isChecked());prefs.setIntervalMinutes(minutes[interval.getSelectedItemPosition()]);prefs.setWifiOnly(wifi.isChecked());prefs.setNotificationsEnabled(notifications.isChecked());
        prefs.setMaxCachedEntries(policy.maxCachedEntries);prefs.setMaxBodyBytes(policy.maxBodyBytes);prefs.setReadRetentionMs(policy.readRetentionMillis);prefs.setImageCacheLimitBytes(imageLimits[pictures.getSelectedItemPosition()]);
        getSharedPreferences("reader-local",MODE_PRIVATE).edit().putInt("reader-text-zoom",zooms[textSize.getSelectedItemPosition()]).apply();
        ReaderNotifications.initialize(this);if(!prefs.isNotificationsEnabled())ReaderNotifications.clearAll(this);boolean scheduled=RefreshScheduler.schedule(this);
        worker.execute(()->{try{try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){store.setRetentionPolicy(policy);}ImageCacheMaintenance.prune(this,profile);updateUsage();runOnUiThread(()->{if(isDestroyed())return;saving=false;showCategories();Toast.makeText(this,scheduled||!prefs.isBackgroundEnabled()?"设置已保存":"设置已保存，但系统未接受后台任务；请检查后台设置",Toast.LENGTH_LONG).show();});}catch(RuntimeException error){runOnUiThread(()->{if(isDestroyed())return;saving=false;updateDone();Toast.makeText(this,"缓存设置保存失败，请重试",Toast.LENGTH_LONG).show();});}});
    }
    private void clean(boolean all){worker.execute(()->{int count;try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){count=all?store.clearBodies():store.clearReadBodies();}ImageCacheMaintenance.prune(this,profile);updateUsage();int cleared=count;runOnUiThread(()->Toast.makeText(this,"已清理 "+cleared+" 篇正文，阅读状态和标签保留",Toast.LENGTH_LONG).show());});}
    private void updateUsage(){
        try(CampusNoticeStore store=new CampusNoticeStore(this,profile)){
            CacheStats s=store.stats();String value="完整正文   "+s.cachedBodies+" 篇，"+mb(s.bodyBytes)+" MiB\n阅读图片   "+mb(ImageCacheMaintenance.size(this,profile))+" MiB\n轻量历史   "+s.totalEntries+" 条（含已清正文的记录）\n通知权限   "+(ReaderNotifications.canPost(this)?"已允许":"未允许，请打开系统通知权限")+"\n后台间隔   "+prefs.getIntervalMinutes()+" 分钟\n最近刷新   "+(prefs.getLastAttemptMillis()==0?"尚未执行":android.text.format.DateFormat.format("MM-dd HH:mm",prefs.getLastAttemptMillis())+"  "+resultText(prefs.getLastResult()));
            runOnUiThread(()->{if(!isDestroyed())usage.setText(value);});
        }catch(RuntimeException error){runOnUiThread(()->{if(!isDestroyed())usage.setText("暂时无法读取缓存用量，请返回后重试。");});}
    }
    private static String resultText(String code){switch(code){case "SUCCESS":return "成功";case "PARTIAL":return "部分成功";case "TUNNEL_REQUIRED":return "等待校园连接";case "PORTAL_LOGIN_REQUIRED":return "等待门户登录";case "BUSY":return "已有刷新进行中";case "CANCELLED":return "已停止";default:return "未完成，请检查网络或登录";}}
    private static String mb(long bytes){return String.format(java.util.Locale.ROOT,"%.1f",bytes/1048576.0);}
    private int dp(int n){return ReaderUi.dp(this,n);}
    private LinearLayout column(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);return p;}
    private void line(LinearLayout parent){View line=new View(this);line.setBackgroundColor(ReaderUi.LINE);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private void section(LinearLayout page,String title){TextView label=ReaderUi.text(this,title,15,ReaderUi.MUTED);label.setBackgroundColor(ReaderUi.BG);label.setPadding(dp(20),dp(20),dp(20),dp(12));page.addView(label);}
    private LinearLayout group(LinearLayout page){LinearLayout p=column();p.setBackgroundColor(ReaderUi.SURFACE);page.addView(p,new LinearLayout.LayoutParams(-1,-2));return p;}
    private void note(LinearLayout page,String value){TextView t=ReaderUi.text(this,value,14,ReaderUi.MUTED);t.setLineSpacing(dp(4),1);t.setPadding(dp(16),dp(12),dp(16),dp(16));page.addView(t);}
    private LinearLayout cell(LinearLayout parent){if(parent.getChildCount()>0)line(parent);LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(20),dp(14),dp(20),dp(14));row.setMinimumHeight(dp(70));parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return row;}
    private void caption(LinearLayout row,String title,String subtitle,int color){LinearLayout text=column();text.addView(ReaderUi.text(this,title,16,color));if(!subtitle.isEmpty()){TextView hint=ReaderUi.text(this,subtitle,14,ReaderUi.MUTED);hint.setPadding(0,dp(5),0,0);text.addView(hint);}row.addView(text,new LinearLayout.LayoutParams(0,-2,1));}
    private Switch toggle(LinearLayout parent,String title,String subtitle,boolean checked){LinearLayout row=cell(parent);caption(row,title,subtitle,ReaderUi.INK);Switch s=new Switch(this);s.setChecked(checked);s.setContentDescription(title);s.setMinimumHeight(dp(48));s.setPadding(dp(12),0,0,0);row.addView(s);selectable(row);row.setOnClickListener(v->s.toggle());return s;}
    private Spinner select(LinearLayout parent,String title,String[] choices,int selected){
        LinearLayout row=cell(parent);row.setOrientation(LinearLayout.VERTICAL);row.setGravity(Gravity.START);
        TextView label=ReaderUi.text(this,title,16,ReaderUi.INK);row.addView(label);
        Spinner s=new Spinner(this);s.setId(View.generateViewId());label.setLabelFor(s.getId());s.setContentDescription(title);s.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,choices);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(adapter);s.setSelection(selected);row.addView(s,new LinearLayout.LayoutParams(-1,dp(48)));return s;
    }
    private void action(LinearLayout parent,String title,String subtitle,Runnable action,boolean destructive){LinearLayout row=cell(parent);caption(row,title,subtitle,destructive?ReaderUi.DANGER:ReaderUi.INK);ImageView arrow=new ImageView(this);arrow.setImageDrawable(ReaderUi.icon(this,"back",ReaderUi.MUTED));arrow.setRotation(180);LinearLayout.LayoutParams arrowLayout=new LinearLayout.LayoutParams(dp(24),dp(24));arrowLayout.leftMargin=dp(16);row.addView(arrow,arrowLayout);row.setClickable(true);selectable(row);row.setOnClickListener(v->action.run());}
    private static int index(int[] a,int value){for(int i=0;i<a.length;i++)if(a[i]==value)return i;return Math.min(1,a.length-1);}
    private static int index(long[] a,long value){for(int i=0;i<a.length;i++)if(a[i]==value)return i;return Math.min(1,a.length-1);}
    @Override protected void onResume(){super.onResume();if(usage!=null&&!worker.isShutdown())worker.execute(this::updateUsage);}
    @Override public void onBackPressed(){if(selectedCategory>=0)showCategories();else super.onBackPressed();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
