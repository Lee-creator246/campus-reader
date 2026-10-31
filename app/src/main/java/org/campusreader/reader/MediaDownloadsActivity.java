package org.campusreader.reader;

import android.app.*;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Lists and clears the current account's own bounded media cache. Never deletes notice reading state. */
public final class MediaDownloadsActivity extends Activity {
    private String profile;
    private LinearLayout content;
    private int revision;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);profile=MediaActions.activeProfile();
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if(Build.VERSION.SDK_INT>=26){getWindow().setNavigationBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}
    }
    @Override protected void onResume(){super.onResume();render();}
    private void render(){
        final int token=++revision;
        if(!MediaActions.currentProfile(profile)){finish();return;}
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(Color.WHITE);
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setBackgroundColor(Color.WHITE);
        toolbar.addView(ReaderUi.iconButton(this,"back","返回阅读",v->finish()),new LinearLayout.LayoutParams(dp(50),dp(56)));
        TextView title=ReaderUi.text(this,"下载管理",18,ReaderUi.INK);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setGravity(Gravity.CENTER);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));toolbar.addView(new View(this),new LinearLayout.LayoutParams(dp(50),dp(56)));
        page.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(56)));
        View line=new View(this);line.setBackgroundColor(ReaderUi.LINE);page.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,dp(12),0,dp(20));scroll.addView(content);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);
        TextView loading=ReaderUi.text(this,"正在读取下载缓存…",14,ReaderUi.MUTED);loading.setPadding(dp(16),dp(20),dp(16),dp(20));content.addView(loading);
        MediaActions.WORKER.execute(()->{
            List<MediaTransfer.Entry> entries=MediaTransfer.list(this,profile);long total=0;for(MediaTransfer.Entry entry:entries)total+=entry.file.length();final long size=total;
            runOnUiThread(()->{
                if(token!=revision||!MediaActions.alive(this)||!MediaActions.currentProfile(profile))return;
                content.removeAllViews();
                LinearLayout summary=new LinearLayout(this);summary.setGravity(Gravity.CENTER_VERTICAL);summary.setPadding(dp(16),0,dp(16),0);
                TextView info=ReaderUi.text(this,entries.size()+" 个文件 · "+MediaActions.formatSize(size),15,ReaderUi.INK);summary.addView(info,new LinearLayout.LayoutParams(0,-2,1));
                Button clear=new Button(this);clear.setText("清理缓存");clear.setTextSize(13);clear.setAllCaps(false);clear.setTextColor(ReaderUi.BLUE);clear.setBackgroundColor(Color.TRANSPARENT);clear.setEnabled(!entries.isEmpty());summary.addView(clear,new LinearLayout.LayoutParams(dp(100),dp(48)));content.addView(summary);
                TextView help=ReaderUi.text(this,"缓存最多 100 MiB，单个附件最多 20 MiB。点按文件打开操作，长按删除；清理保留通知和阅读状态。",12,ReaderUi.MUTED);help.setLineSpacing(dp(3),1);help.setPadding(dp(16),0,dp(16),dp(16));content.addView(help);
                clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清理下载缓存？").setMessage("已导出文件和相册图片不会被删除。")
                        .setPositiveButton("清理",(d,w)->MediaActions.WORKER.execute(()->{MediaTransfer.clear(this,profile);runOnUiThread(this::render);})).setNegativeButton("取消",null).show());
                for(MediaTransfer.Entry entry:entries){
                    LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(12),dp(16),dp(12));row.setBackgroundColor(Color.WHITE);row.setMinimumHeight(dp(70));row.setFocusable(true);android.util.TypedValue selectable=new android.util.TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackground,selectable,true);if(selectable.resourceId!=0)row.setBackgroundResource(selectable.resourceId);
                    ImageView file=new ImageView(this);file.setImageDrawable(ReaderUi.icon(this,"file",ReaderUi.BLUE));row.addView(file,new LinearLayout.LayoutParams(dp(24),dp(24)));
                    LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);words.setPadding(dp(16),0,dp(16),0);
                    TextView name=ReaderUi.text(this,entry.name,16,ReaderUi.INK);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);words.addView(name);
                    TextView details=ReaderUi.text(this,MediaActions.formatSize(entry.file.length()),12,ReaderUi.MUTED);details.setPadding(0,dp(6),0,0);words.addView(details);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
                    ImageView chevron=new ImageView(this);chevron.setImageDrawable(ReaderUi.icon(this,"back",ReaderUi.MUTED));chevron.setRotation(180);row.addView(chevron,new LinearLayout.LayoutParams(dp(20),dp(20)));
                    LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);content.addView(row,rowParams);View separator=new View(this);separator.setBackgroundColor(ReaderUi.LINE);content.addView(separator,new LinearLayout.LayoutParams(-1,dp(1)));
                    row.setOnClickListener(v->MediaActions.showEntry(this,entry));
                    row.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle(entry.name).setItems(new String[]{"删除此缓存"},(d,w)->MediaActions.WORKER.execute(()->{MediaTransfer.delete(entry);runOnUiThread(this::render);})).show();return true;});
                }
                if(entries.isEmpty()){
                    LinearLayout empty=new LinearLayout(this);empty.setOrientation(LinearLayout.VERTICAL);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(64),dp(24),dp(64));
                    ImageView icon=new ImageView(this);icon.setImageDrawable(ReaderUi.icon(this,"download",0xffaeb7c4));empty.addView(icon,new LinearLayout.LayoutParams(dp(52),dp(52)));
                    TextView emptyTitle=ReaderUi.text(this,"暂无下载文件",18,ReaderUi.INK);emptyTitle.setPadding(0,dp(20),0,dp(8));empty.addView(emptyTitle);
                    TextView hint=ReaderUi.text(this,"浏览图片或下载附件后，\n可在这里管理当前账号的缓存。",14,ReaderUi.MUTED);hint.setGravity(Gravity.CENTER);hint.setLineSpacing(dp(4),1);empty.addView(hint);content.addView(empty);
                }
            });
        });
    }
    private int dp(int value){return ReaderUi.dp(this,value);}
}
