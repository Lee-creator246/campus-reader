package org.campusreader.reader;

import org.campusreader.config.CampusSite;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import org.campusreader.auth.*;
import org.campusreader.notices.*;
import org.campusreader.portal.CampusPortalParser;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native reader presentation bound to the local campus repository. */
public class ReaderActivity extends Activity {
    private static final int AUTH=100, CONFIG=101, PORTAL=102, BLUE=ReaderUi.BLUE, INK=ReaderUi.INK, MUTED=ReaderUi.MUTED;
    private SyncGate.Lease configurationLease;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final ExecutorService images=Executors.newFixedThreadPool(2);
    private final CampusPortalParser parser=new CampusPortalParser();
    private final List<CachedNotice> rows=new ArrayList<>();
    private final List<Subscription> subscriptions=new ArrayList<>();
    private SharedPreferences preferences;
    private ReaderFilterState libraryFilter;
    private KeywordRules keywordRules;
    private boolean priorityOnly;
    private TextView priorityCount;
    private String profile,source=CampusPortalParser.SOURCE,sourceTitle="校内通知";
    private volatile int generation=0;
    private volatile long foregroundRequestGeneration;
    private int filter=0,visibleLimit=100;
    private boolean busy=false,detailVisible=false,clearingSession=false,attemptedAutoRefresh=false;
    private long refreshSequence;
    private String refreshingProfile="",refreshingSource="",refreshMessage="";
    private ProgressBar syncProgress;
    private boolean resumed=false,lastAccessReady=false,portalVerified=false,portalPrompted=false;
    private final DeferredPortalRefresh deferredPortalRefresh=new DeferredPortalRefresh();
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private boolean portalLoginInFlight;
    private long pausedAt;
    private final Runnable readiness=new Runnable(){@Override public void run(){
        if(!resumed)return;
        boolean ready=CampusApplication.auth().isAccessReady();
        if(ready&&!lastAccessReady){applyAccount();if(!detailVisible)loadCache();}
        if(!ready&&lastAccessReady){portalVerified=false;message("校园通道已断开，请重新完成校园连接。缓存仍可阅读。");}
        lastAccessReady=ready;updateConnectionStatus();if(ready)drainPortalRefresh();mainHandler.postDelayed(this,1000);
    }};
    private CampusPortalParser.MoreRequest nextPage;
    private TextView status,heading,connectionStatus,connectionHint;
    private Button refresh,more,portalButton;
    private ListView list;
    private TextView emptyTitle,emptyHint;
    private Button emptyAction,emptyMore;
    private NoticeAdapter adapter;
    private WebView detailWeb;
    private DrawerLayout nativeDrawer;
    private ScrollView drawerScroll;
    private LinearLayout drawerPanel;
    private Toolbar toolbar;
    private SwipeRefreshLayout swipeRefresh;
    private String viewMode;
    private long matchingCount;
    private int drawerSequence;
    private TextView allCount,laterCount;
    private EditText searchInput;
    private boolean cardStyle,changingSearch;
    private Runnable pendingSearch;
    private int savedListPosition,savedListTop;
    private String savedListScope="";
    private String listScope="";
    private boolean restoreListPending;
    private String lastMessage="已缓存的通知可离线阅读。";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(ReaderUi.SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        preferences=getSharedPreferences("reader-local",MODE_PRIVATE);
        viewMode=preferences.getString("reader-view-mode",preferences.getBoolean("reader-card-style",true)?"magazine":"list");
        cardStyle="card".equals(viewMode);
        profile=preferences.getString(ReaderPreferences.lastProfileKey(),"local");
        libraryFilter=ReaderFilterState.load(preferences,profile);
        keywordRules=KeywordRules.load(this,profile);
        ReaderNotifications.initialize(this);RefreshScheduler.schedule(this);
        loadSubscriptions();
        acceptNotification(getIntent());
        showList();
        if(state==null&&!CampusSite.isConfigured())mainHandler.post(()->{if(!isFinishing())openCampusConfiguration();});
    }
    @Override protected void onResume() {
        super.onResume();
        if(CampusConfiguration.requiresRestart()){
            new AlertDialog.Builder(this).setTitle("配置已保存")
                .setMessage("请关闭并重新打开应用，使新的校园配置生效。")
                .setCancelable(false).setPositiveButton("关闭应用",(dialog,which)->closeForConfiguration()).show();
            return;
        }
        if(pausedAt>0&&android.os.SystemClock.elapsedRealtime()-pausedAt>=60_000L){attemptedAutoRefresh=false;portalPrompted=false;}
        resumed=true;mainHandler.removeCallbacks(readiness);mainHandler.post(readiness);
        applyAccount();
        KeywordRules updated=KeywordRules.load(this,profile);boolean changed=!updated.fingerprint().equals(keywordRules.fingerprint());keywordRules=updated;
        if(changed){generation++;savedListPosition=0;savedListTop=0;if(!detailVisible){showList();return;}}
        if(!detailVisible)loadCache();
    }
    @Override protected void onPause(){foregroundRequestGeneration++;pausedAt=android.os.SystemClock.elapsedRealtime();resumed=false;mainHandler.removeCallbacks(readiness);super.onPause();}
    private void applyAccount() {
        String account=CampusApplication.auth().getProfileId();
        if(CampusApplication.auth().isAccessReady()&&account!=null&&!account.trim().isEmpty()) {
            String current=MediaSafety.readerProfile(account);
            if(!current.equals(profile)) {
                new ReaderPreferences(this).clearPortalSession();
                profile=current;keywordRules=KeywordRules.load(this,profile);priorityOnly=false;source=CampusPortalParser.SOURCE;sourceTitle="校内通知";filter=0;visibleLimit=100; preferences.edit().putString(ReaderPreferences.lastProfileKey(),profile).apply();
                libraryFilter=ReaderFilterState.load(preferences,profile);
                generation++;refreshSequence++;busy=false;refreshingProfile="";refreshingSource="";refreshMessage=""; nextPage=null; rows.clear(); detailVisible=false;attemptedAutoRefresh=false;portalVerified=false;portalPrompted=false;clearingSession=true;showList();
                // WebView has one app-wide cookie store: never reuse account A's portal SSO for account B.
                CookieManager.getInstance().removeAllCookies(removed->{clearingSession=false;CookieManager.getInstance().flush();updateConnectionStatus();if(!isDestroyed()){boolean started=drainPortalRefresh();if(!started&&!deferredPortalRefresh.hasRequest()&&!busy&&CampusPortalParser.SOURCE.equals(source))refresh(false);}});
            }
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==PORTAL){
            portalLoginInFlight=false;
            if(result==RESULT_OK&&data!=null)refreshAfterPortalLogin(data.getStringExtra(PortalLoginActivity.EXTRA_PROFILE));
            else message("门户恢复未完成，缓存可继续阅读；可从侧栏重新登录。");
            return;
        }
        if(request==CONFIG){
            if(configurationLease!=null){configurationLease.close();configurationLease=null;}
            if(result==RESULT_FIRST_USER){closeForConfiguration();return;}
            RefreshScheduler.schedule(this);
            if(result==RESULT_OK){
                ReaderNotifications.clearAll(this);
                new ReaderPreferences(this).clearPortalSession();
                profile=preferences.getString(ReaderPreferences.lastProfileKey(),"local");
                libraryFilter=ReaderFilterState.load(preferences,profile);keywordRules=KeywordRules.load(this,profile);
                generation++;refreshSequence++;nextPage=null;rows.clear();portalVerified=false;portalPrompted=false;
                attemptedAutoRefresh=false;showList();connect();
            }
            return;
        }
        if(request==AUTH&&result==RESULT_OK) {
            String account=CampusApplication.auth().getProfileId();
            if(account!=null&&!account.trim().isEmpty()) {
                source=CampusPortalParser.SOURCE;sourceTitle="校内通知";priorityOnly=false;filter=0;applyAccount();
                showList();if(!clearingSession)refresh(false);
            }
        }
    }
    private void connect(){if(!CampusConfiguration.isConfigured()){openCampusConfiguration();return;}startActivityForResult(new Intent(this,CampusAuthActivity.class),AUTH);}
    private void openCampusConfiguration(){
        if(configurationLease!=null)return;
        if(busy||clearingSession){message("请等待当前刷新或登录处理完成后再配置。");return;}
        configurationLease=SyncGate.tryAcquire("configuration");
        if(configurationLease==null){message("后台正在刷新，请稍后再配置。");return;}
        // Preview/cancel must preserve the live portal session. The lease alone pauses sync.
        startActivityForResult(new Intent(this,CampusConfigActivity.class),CONFIG);
    }
    private void closeForConfiguration(){
        RefreshScheduler.cancel(this);ReaderNotifications.clearAll(this);finishAffinity();
        mainHandler.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),150);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);acceptNotification(intent);showList();}
    private void acceptNotification(Intent intent){if(intent!=null&&intent.getBooleanExtra(ReaderNotifications.EXTRA_UNREAD,false)&&profile.equals(intent.getStringExtra(ReaderNotifications.EXTRA_PROFILE))){source=CampusPortalParser.SOURCE;priorityOnly=intent.getBooleanExtra(ReaderNotifications.EXTRA_PRIORITY,false);sourceTitle=priorityOnly?"重点关注":"校内通知";filter=1;libraryFilter=new ReaderFilterState();libraryFilter.save(preferences,profile);}}
    private void rememberListPosition(){if(list!=null&&!detailVisible){savedListScope=listScope;savedListPosition=list.getFirstVisiblePosition();View first=list.getChildAt(0);savedListTop=first==null?0:first.getTop();}}
    private String scope(){return profile+"|"+source+"|"+priorityOnly+"|"+keywordRules.fingerprint()+"|"+filter+"|"+libraryFilter.search+"|"+libraryFilter.include+"|"+libraryFilter.exclude+"|"+libraryFilter.tags;}
    protected void showList() {
        rememberListPosition();detailVisible=false;
        if(detailWeb!=null){detailWeb.destroy();detailWeb=null;}
        if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);
        setContentView(R.layout.reader_activity_reader);
        nativeDrawer=findViewById(R.id.reader_drawer_layout);
        toolbar=findViewById(R.id.reader_toolbar);
        toolbar.setTitle(priorityOnly?"重点关注":filter==2?"稍后读":sourceTitle);toolbar.setTitleTextColor(INK);
        toolbar.setNavigationIcon(ReaderUi.icon(this,"menu",INK));toolbar.setNavigationContentDescription("打开导航");toolbar.setNavigationOnClickListener(v->showSources());
        toolbar.getMenu().clear();
        toolbar.getMenu().add(0,1,0,"阅读选项").setIcon(ReaderUi.icon(this,"eye",INK)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.getMenu().add(0,2,1,"搜索通知").setIcon(ReaderUi.icon(this,"search",INK)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.getMenu().add(0,3,2,"标为已读").setIcon(ReaderUi.icon(this,"check",INK)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.setOnMenuItemClickListener(item->{if(item.getItemId()==1)showReadingMenu(toolbar);else if(item.getItemId()==2)toggleSearch();else markDisplayedRead();return true;});
        FrameLayout frame=findViewById(R.id.reader_main_content);LinearLayout root=column();root.setBackgroundColor(ReaderUi.SURFACE);frame.addView(root,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout searchRow=row();searchRow.setPadding(dp(16),0,dp(8),0);searchRow.setBackgroundColor(ReaderUi.SURFACE);
        searchInput=new EditText(this);searchInput.setSingleLine(true);searchInput.setTextSize(16);searchInput.setHint("搜索标题及缓存正文");searchInput.setTextColor(INK);searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);searchInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});searchInput.setText(libraryFilter.search);searchRow.addView(searchInput,new LinearLayout.LayoutParams(0,dp(48),1));
        searchRow.addView(icon("close","清空并收起搜索",v->{searchInput.setText("");searchRow.setVisibility(View.GONE);hideKeyboard();}),new LinearLayout.LayoutParams(dp(48),dp(48)));searchRow.setVisibility(libraryFilter.search.isEmpty()?View.GONE:View.VISIBLE);root.addView(searchRow);
        searchInput.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence t,int start,int count,int after){}public void onTextChanged(CharSequence t,int start,int before,int count){if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);final String value=t.toString().trim();pendingSearch=()->{libraryFilter.search=value;libraryFilter.save(preferences,profile);generation++;savedListPosition=0;savedListTop=0;loadCache();};mainHandler.postDelayed(pendingSearch,250);}public void afterTextChanged(android.text.Editable text){}});
        searchInput.setOnEditorActionListener((v,action,event)->{hideKeyboard();return true;});
        list=new ListView(this);list.setBackgroundColor(ReaderUi.SURFACE);list.setClipToPadding(false);list.setDivider(new android.graphics.drawable.ColorDrawable(ReaderUi.LINE));list.setDividerHeight(dp(1));list.setSelector(new android.graphics.drawable.ColorDrawable(0xffedf3fb));
        LinearLayout footer=column();footer.setPadding(dp(16),dp(12),dp(16),dp(12));heading=text("",12,MUTED);heading.setGravity(Gravity.CENTER);footer.addView(heading);more=button("加载更多",v->loadMoreNotices());footer.addView(more,new LinearLayout.LayoutParams(-1,dp(48)));list.addFooterView(footer,null,false);
        adapter=new NoticeAdapter();list.setAdapter(adapter);listScope=scope();
        LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(28),dp(24),dp(28),dp(24));emptyTitle=text("",20,INK);emptyTitle.setGravity(Gravity.CENTER);empty.addView(emptyTitle);emptyHint=text("",14,MUTED);emptyHint.setGravity(Gravity.CENTER);emptyHint.setPadding(0,dp(12),0,dp(18));empty.addView(emptyHint);emptyAction=button("",null);empty.addView(emptyAction,new LinearLayout.LayoutParams(-2,dp(48)));
        emptyMore=button("加载更多",v->loadMoreNotices());empty.addView(emptyMore,new LinearLayout.LayoutParams(-2,dp(48)));
        FrameLayout content=new FrameLayout(this);content.addView(list,new FrameLayout.LayoutParams(-1,-1));content.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);
        swipeRefresh=new SwipeRefreshLayout(this);swipeRefresh.setColorSchemeColors(BLUE);swipeRefresh.addView(content);swipeRefresh.setOnChildScrollUpCallback((parent,child)->list.canScrollVertically(-1));swipeRefresh.setOnRefreshListener(()->{if(!busy){refresh(false);updateBusy();}else swipeRefresh.setRefreshing(true);});root.addView(swipeRefresh,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((p,v,pos,id)->{if(pos<rows.size()){rememberListPosition();openNotice(rows.get(pos).content.key);}});list.setOnItemLongClickListener((p,v,pos,id)->{if(pos>=rows.size())return false;rowActions(rows.get(pos));return true;});
        View drawerHost=findViewById(R.id.reader_drawer_content);if(drawerHost instanceof ScrollView)drawerScroll=(ScrollView)drawerHost;else{drawerScroll=new ScrollView(this);((android.view.ViewGroup)drawerHost).addView(drawerScroll);}
        drawerPanel=column();drawerPanel.setBackgroundColor(ReaderUi.SURFACE);drawerScroll.addView(drawerPanel);populateDrawer();
        nativeDrawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener(){@Override public void onDrawerOpened(View view){hideKeyboard();populateDrawer();}@Override public void onDrawerSlide(View view,float offset){if(offset>0)hideKeyboard();}});
        refresh=null;syncProgress=null;restoreListPending=true;updateBusy();updateConnectionStatus();loadCache();
    }

    private ImageButton icon(String name,String description,View.OnClickListener listener){return ReaderUi.iconButton(this,name,description,listener);}
    private void hideKeyboard(){((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);}
    private void toggleSearch(){if(searchInput==null)return;View parent=(View)searchInput.getParent();if(parent.getVisibility()==View.VISIBLE){parent.setVisibility(View.GONE);hideKeyboard();}else{parent.setVisibility(View.VISIBLE);searchInput.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(searchInput,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}}
    private void setCardStyle(boolean value){setViewMode(value?"card":"list");}
    private void setViewMode(String value){viewMode=value;cardStyle="card".equals(value);preferences.edit().putString("reader-view-mode",value).putBoolean("reader-card-style",cardStyle).apply();showList();}
    private void clearFilters(){libraryFilter=new ReaderFilterState();libraryFilter.save(preferences,profile);generation++;showList();}
    private void updateEmptyState(){
        if(emptyTitle==null)return;boolean filtered=libraryFilter.active()||filter!=0;
        if(keywordRules.loadFailed){emptyTitle.setText("关键词规则暂时无法读取");emptyHint.setText("列表已暂停显示，缓存和阅读状态仍保留。可在关键词规则中重试读取或恢复上一版。");emptyAction.setText("检查关键词规则");emptyAction.setEnabled(true);emptyAction.setAlpha(1f);emptyAction.setOnClickListener(v->openKeywordRules());emptyMore.setVisibility(View.GONE);return;}
        if(CampusPortalParser.SOURCE.equals(source)&&!CampusConfiguration.isConfigured()){
            emptyTitle.setText("先配置校园访问");emptyHint.setText("填写校园入口和门户地址后，依次完成校园认证与信息门户登录。");
            emptyAction.setText("校园配置");emptyAction.setEnabled(true);emptyAction.setAlpha(1f);
            emptyAction.setOnClickListener(v->openCampusConfiguration());emptyMore.setVisibility(View.GONE);return;
        }
        emptyMore.setVisibility(CampusPortalParser.SOURCE.equals(source)&&nextPage!=null?View.VISIBLE:View.GONE);emptyMore.setEnabled(!busy);emptyMore.setAlpha(busy?.4f:1f);
        emptyAction.setEnabled(filtered||priorityOnly||keywordRules.hasBlocked()||!busy);emptyAction.setAlpha(emptyAction.isEnabled()?1f:.4f);
        emptyTitle.setText(priorityOnly?"暂无重点通知":filtered||keywordRules.hasBlocked()?"没有匹配的文章":"暂无通知");
        emptyHint.setText(priorityOnly?(keywordRules.hasPriority()?"当前缓存中没有匹配内容，可刷新或继续加载。屏蔽规则仍然优先。":"设置优先提醒词后，可在这里查看匹配内容。屏蔽规则仍然优先。"):keywordRules.hasBlocked()?"屏蔽词已应用。可在侧栏调整关键词规则；内容和阅读状态仍保留。":filtered?"切换阅读选项，或清除搜索与标签筛选。":"下拉刷新获取通知；校园连接入口位于侧栏。");
        emptyAction.setText(priorityOnly||keywordRules.hasBlocked()?"关键词规则":filtered?"显示全部通知":"刷新通知");
        emptyAction.setOnClickListener(v->{if(priorityOnly||keywordRules.hasBlocked())openKeywordRules();else if(filtered){filter=0;clearFilters();}else refresh(false);});
    }
    private void openKeywordRules(){startActivity(new Intent(this,KeywordRulesActivity.class).putExtra("profile",profile));}
    private void showPriority(){priorityOnly=true;source=CampusPortalParser.SOURCE;sourceTitle="重点关注";filter=0;libraryFilter=new ReaderFilterState();libraryFilter.save(preferences,profile);visibleLimit=100;nextPage=null;generation++;showList();}
    private void showReadingMenu(View anchor){
        View options=getLayoutInflater().inflate(R.layout.reader_art_options,null);
        PopupWindow popup=new PopupWindow(options,Math.min(dp(308),getResources().getDisplayMetrics().widthPixels-dp(24)),-2,true);popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ReaderUi.SURFACE));popup.setElevation(dp(8));popup.setOutsideTouchable(true);
        int[] filters={R.id.reader_all_txt,R.id.reader_unread_txt,R.id.reader_star_txt};for(int i=0;i<filters.length;i++){final int index=i;TextView choice=options.findViewById(filters[i]);choice.setTextColor(filter==i?BLUE:INK);choice.setBackgroundColor(filter==i?0xffeaf1ff:Color.TRANSPARENT);choice.setOnClickListener(v->{popup.dismiss();filter=index;generation++;showList();});}
        int[] modes={R.id.reader_list_button,R.id.reader_magazine_button,R.id.reader_card_button};String[] modeNames={"list","magazine","card"};for(int i=0;i<modes.length;i++){final String mode=modeNames[i];View choice=options.findViewById(modes[i]);choice.setSelected(viewMode.equals(mode));choice.setBackgroundColor(viewMode.equals(mode)?0xffeaf1ff:Color.TRANSPARENT);choice.setOnClickListener(v->{popup.dismiss();setViewMode(mode);});}
        options.findViewById(R.id.reader_item_settings).setOnClickListener(v->{popup.dismiss();showLocalReadingActions();});
        popup.showAtLocation(getWindow().getDecorView(),Gravity.TOP|Gravity.END,dp(8),dp(80));
    }
    private void showLocalReadingActions(){new AlertDialog.Builder(this).setTitle("阅读设置").setItems(new String[]{"刷新通知","搜索与标签筛选","清除当前筛选","阅读与缓存设置"},(d,i)->{if(i==0)refresh(false);else if(i==1)showFilterDialog();else if(i==2){filter=0;clearFilters();}else openSettings();}).setNegativeButton("返回",null).show();}
    private void markDisplayedRead(){
        List<NoticeKey> keys=new ArrayList<>();for(CachedNotice row:rows)if(!row.read)keys.add(row.content.key);
        if(keys.isEmpty()){Toast.makeText(this,"当前列表没有未读文章",Toast.LENGTH_SHORT).show();return;}
        final String p=profile;final int token=generation;new AlertDialog.Builder(this).setTitle("标为已读").setMessage("将当前已加载列表中的 "+keys.size()+" 篇未读文章标为已读？稍后读和标签保留。").setPositiveButton("标为已读",(d,w)->worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){for(NoticeKey key:keys)store.setRead(key,true);ui(token,()->{loadCache();populateDrawer();});}catch(Exception e){ui(token,()->message("部分阅读状态未能保存，请刷新列表确认。"));}})).setNegativeButton("取消",null).show();
    }

    private String statusText(){return busy&&profile.equals(refreshingProfile)&&source.equals(refreshingSource)?refreshMessage:lastMessage;}
    private void renderStatus(){if(status!=null&&!detailVisible)status.setText(statusText());}
    private void updateBusy(){
        if(swipeRefresh!=null&&!detailVisible)swipeRefresh.setRefreshing(busy&&source.equals(refreshingSource));
        if(more!=null){more.setEnabled(!busy);more.setAlpha(busy?.4f:1f);more.setText(busy?"正在更新…":"加载更多");}
        updateEmptyState();updateConnectionStatus();renderStatus();
    }
    private void updateConnectionStatus(){
        if(connectionStatus==null)return;
        boolean checking=busy&&profile.equals(refreshingProfile)&&CampusPortalParser.SOURCE.equals(refreshingSource);
        CampusConnectionStatus.PortalState portal=checking?CampusConnectionStatus.PortalState.CHECKING:
                deferredPortalRefresh.hasRequest()||clearingSession?CampusConnectionStatus.PortalState.WAITING:
                portalVerified?CampusConnectionStatus.PortalState.VERIFIED:CampusConnectionStatus.PortalState.UNKNOWN;
        CampusConnectionStatus view=CampusApplication.auth().connectionStatus(portal);
        connectionStatus.setText(view.title);
        if(connectionHint!=null){long last=new ReaderPreferences(this).getLastSuccessfulMillis(profile);
            connectionHint.setText(view.detail+(last>0?"\n上次完整同步："+android.text.format.DateFormat.format("MM-dd HH:mm",last):"\n尚未完成一次同步"));}
        if(portalButton!=null){boolean enabled=!busy&&!clearingSession&&view.action!=CampusConnectionStatus.Action.WAIT;
            portalButton.setEnabled(enabled);portalButton.setAlpha(enabled?1f:.38f);portalButton.setText(view.actionLabel);
            portalButton.setOnClickListener(v->{closeDrawer();switch(view.action){
                case CONFIGURE:openCampusConfiguration();break;case RESTART:closeForConfiguration();break;
                case CONNECT:connect();break;case CHECK_PORTAL:case REFRESH:checkCampusPortal();break;
                case LOGIN_PORTAL:portalLogin();break;default:break;
            }});
        }
    }

    private void message(String value){lastMessage=value;renderStatus();if(resumed&&!detailVisible&&nativeDrawer!=null&&!nativeDrawer.isDrawerOpen(androidx.core.view.GravityCompat.START))Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private void refreshUi(long requestId,String requestProfile,Runnable action){runOnUiThread(()->{if(!isDestroyed()&&requestId==refreshSequence&&requestProfile.equals(profile))action.run();});}

    private void closeDrawer(){if(nativeDrawer!=null)nativeDrawer.closeDrawer(androidx.core.view.GravityCompat.START);}
    void checkCampusPortal(){
        if(!CampusPortalParser.SOURCE.equals(source))selectSource(CampusPortalParser.SOURCE,"校内通知",0);
        refresh(false);
    }
    private void drawerSection(LinearLayout panel,String value){TextView label=text(value,12,MUTED);label.setPadding(dp(16),dp(20),dp(16),dp(8));panel.addView(label);}
    private TextView drawerItem(LinearLayout panel,String image,String value,boolean selected,Runnable action){
        View item=getLayoutInflater().inflate(R.layout.reader_item_list_group,panel,false);ImageView symbol=item.findViewById(R.id.reader_image);symbol.setImageDrawable(ReaderUi.icon(this,image,selected?BLUE:MUTED));TextView label=item.findViewById(R.id.reader_text);label.setText(value);label.setTextColor(selected?BLUE:INK);TextView count=item.findViewById(R.id.reader_textCount);count.setText("");item.findViewById(R.id.reader_ll_selected).setBackgroundColor(BLUE);item.findViewById(R.id.reader_ll_selected).setVisibility(selected?View.VISIBLE:View.INVISIBLE);ReaderUi.selectable(item,selected?ReaderUi.TINT:ReaderUi.SURFACE);item.setContentDescription(value);item.setOnClickListener(v->{closeDrawer();action.run();});panel.addView(item);return count;
    }
    private void selectSource(String key,String title,int selectedFilter){priorityOnly=false;source=key;sourceTitle=title;filter=selectedFilter;visibleLimit=100;nextPage=null;libraryFilter=new ReaderFilterState();libraryFilter.save(preferences,profile);generation++;showList();}
    private void showSources(){if(nativeDrawer!=null&&!detailVisible){hideKeyboard();if(nativeDrawer.isDrawerOpen(androidx.core.view.GravityCompat.START))closeDrawer();else nativeDrawer.openDrawer(androidx.core.view.GravityCompat.START);}}
    private void populateDrawer(){
        if(drawerPanel==null||detailVisible)return;final int drawerRequest=++drawerSequence;drawerPanel.removeAllViews();
        View header=getLayoutInflater().inflate(R.layout.reader_list_header,drawerPanel,false);((TextView)header.findViewById(R.id.reader_user_name)).setText("校园阅读");((TextView)header.findViewById(R.id.reader_user_email)).setText("本地阅读");((ImageView)header.findViewById(R.id.reader_user_image)).setImageDrawable(ReaderUi.icon(this,"book",BLUE));header.findViewById(R.id.reader_image_drop).setVisibility(View.GONE);drawerPanel.addView(header);
        allCount=drawerItem(drawerPanel,"book","全部校内通知",CampusPortalParser.SOURCE.equals(source)&&filter==0&&!priorityOnly&&libraryFilter.tags.isEmpty()&&sourceTitle.equals("全部校内通知"),()->selectSource(CampusPortalParser.SOURCE,"全部校内通知",0));
        laterCount=drawerItem(drawerPanel,"star","稍后读",filter==2&&!priorityOnly,()->selectSource(CampusPortalParser.SOURCE,"稍后读",2));
        priorityCount=drawerItem(drawerPanel,"eye","重点关注",priorityOnly,()->showPriority());
        drawerItem(drawerPanel,"settings","关键词规则",false,()->openKeywordRules());
        drawerItem(drawerPanel,"refresh","刷新与提醒记录",false,()->startActivity(new Intent(this,NotificationHistoryActivity.class).putExtra(NotificationHistoryActivity.EXTRA_PROFILE,profile)));
        drawerItem(drawerPanel,"summary","AI 摘要服务",false,()->startActivity(new Intent(this,AiSettingsActivity.class).putExtra(AiSettingsActivity.EXTRA_PROFILE,profile)));
        drawerSection(drawerPanel,"订阅");drawerItem(drawerPanel,"feed","校内通知",CampusPortalParser.SOURCE.equals(source)&&filter!=2&&!priorityOnly&&libraryFilter.tags.isEmpty()&&!sourceTitle.equals("全部校内通知"),()->selectSource(CampusPortalParser.SOURCE,"校内通知",0));for(Subscription subItem:subscriptions)drawerItem(drawerPanel,"feed",subItem.title,RssParser.source(subItem.url).equals(source),()->selectSource(RssParser.source(subItem.url),subItem.title,0));drawerItem(drawerPanel,"link","添加 / 管理订阅",false,()->subscriptionMenu());
        drawerSection(drawerPanel,"标签");LinearLayout tagsPanel=column();drawerPanel.addView(tagsPanel);drawerItem(drawerPanel,"tag","管理标签",false,()->manageTags());
        drawerSection(drawerPanel,"校园访问");connectionStatus=text("",12,MUTED);connectionStatus.setPadding(dp(16),0,dp(16),dp(8));drawerPanel.addView(connectionStatus);
        connectionHint=text("",12,MUTED);connectionHint.setPadding(dp(16),0,dp(16),dp(8));drawerPanel.addView(connectionHint);
        drawerItem(drawerPanel,"settings","学校与校园配置",false,()->openCampusConfiguration());
        drawerItem(drawerPanel,"link","校园认证",false,()->connect());
        portalButton=button("检查 / 登录信息门户",v->{closeDrawer();portalLogin();});portalButton.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);portalButton.setPadding(dp(56),0,dp(16),0);drawerPanel.addView(portalButton,new LinearLayout.LayoutParams(-1,dp(48)));
        drawerItem(drawerPanel,"refresh","刷新通知",false,()->refresh(false));
        status=text(statusText(),12,MUTED);status.setPadding(dp(16),dp(4),dp(16),dp(12));drawerPanel.addView(status);
        View separator=new View(this);separator.setBackgroundColor(ReaderUi.LINE);drawerPanel.addView(separator,new LinearLayout.LayoutParams(-1,dp(1)));drawerItem(drawerPanel,"download","下载文件",false,()->MediaActions.viewDownloads(this));View settingsRow=getLayoutInflater().inflate(R.layout.reader_list_footer,drawerPanel,false);((TextView)settingsRow.findViewById(R.id.reader_text_settings)).setText("设置");settingsRow.findViewById(R.id.reader_ll_settings).setOnClickListener(v->{closeDrawer();openSettings();});drawerPanel.addView(settingsRow);
        final int token=generation;final String p=profile;final LinearLayout current=drawerPanel;final KeywordRules rules=keywordRules;worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){List<NoticeTag> tags=store.listTags();long unread=store.count(rules.apply(NoticeQuery.builder(CampusPortalParser.SOURCE).unreadOnly(true),false).build()),saved=store.count(rules.apply(NoticeQuery.builder(CampusPortalParser.SOURCE).laterOnly(true),false).build()),focused=store.count(rules.apply(NoticeQuery.builder(CampusPortalParser.SOURCE).unreadOnly(true),true).build());ui(token,()->{if(drawerPanel!=current||detailVisible||drawerRequest!=drawerSequence)return;allCount.setText(unread==0?"":Long.toString(unread));laterCount.setText(saved==0?"":Long.toString(saved));priorityCount.setText(focused==0?"":Long.toString(focused));for(NoticeTag tag:tags)drawerItem(tagsPanel,"tag",tag.name,libraryFilter.tags.contains(tag.id),()->{libraryFilter=new ReaderFilterState();libraryFilter.tags.add(tag.id);libraryFilter.save(preferences,profile);source=CampusPortalParser.SOURCE;sourceTitle=tag.name;priorityOnly=false;filter=0;generation++;showList();});});}catch(Exception ignored){}});
        updateConnectionStatus();
    }

    private void subscriptionMenu(){List<String> names=new ArrayList<>();for(Subscription s:subscriptions)names.add(s.title);names.add("添加 RSS / Atom 订阅");names.add("管理本地订阅");new AlertDialog.Builder(this).setTitle("其他订阅").setItems(names.toArray(new String[0]),(d,index)->{if(index<subscriptions.size()){Subscription s=subscriptions.get(index);selectSource(RssParser.source(s.url),s.title,0);}else if(index==subscriptions.size())addSubscription();else manageSubscriptions();}).setNegativeButton("返回",null).show();}
    private void loadCache() {
        final String p=profile,s=source,queryScope=scope();final int token=generation;final NoticeQuery query=libraryFilter.query(s,filter,Math.min(visibleLimit,1000),keywordRules,priorityOnly);
        worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)) {
            store.enforceRetention();List<CachedNotice> found=store.query(query);long total=store.count(query);
            CampusNoticeStore.ConnectionState connection=store.connectionState(s);
            ui(token,()->{if(detailVisible)return;rows.clear();rows.addAll(found);adapter.notifyDataSetChanged();
                updateEmptyState();
                if(connection==CampusNoticeStore.ConnectionState.REAUTH_REQUIRED&&!busy)message(deferredPortalRefresh.hasRequest()?"门户登录已返回，等待校园通道恢复后验证；缓存仍可阅读。":"信息门户需要单独登录，请点击“登录信息门户”。已缓存内容和阅读状态仍保留。");
                matchingCount=total;heading.setText("已显示 "+found.size()+" / "+total+" 篇"+(libraryFilter.active()?" · 已筛选":"")+(keywordRules.hasBlocked()?" · 已应用屏蔽词":""));more.setVisibility(total>found.size()||CampusPortalParser.SOURCE.equals(s)?View.VISIBLE:View.GONE);
                if(restoreListPending){restoreListPending=false;if(queryScope.equals(savedListScope))list.setSelectionFromTop(savedListPosition,savedListTop);else list.setSelection(0);}else if(!queryScope.equals(listScope))list.setSelection(0);listScope=queryScope;
                if(resumed&&new ReaderPreferences(this).isRefreshOnOpen()&&CampusPortalParser.SOURCE.equals(s)&&CampusApplication.auth().isAccessReady()&&!busy&&!clearingSession&&!portalLoginInFlight&&!attemptedAutoRefresh){attemptedAutoRefresh=true;refresh(false);}
            });
        }catch(Exception e){ui(token,()->message("读取本地缓存失败；未修改缓存。"));}try{ImageCacheMaintenance.prune(this,p);}catch(Exception ignored){}});
    }
    private void refreshAfterPortalLogin(String loginProfile) {
        if(loginProfile==null||!loginProfile.equals(profile)||campusState(loginProfile)==PortalBatchDownloader.State.ACCOUNT_CHANGED){message("校园账号已变化，请使用当前账号重新登录信息门户。");return;}
        if(!CampusPortalParser.SOURCE.equals(source)){source=CampusPortalParser.SOURCE;sourceTitle="校内通知";filter=0;generation++;showList();}
        deferredPortalRefresh.request(loginProfile);
        message(CampusApplication.auth().isAccessReady()?"门户登录已返回；正在等待当前请求结束后刷新通知…":"校园通道尚未恢复；恢复后将刷新通知，现有缓存仍可阅读。");
        drainPortalRefresh();
    }
    private boolean drainPortalRefresh() {
        if(isDestroyed()||!resumed||!deferredPortalRefresh.hasRequest()||SyncGate.isBusy())return false;
        if(deferredPortalRefresh.takeIfReady(profile,busy,clearingSession,campusState(profile)==PortalBatchDownloader.State.READY)){
            if(!CampusPortalParser.SOURCE.equals(source)){source=CampusPortalParser.SOURCE;sourceTitle="校内通知";filter=0;generation++;showList();}
            refresh(false);return true;
        }
        return false;
    }
    protected void refresh(boolean loadMore) {
        if(busy||clearingSession)return;
        final boolean campus=CampusPortalParser.SOURCE.equals(source);
        if(campus&&!CampusConfiguration.isConfigured()){openCampusConfiguration();return;}
        if(campus){applyAccount();if(clearingSession)return;}
        if(campus&&!CampusApplication.auth().isAccessReady()){message("校园通道未就绪，请从校园连接检查；缓存仍可阅读。");return;}
        if(campus)attemptedAutoRefresh=true;
        if(loadMore&&!campus){visibleLimit=Math.min(1000,visibleLimit+100);loadCache();return;}
        if(loadMore&&nextPage==null){message("请先刷新通知；当前分页结束时无需继续加载。");return;}
        final String p=profile,s=source;final long requestId=++refreshSequence;final CampusPortalParser.MoreRequest request=loadMore?nextPage:null;
        final Subscription subscription=subscription(s);
        refreshingProfile=p;refreshingSource=s;refreshMessage=loadMore?"正在加载下一页…":campus?"正在检查门户…":"正在更新订阅…";busy=true;updateBusy();
        network.execute(()->{
            try {
                if(campus) {
                    if(request!=null){try(CampusNoticeStore store=new CampusNoticeStore(this,p)){int offset=Integer.parseInt(request.parameters.get("start"));if(offset>=store.getRetentionPolicy().maxCachedEntries){refreshUi(requestId,p,()->message("已达到本轮加载上限，可在设置中调整正文条数。"));return;}}}
                    CampusSyncEngine.Result result=CampusSyncEngine.syncPage(this,p,request,false,()->isDestroyed(),
                            (completed,total)->refreshUi(requestId,p,()->{portalVerified=true;CampusApplication.auth().clearPortalAuthenticationRequired();refreshMessage="更新通知 "+completed+" / "+total;updateConnectionStatus();renderStatus();}));
                    refreshUi(requestId,p,()->{
                        if(campusState(p)==PortalBatchDownloader.State.ACCOUNT_CHANGED)return;
                        nextPage=result.nextPage;if(s.equals(source))visibleLimit=Math.min(1000,visibleLimit+result.saved);
                        switch(result.status){
                            case SUCCESS: case PARTIAL:
                                portalVerified=true;CampusApplication.auth().clearPortalAuthenticationRequired();
                                message("已更新 "+result.saved+" 条，新增 "+result.newCount+" 条"+(result.failed>0?"；部分内容未完成，可重试当前页":"。"));break;
                            case PORTAL_LOGIN_REQUIRED:
                                portalVerified=false;CampusApplication.auth().markPortalAuthenticationRequired();message("门户登录已过期，请登录信息门户；缓存及标签保留。");if(s.equals(source)&&!detailVisible)promptPortalLogin();break;
                            case BUSY:
                                if(loadMore)message("后台正在刷新，请稍后再点加载更多；当前页已保留。");
                                else{deferredPortalRefresh.request(p);message("后台正在刷新，完成后将检查门户并更新通知。");}
                                break;
                            case TUNNEL_REQUIRED:portalVerified=false;message("校园通道暂不可用，已缓存内容仍可阅读。");break;
                            case CANCELLED:case ACCOUNT_CHANGED:message("本次更新已停止，已获取内容保留，可稍后重试。");break;
                            case PARSE_ERROR:message("暂时无法识别门户内容，已缓存内容仍可阅读。");break;
                            default:message("更新未完成，请检查网络后重试；已缓存内容仍可阅读。");
                        }
                    });
                } else {
                    if(subscription==null)throw new IOException("Subscription no longer exists");
                    ReaderHttp.Response response=ReaderHttp.get(subscription.url,5*1024*1024,false);
                    if(response.status!=200)throw new IOException("Feed HTTP "+response.status);
                    RssParser.Feed feed=RssParser.parse(subscription.url,response.url,response.text());
                    try(CampusNoticeStore store=new CampusNoticeStore(this,p)){store.upsert(feed.items,System.currentTimeMillis());}
                    ImageCacheMaintenance.prune(this,p);
                    refreshUi(requestId,p,()->message("订阅已更新 "+feed.items.size()+" 篇；阅读状态与标签保留。"));
                }
            }catch(Exception error){refreshUi(requestId,p,()->message("更新未完成，请检查网络或登录；已缓存内容仍可阅读。"));}
            finally{refreshUi(requestId,p,()->{busy=false;refreshMessage="";updateBusy();if(!detailVisible)loadCache();drainPortalRefresh();});}
        });
    }
    private void loadMoreNotices(){if(busy)return;final String p=profile,s=source;final int token=generation;final NoticeQuery query=libraryFilter.query(s,filter,1000,keywordRules,priorityOnly);worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){long count=store.count(query);ui(token,()->{if(count>visibleLimit){if(visibleLimit>=1000){message("已显示1000条本地记录，请用关键词或标签缩小范围。");return;}visibleLimit=Math.min(1000,visibleLimit+100);loadCache();}else if(CampusPortalParser.SOURCE.equals(s))refresh(true);else message("已显示全部匹配的本地订阅文章，可刷新获取更新。");});}catch(Exception e){ui(token,()->message("本地记录读取失败"));}});}
    private PortalBatchDownloader.State campusState(String expectedProfile) {
        String account=CampusApplication.auth().getProfileId();
        if(account!=null&&!account.trim().isEmpty()&&!expectedProfile.equals(MediaSafety.readerProfile(account)))return PortalBatchDownloader.State.ACCOUNT_CHANGED;
        return account!=null&&!account.trim().isEmpty()&&CampusApplication.auth().isAccessReady()?PortalBatchDownloader.State.READY:PortalBatchDownloader.State.TUNNEL_DISCONNECTED;
    }
    private void requireCampus(String expectedProfile) throws IOException {
        if(campusState(expectedProfile)!=PortalBatchDownloader.State.READY)throw new IOException("Campus authentication required");
    }
    private void openNotice(NoticeKey key) {
        final String p=profile;final int token=generation;
        worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){CachedNotice cached=store.find(key);if(cached!=null)ui(token,()->showDetail(cached));}catch(Exception e){ui(token,()->message("无法打开缓存正文。"));}});
    }
    private void showDetail(CachedNotice cached) {
        showDetail(cached,cached.bodyCached?cached.content:null);
    }
    private void showDetail(CachedNotice cached,CampusNotice displayed) {
        rememberListPosition();closeDrawer();if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);
        detailVisible=true;final CampusNotice notice=displayed==null?cached.content:displayed;final String p=profile;final int token=generation;final boolean hasBody=displayed!=null&&!displayed.bodyHtml.trim().isEmpty();
        final boolean[] flags={cached.read,cached.later};final boolean[] opened={false};
        LinearLayout root=column();root.setBackgroundColor(ReaderUi.SURFACE);
        LinearLayout top=row();top.addView(icon("close","返回通知列表",v->showList()),new LinearLayout.LayoutParams(dp(48),dp(48)));TextView sourceName=text(CampusPortalParser.SOURCE.equals(notice.key.source)?"校内通知":sourceTitle,13,MUTED);sourceName.setGravity(Gravity.CENTER_VERTICAL);top.addView(sourceName,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(top);
        View bar=getLayoutInflater().inflate(R.layout.reader_reader_bottom_bar,root,false);Button unread=bar.findViewById(R.id.reader_btn_read),later=bar.findViewById(R.id.reader_btn_star);updateDetailButtons(unread,later,flags);
        unread.setOnClickListener(v->{opened[0]=true;flags[0]=!flags[0];updateDetailButtons(unread,later,flags);setFlag(notice.key,true,flags[0],null);});
        later.setOnClickListener(v->{flags[1]=!flags[1];updateDetailButtons(unread,later,flags);setFlag(notice.key,false,flags[1],null);});
        bar.findViewById(R.id.reader_btn_tag).setOnClickListener(v->editTags(notice.key));bar.findViewById(R.id.reader_btn_share).setOnClickListener(v->shareNotice(notice));bar.findViewById(R.id.reader_btn_more).setOnClickListener(v->showArticleMenu(cached,notice));
        Button summary=bar.findViewById(R.id.reader_btn_summary);summary.setCompoundDrawablesWithIntrinsicBounds(null,ReaderUi.icon(this,"summary",INK),null,null);summary.setOnClickListener(v->openSummary(notice.key));
        ((Button)bar.findViewById(R.id.reader_btn_tag)).setCompoundDrawablesWithIntrinsicBounds(null,ReaderUi.icon(this,"tag",INK),null,null);
        ((Button)bar.findViewById(R.id.reader_btn_more)).setCompoundDrawablesWithIntrinsicBounds(null,ReaderUi.icon(this,"more",INK),null,null);
        if(!hasBody)root.addView(button("正文已清理 · 联网重新获取",v->refetchBody(cached)),new LinearLayout.LayoutParams(-1,dp(48)));
        detailWeb=new WebView(this);detailWeb.setBackgroundColor(ReaderUi.SURFACE);WebSettings settings=detailWeb.getSettings();settings.setJavaScriptEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setTextZoom(preferences.getInt("reader-text-zoom",100));
        detailWeb.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){openArticleLink(notice,p,url);return true;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){openArticleLink(notice,p,request.getUrl().toString());return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                String url=request.getUrl().toString();if(!notice.imageUrls.contains(url))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                try{byte[] data=imageData(p,url);return new WebResourceResponse("image/*",null,new ByteArrayInputStream(data));}catch(Exception e){return new WebResourceResponse("image/png",null,new ByteArrayInputStream(new byte[0]));}
            }
            @Override public void onPageFinished(WebView view,String url){if(hasBody&&!opened[0]&&view==detailWeb&&token==generation&&p.equals(profile)){opened[0]=true;flags[0]=true;updateDetailButtons(unread,later,flags);setFlag(notice.key,true,true,null);}}
        });
        detailWeb.setOnLongClickListener(v->{WebView.HitTestResult hit=detailWeb.getHitTestResult();if(hit!=null&&(hit.getType()==WebView.HitTestResult.IMAGE_TYPE||hit.getType()==WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)){int index=notice.imageUrls.indexOf(hit.getExtra());if(index>=0){MediaActions.showImages(this,p,notice.imageUrls,index);return true;}}return false;});

        root.addView(detailWeb,new LinearLayout.LayoutParams(-1,0,1));root.addView(bar);setContentView(root);
        String attachments="";if(!notice.attachments.isEmpty()){StringBuilder links=new StringBuilder("<section class='attachments'><h2>附件</h2>");for(CampusNotice.Attachment a:notice.attachments)links.append("<p><a href='").append(escape(a.url)).append("'>").append(escape(a.name)).append("</a></p>");attachments=links.append("</section>").toString();}
        String html="<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'><meta charset='UTF-8'><style>body{font:17px/1.7 sans-serif;color:#222;margin:0;padding:16px;overflow-wrap:anywhere}h1{font-size:24px;line-height:1.4;font-weight:600;margin:8px 0 12px}h2{font-size:17px}a{color:#2670c3}img{max-width:100%;height:auto}table{max-width:100%;display:block;overflow-x:auto}iframe{display:none}.date{font-size:12px;color:#888}.attachments{margin-top:24px;border-top:1px solid #eee}</style></head><body><h1>"+escape(notice.title)+"</h1><p class='date'>"+date(notice.publishedAtMillis)+" · "+(CampusPortalParser.SOURCE.equals(notice.key.source)?"校内通知":escape(sourceTitle))+"</p>"+(hasBody?notice.bodyHtml:"<p>正文已按缓存规则清理。阅读状态、标签和原文地址仍保留。</p>")+attachments+"</body></html>";
        detailWeb.loadDataWithBaseURL(notice.originalUrl,html,"text/html","UTF-8",null);
    }
    private void updateDetailButtons(Button read,Button later,boolean[] flags){
        read.setText(flags[0]?"未读":"已读");read.setContentDescription(flags[0]?"标为未读":"标为已读");read.setTextColor(INK);
        read.setCompoundDrawablesWithIntrinsicBounds(null,ReaderUi.icon(this,flags[0]?"article_read":"article_unread",INK),null,null);
        later.setText("稍后读");later.setContentDescription(flags[1]?"取消稍后读":"稍后读");later.setTextColor(flags[1]?BLUE:INK);later.setCompoundDrawablesWithIntrinsicBounds(null,ReaderUi.icon(this,flags[1]?"article_star_filled":"article_star",flags[1]?BLUE:INK),null,null);
    }
    private void shareNotice(CampusNotice notice){Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");share.putExtra(Intent.EXTRA_SUBJECT,notice.title);share.putExtra(Intent.EXTRA_TEXT,notice.title+"\n"+notice.originalUrl);startActivity(Intent.createChooser(share,"分享文章"));}
    private void openSummary(NoticeKey key){startActivity(new Intent(this,AiSummaryActivity.class)
            .putExtra(AiSummaryActivity.EXTRA_PROFILE,profile).putExtra(AiSummaryActivity.EXTRA_SOURCE,key.source).putExtra(AiSummaryActivity.EXTRA_STABLE_ID,key.stableId));}
    private void showArticleMenu(CachedNotice cached,CampusNotice notice){
        List<String> names=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        names.add("打开原文");actions.add(()->openExternal(notice.originalUrl));names.add("正文字号");actions.add(()->showTextSize());
        names.add("AI 摘要");actions.add(()->openSummary(notice.key));
        names.add("分享通知");actions.add(()->shareNotice(notice));
        if(!notice.imageUrls.isEmpty()){names.add("查看图片（"+notice.imageUrls.size()+"）");actions.add(()->MediaActions.showImages(this,profile,notice.imageUrls,0));}
        if(!notice.attachments.isEmpty()){names.add("附件（"+notice.attachments.size()+"）");actions.add(()->showAttachments(notice));}
        if(!cached.bodyCached){names.add("重新获取正文");actions.add(()->refetchBody(cached));}
        new AlertDialog.Builder(this).setItems(names.toArray(new String[0]),(d,index)->actions.get(index).run()).setNegativeButton("返回",null).show();
    }
    private void showAttachments(CampusNotice notice){String[] names=new String[notice.attachments.size()];for(int i=0;i<names.length;i++)names[i]=notice.attachments.get(i).name;new AlertDialog.Builder(this).setTitle("附件").setItems(names,(d,index)->openArticleLink(notice,profile,notice.attachments.get(index).url)).setNegativeButton("返回",null).show();}
    private void openArticleLink(CampusNotice notice,String owner,String url){for(CampusNotice.Attachment a:notice.attachments)if(a.url.equals(url)){if(ReaderHttp.isCampus(a.url))MediaActions.downloadAttachment(this,owner,a.url,a.name,notice.originalUrl);else openExternal(a.url);return;}openExternal(url);}
    private void showTextSize(){int[] sizes={90,100,110,125,140};String[] names={"较小 · 90%","标准 · 100%","较大 · 110%","大 · 125%","特大 · 140%"};int selected=1;int current=preferences.getInt("reader-text-zoom",100);for(int i=0;i<sizes.length;i++)if(current==sizes[i])selected=i;new AlertDialog.Builder(this).setTitle("正文字号").setSingleChoiceItems(names,selected,(dialog,index)->{preferences.edit().putInt("reader-text-zoom",sizes[index]).apply();if(detailWeb!=null)detailWeb.getSettings().setTextZoom(sizes[index]);dialog.dismiss();}).setNegativeButton("返回",null).show();}
    private void refetchBody(CachedNotice cached){final WebView requestedPage=detailWeb;final String p=profile;final int token=generation;final boolean campus=CampusPortalParser.SOURCE.equals(cached.content.key.source);final Subscription feed=subscription(cached.content.key.source);
        if(campus&&!CampusApplication.auth().isAccessReady()){connect();return;}
        final ReaderPreferences requestPrefs=new ReaderPreferences(this);final long portalGeneration=requestPrefs.getPortalGeneration(),foregroundGeneration=foregroundRequestGeneration;
        final java.util.function.BooleanSupplier cancelled=()->token!=generation||foregroundGeneration!=foregroundRequestGeneration||(campus&&portalGeneration!=requestPrefs.getPortalGeneration());
        Toast.makeText(this,"正在重新获取正文…",Toast.LENGTH_SHORT).show();network.execute(()->{
            SyncGate.Lease lease=campus?SyncGate.tryAcquire(p):null;
            if(campus&&lease==null){ui(token,()->Toast.makeText(this,"正在刷新或认证，请稍后重新获取正文。",Toast.LENGTH_SHORT).show());return;}
            try(SyncGate.Lease ignored=lease;CampusNoticeStore store=new CampusNoticeStore(this,p)){
                ReaderHttp.RequestGuard guard=new ReaderHttp.RequestGuard(p,campus,Thread.currentThread(),cancelled);guard.check();CampusNotice notice=null;
                if(campus){ReaderHttp.Response response=ReaderHttp.get(cached.content.originalUrl,5*1024*1024,true,cancelled);guard.check();notice=parser.parseDetail(response.url,response.status,response.text()).notice;}
                else if(feed!=null){ReaderHttp.Response response=ReaderHttp.get(feed.url,5*1024*1024,false,cancelled);if(response.status!=200)throw new IOException();for(CampusNotice item:RssParser.parse(feed.url,response.url,response.text()).items)if(item.key.equals(cached.content.key)){notice=item;break;}}
                if(notice==null||!notice.key.equals(cached.content.key))throw new IOException();guard.check();store.upsert(Collections.singletonList(notice),System.currentTimeMillis());CachedNotice state=store.find(notice.key);CampusNotice body=notice;ui(token,()->{if(cancelled.getAsBoolean())return;if(!detailVisible||detailWeb!=requestedPage){if(!detailVisible)loadCache();return;}if(detailWeb!=null)detailWeb.destroy();showDetail(state,body);});
            }catch(Exception e){if(!cancelled.getAsBoolean())ui(token,()->Toast.makeText(this,"正文未能取回；请检查校园/门户登录，或打开原文。阅读状态未更改。",Toast.LENGTH_LONG).show());}
        });
    }
    private void setFlag(NoticeKey key,boolean read,boolean value,Runnable after) {
        final String p=profile;final int token=generation;
        worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){if(read)store.setRead(key,value);else store.setLater(key,value);ui(token,()->{if(after!=null)after.run();else if(!detailVisible)loadCache();});}catch(Exception e){ui(token,()->Toast.makeText(this,"阅读状态保存失败，请重试",Toast.LENGTH_LONG).show());}});
    }
    private void rowActions(CachedNotice cached) {
        new AlertDialog.Builder(this).setTitle(cached.content.title).setItems(new String[]{cached.read?"标为未读":"标为已读",cached.later?"取消稍后读":"稍后读","编辑标签"},(d,which)->{if(which==2)editTags(cached.content.key);else setFlag(cached.content.key,which==0,which==0?!cached.read:!cached.later,null);}).show();
    }
    private void openSettings(){startActivity(new Intent(this,ReaderSettingsActivity.class).putExtra("profile",profile));}
    private EditText input(LinearLayout page,String label,String value){page.addView(text(label,15,INK));EditText edit=new EditText(this);edit.setText(value);edit.setSingleLine(false);edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});page.addView(edit);return edit;}
    private void showFilterDialog(){final String p=profile;final int token=generation;worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){List<NoticeTag> tags=store.listTags();ui(token,()->{
        LinearLayout page=column();page.setPadding(dp(16),dp(4),dp(16),dp(4));ScrollView scroll=new ScrollView(this);scroll.addView(page);
        EditText search=input(page,"关键词搜索（标题及仍缓存的正文）",libraryFilter.search),include=input(page,"必须包含的词（逗号分隔，全部满足）",libraryFilter.include),exclude=input(page,"排除的词（逗号分隔，任一命中隐藏）",libraryFilter.exclude);
        CheckBox all=new CheckBox(this);all.setText("同时具有所有选中标签");all.setChecked(libraryFilter.allTags);page.addView(all);
        List<CheckBox> choices=new ArrayList<>();for(NoticeTag tag:tags){CheckBox c=new CheckBox(this);c.setText(tag.name);c.setChecked(libraryFilter.tags.contains(tag.id));page.addView(c);choices.add(c);}
        page.addView(text("筛选只隐藏列表项，不删除通知；清理过的正文只检索标题。",13,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("搜索与筛选").setView(scroll).setPositiveButton("应用",null).setNeutralButton("清除筛选",(d,w)->{libraryFilter=new ReaderFilterState();libraryFilter.save(preferences,p);generation++;showList();}).setNegativeButton("取消",null).create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{ReaderFilterState next=new ReaderFilterState();next.search=search.getText().toString().trim();next.include=include.getText().toString().trim();next.exclude=exclude.getText().toString().trim();next.allTags=all.isChecked();for(int i=0;i<tags.size();i++)if(choices.get(i).isChecked())next.tags.add(tags.get(i).id);try{next.query(source,filter,100);}catch(IllegalArgumentException e){search.setError("每个词不超过256字，包含/排除词各最多50个");return;}libraryFilter=next;libraryFilter.save(preferences,p);generation++;dialog.dismiss();showList();});
    });}catch(Exception e){ui(token,()->message("无法读取筛选条件"));}});}
    private void editTags(NoticeKey key){final String p=profile;final int token=generation;worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){List<NoticeTag> tags=store.listTags();List<NoticeTag> current=store.tags(key);Set<Long> selected=new LinkedHashSet<>();for(NoticeTag t:current)selected.add(t.id);String[] names=new String[tags.size()];boolean[] checked=new boolean[tags.size()];for(int i=0;i<tags.size();i++){names[i]=tags.get(i).name;checked[i]=selected.contains(tags.get(i).id);}ui(token,()->new AlertDialog.Builder(this).setTitle("文章标签").setMultiChoiceItems(names,checked,(d,index,value)->{if(value)selected.add(tags.get(index).id);else selected.remove(tags.get(index).id);}).setPositiveButton("保存",(d,w)->worker.execute(()->{try(CampusNoticeStore db=new CampusNoticeStore(this,p)){db.setTags(key,selected);ui(token,()->{Toast.makeText(this,"标签已保存",Toast.LENGTH_SHORT).show();if(!detailVisible)loadCache();});}catch(Exception e){ui(token,()->Toast.makeText(this,"标签保存失败",Toast.LENGTH_SHORT).show());}})).setNeutralButton("新建标签",(d,w)->newTag(key)).setNegativeButton("取消",null).show());}catch(Exception e){ui(token,()->message("无法读取标签"));}});}
    private void newTag(NoticeKey attach){EditText name=new EditText(this);name.setHint("标签名称");name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});final String p=profile;final int token=generation;
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("新建本地标签").setView(name).setPositiveButton("保存",null).setNegativeButton("取消",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("请输入名称");return;}worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){NoticeTag tag=store.createTag(value);if(attach!=null)store.addTag(attach,tag.id);ui(token,()->{dialog.dismiss();Toast.makeText(this,"标签已保存",Toast.LENGTH_SHORT).show();if(attach!=null)editTags(attach);else manageTags();});}catch(Exception e){ui(token,()->name.setError("名称不合法或保存失败"));}});});}
    private void manageTags(){final String p=profile;final int token=generation;worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){List<NoticeTag> tags=store.listTags();String[] names=new String[tags.size()];for(int i=0;i<names.length;i++)names[i]=tags.get(i).name;ui(token,()->new AlertDialog.Builder(this).setTitle("管理本地标签").setItems(names,(d,index)->tagActions(tags.get(index))).setPositiveButton("新建标签",(d,w)->newTag(null)).setNegativeButton("返回",null).show());}catch(Exception e){ui(token,()->message("标签读取失败"));}});}
    private void tagActions(NoticeTag tag){new AlertDialog.Builder(this).setTitle(tag.name).setItems(new String[]{"按此标签筛选","重命名","删除标签（保留文章）"},(d,index)->{
        if(index==0){libraryFilter.tags.clear();libraryFilter.tags.add(tag.id);libraryFilter.save(preferences,profile);generation++;showList();return;}
        final String p=profile;final int token=generation;
        if(index==2){worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){store.deleteTag(tag.id);ui(token,()->{libraryFilter.tags.remove(tag.id);libraryFilter.save(preferences,p);loadCache();manageTags();});}catch(Exception e){ui(token,()->message("标签删除失败"));}});return;}
        EditText name=new EditText(this);name.setText(tag.name);new AlertDialog.Builder(this).setTitle("重命名标签").setView(name).setPositiveButton("保存",(dialog,w)->{String value=name.getText().toString().trim();worker.execute(()->{try(CampusNoticeStore store=new CampusNoticeStore(this,p)){store.renameTag(tag.id,value);ui(token,()->{loadCache();manageTags();});}catch(Exception e){ui(token,()->Toast.makeText(this,"名称重复、不合法或保存失败",Toast.LENGTH_LONG).show());}});}).setNegativeButton("取消",null).show();
    }).show();}
    private void openExternal(String url) {
        try {java.net.URI uri=new java.net.URI(url);if(uri.getHost()==null||uri.getUserInfo()!=null||!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme())))throw new IOException();startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception e){Toast.makeText(this,"无法打开链接；校园原文及附件需要有效校园连接。",Toast.LENGTH_LONG).show();}
    }
    private void promptPortalLogin(){
        if(!portalPrompted&&!deferredPortalRefresh.hasRequest()&&resumed&&!detailVisible
                &&CampusApplication.auth().isAccessReady()&&new ReaderPreferences(this).isPortalAutoRestoreEnabled()){
            portalPrompted=true;portalLogin(true);
        }
    }
    private void portalLogin(){portalLogin(false);}
    private void portalLogin(boolean automatic){
        if(portalLoginInFlight)return;
        if(automatic&&!PortalSessionCoordinator.shouldAttempt(this,profile))return;
        if(!CampusApplication.auth().isAccessReady()){if(!automatic)connect();return;}
        if(clearingSession){message("正在切换校园账号，请稍后重新登录门户。");return;}
        portalLoginInFlight=true;
        startActivityForResult(new Intent(this,PortalLoginActivity.class)
                .putExtra(PortalLoginActivity.EXTRA_PROFILE,profile)
                .putExtra(PortalLoginActivity.EXTRA_MANAGE,!automatic)
                .putExtra(PortalLoginActivity.EXTRA_AUTOMATIC,automatic),PORTAL);
    }
    private byte[] imageData(String p,String url) throws IOException {
        byte[] cached=ImageCacheMaintenance.read(this,p,url);if(cached!=null)return cached;
        final boolean campus=ReaderHttp.isCampus(url);final ReaderPreferences requestPrefs=new ReaderPreferences(this);final long portalGeneration=requestPrefs.getPortalGeneration(),foregroundGeneration=foregroundRequestGeneration;
        final java.util.function.BooleanSupplier cancelled=()->foregroundGeneration!=foregroundRequestGeneration||(campus&&portalGeneration!=requestPrefs.getPortalGeneration());
        ReaderHttp.RequestGuard guard=new ReaderHttp.RequestGuard(p,campus,Thread.currentThread(),cancelled);guard.check();
        ReaderHttp.Response response=ReaderHttp.get(url,5*1024*1024,campus,cancelled);
        if(response.status!=200||!response.mime.toLowerCase(Locale.ROOT).startsWith("image/"))throw new IOException("Not an image");
        guard.check();
        ImageCacheMaintenance.put(this,p,url,response.bytes);return response.bytes;
    }

    private final android.util.LruCache<String,Bitmap> thumbnails=new android.util.LruCache<String,Bitmap>(4*1024*1024){@Override protected int sizeOf(String key,Bitmap value){return value.getByteCount();}};
    private final class NoticeAdapter extends BaseAdapter {
        @Override public int getCount(){return rows.size();}
        @Override public Object getItem(int position){return rows.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View reused,android.view.ViewGroup parent) {
            final CachedNotice cached=rows.get(position);final String mode=viewMode;final String p=profile;final int token=generation;
            FrameLayout holder=new FrameLayout(ReaderActivity.this);holder.setBackgroundColor(ReaderUi.SURFACE);
            // A text row is always usable while its optional thumbnail is unavailable.
            holder.addView(noticeRow(cached,"list",null),new FrameLayout.LayoutParams(-1,-2));
            if(!"list".equals(mode)&&cached.bodyCached&&cached.content.hasImage()) {
                final String url=cached.content.imageUrls.get(0),imageKey=p+"|"+mode+"|"+url;Bitmap known=thumbnails.get(imageKey);
                if(known!=null){holder.removeAllViews();holder.addView(noticeRow(cached,mode,known));}
                else images.execute(()->{try{if(token!=generation||isDestroyed())return;byte[] bytes=imageData(p,url);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);BitmapFactory.Options opts=new BitmapFactory.Options();int max="card".equals(mode)?1000:300;opts.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/opts.inSampleSize>max*2)opts.inSampleSize*=2;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);if(bitmap!=null){thumbnails.put(imageKey,bitmap);runOnUiThread(()->{if(!isDestroyed()&&token==generation&&!detailVisible){holder.removeAllViews();holder.addView(noticeRow(cached,mode,bitmap));}});}}catch(Exception ignored){}});
            }
            return holder;
        }
    }
    private View noticeRow(CachedNotice cached,String mode,Bitmap bitmap){
        boolean card="card".equals(mode);int layout=card?R.layout.reader_item_article_card:"magazine".equals(mode)?R.layout.reader_item_article_magazine:R.layout.reader_item_article_list;
        View row=getLayoutInflater().inflate(layout,list,false);TextView title=row.findViewById(R.id.reader_content_txt);title.setText(cached.content.title);title.setTextColor(cached.read?MUTED:INK);title.setTypeface(Typeface.create("sans-serif",cached.read?Typeface.NORMAL:Typeface.BOLD));
        TextView origin=row.findViewById(R.id.reader_title_txt),published=row.findViewById(R.id.reader_date);if(origin!=null){origin.setText(CampusPortalParser.SOURCE.equals(cached.content.key.source)?"校内通知":"订阅");origin.setTextColor(MUTED);}if(published!=null){published.setText(date(cached.content.publishedAtMillis));published.setTextColor(MUTED);}
        ImageView star=row.findViewById(R.id.reader_image_star_lg);if(star!=null){star.setVisibility(cached.later?View.VISIBLE:View.GONE);star.setColorFilter(BLUE);star.setContentDescription("稍后读");}
        View offline=row.findViewById(R.id.reader_image_offline);if(offline!=null)offline.setVisibility(View.GONE);
        ImageView readIcon=row.findViewById(R.id.reader_image_read_lg);if(readIcon!=null){readIcon.setVisibility(cached.read?View.GONE:View.VISIBLE);readIcon.setColorFilter(ReaderUi.SURFACE);}
        ImageView image=row.findViewById(R.id.reader_photo_preview);if(image!=null&&bitmap!=null)image.setImageBitmap(bitmap);
        if(card){View gradient=row.findViewById(R.id.reader_card_gradient);if(gradient!=null)gradient.setVisibility(View.GONE);}
        row.setContentDescription(cached.content.title+"，"+date(cached.content.publishedAtMillis)+(cached.read?"，已读":"，未读")+(cached.later?"，稍后读":"")+tagText(cached.tags));
        // Row actions remain available without a gesture through the native long-press menu.
        return row;
    }
    private static String tagText(List<NoticeTag> tags){StringBuilder b=new StringBuilder();for(NoticeTag tag:tags)b.append("   #").append(tag.name);return b.toString();}
    private void addSubscription() {
        EditText input=new EditText(this);input.setHint("https://example.org/feed.xml");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("添加本地 RSS / Atom 订阅").setView(input).setPositiveButton("验证并添加",null).setNegativeButton("取消",null).create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String url=input.getText().toString().trim();try{if(!url.startsWith("https://"))throw new IOException();ReaderHttp.checked(url,false);}catch(Exception e){input.setError("请输入完整 HTTPS 订阅地址");return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);final String p=profile;final int token=generation;
            network.execute(()->{try{ReaderHttp.Response response=ReaderHttp.get(url,5*1024*1024,false);if(response.status!=200)throw new IOException();RssParser.Feed feed=RssParser.parse(url,response.url,response.text());try(CampusNoticeStore store=new CampusNoticeStore(this,p)){store.upsert(feed.items,System.currentTimeMillis());}
                ui(token,()->{Subscription found=subscription(RssParser.source(url));if(found==null)subscriptions.add(new Subscription(url,feed.title));saveSubscriptions();source=RssParser.source(url);sourceTitle=feed.title;priorityOnly=false;filter=0;generation++;dialog.dismiss();showList();message("本地订阅已添加；阅读状态保存在本机。");});
            }catch(Exception e){ui(token,()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);input.setError("无法读取有效 RSS / Atom，请检查地址与网络");});}});
        });
    }
    private void manageSubscriptions(){if(subscriptions.isEmpty()){Toast.makeText(this,"尚无本地 RSS / Atom 订阅",Toast.LENGTH_SHORT).show();return;}String[] names=new String[subscriptions.size()];for(int i=0;i<names.length;i++)names[i]=subscriptions.get(i).title;new AlertDialog.Builder(this).setTitle("移除本地订阅（保留缓存）").setItems(names,(d,index)->{Subscription removed=subscriptions.remove(index);saveSubscriptions();if(source.equals(RssParser.source(removed.url))){source=CampusPortalParser.SOURCE;sourceTitle="校内通知";generation++;showList();}}).show();}
    private Subscription subscription(String key){for(Subscription s:subscriptions)if(RssParser.source(s.url).equals(key))return s;return null;}
    private static final class Subscription {final String url,title;Subscription(String url,String title){this.url=url;this.title=title;}}
    private void loadSubscriptions(){try{JSONArray a=new JSONArray(preferences.getString("subscriptions","[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);subscriptions.add(new Subscription(o.getString("url"),o.getString("title")));}}catch(JSONException ignored){}}
    private void saveSubscriptions(){JSONArray a=new JSONArray();try{for(Subscription s:subscriptions){JSONObject o=new JSONObject();o.put("url",s.url);o.put("title",s.title);a.put(o);}preferences.edit().putString("subscriptions",a.toString()).apply();}catch(JSONException e){Toast.makeText(this,"订阅保存失败",Toast.LENGTH_LONG).show();}}
    private void ui(int token,Runnable action){runOnUiThread(()->{if(!isDestroyed()&&token==generation)action.run();});}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button button(String value,View.OnClickListener click){Button b=new Button(this);b.setText(value);b.setTextSize(14);b.setTextColor(BLUE);b.setAllCaps(false);b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(8),0,dp(8),0);ReaderUi.selectable(b);if(click!=null)b.setOnClickListener(click);return b;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static String date(long time){if(time==0)return "未提供日期";SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date(time));}
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    @Override public void onBackPressed(){if(nativeDrawer!=null&&nativeDrawer.isDrawerOpen(androidx.core.view.GravityCompat.START))closeDrawer();else if(detailVisible)showList();else if(searchInput!=null&&((View)searchInput.getParent()).getVisibility()==View.VISIBLE){((View)searchInput.getParent()).setVisibility(View.GONE);hideKeyboard();}else super.onBackPressed();}
    @Override protected void onDestroy(){foregroundRequestGeneration++;if(configurationLease!=null){configurationLease.close();configurationLease=null;}mainHandler.removeCallbacks(readiness);if(pendingSearch!=null)mainHandler.removeCallbacks(pendingSearch);if(detailWeb!=null)detailWeb.destroy();worker.shutdown();network.shutdownNow();images.shutdownNow();thumbnails.evictAll();super.onDestroy();}
}
