package org.campusreader.reader;
import org.campusreader.auth.ScreenPrivacy;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** User-selected policy documents stay on device until a separate summary send confirmation. */
public final class PolicyLibraryActivity extends Activity {
    public static final String EXTRA_PROFILE="profile";
    private static final int PICK_DOCUMENT=421;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private String profile;
    private PolicyLibrary library;
    private LinearLayout list;
    private TextView status;
    private Future<?> task;
    private boolean importing;
    private volatile long sequence;
    private long displayedRevision=-1;
    private AlertDialog openDialog;
    private final Runnable monitor=new Runnable(){@Override public void run(){
        if(isDestroyed()||isFinishing())return;
        if(!AiProfile.owns(PolicyLibraryActivity.this,profile)){
            cancelWork();dismissDialog();list.removeAllViews();status.setText("");finish();return;
        }
        try{
            if(displayedRevision>=0&&!library.isCurrent(displayedRevision)){
                cancelWork();dismissDialog();list.removeAllViews();displayedRevision=-1;reload("政策资料已变化，已重新读取本机版本。");
            }
        }catch(RuntimeException unavailable){cancelWork();dismissDialog();list.removeAllViews();displayedRevision=-1;status.setText("政策资料暂时无法核对，已隐藏显示内容，请返回后重试。");}
        main.postDelayed(this,500);
    }};
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ScreenPrivacy.protect(getWindow());
        profile=getIntent().getStringExtra(EXTRA_PROFILE);if(!AiProfile.owns(this,profile)){finish();return;}
        library=new PolicyLibrary(this,profile);
        LinearLayout page=column();page.setPadding(dp(20),dp(16),dp(20),dp(24));page.setBackgroundColor(Color.WHITE);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        button(page,"‹ 返回阅读",this::finish);note(page,"政策资料库",24,ReaderUi.INK);
        note(page,"添加与你相关的政策，摘要时可检索相关条款并查看资料依据。资料由你提供，应用不验证其真实性、效力或适用范围。",14,ReaderUi.MUTED);
        button(page,"导入政策文件（TXT / PDF）",()->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","application/pdf"});
            try{startActivityForResult(intent,PICK_DOCUMENT);}catch(ActivityNotFoundException unavailable){status.setText("本机没有可用文件选择器。");}
        });
        note(page,"仅保存在当前账号本机，不会自动上传。最多 20 份、每份 8 MiB / 40 页 / 100000 字符，文字总量 1000000 字符。超限文件不截取导入。",12,ReaderUi.MUTED);
        note(page,"本版支持 UTF-8 TXT、文字版 PDF。只保存提取的文字与文件名，不保留原文件；图片、扫描件、嵌入附件不解析，表格阅读顺序可能改变。请保留原文件用于核对。",12,ReaderUi.MUTED);
        status=note(page,"正在读取本机资料…",14,ReaderUi.BLUE);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        list=column();page.addView(list);
        button(page,"清空当前账号的政策资料",()->show(new AlertDialog.Builder(this).setTitle("清空政策资料？").setMessage("删除本机提取文字和当前账号的全部 AI 摘要缓存，停止尚未完成的摘要；通知、阅读状态和标签保留。已经发送给服务方的内容不会撤回。")
            .setPositiveButton("清空",(dialog,which)->mutate(()->library.clear(),"资料已清空；相关摘要已失效。" )).setNegativeButton("取消",null).create()));
    }
    @Override protected void onResume(){super.onResume();if(library==null)return;if(!AiProfile.owns(this,profile)){finish();return;}main.removeCallbacks(monitor);main.post(monitor);if(!importing)reload("");}
    @Override protected void onStop(){main.removeCallbacks(monitor);cancelWork();dismissDialog();super.onStop();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);cancelWork();dismissDialog();worker.shutdownNow();super.onDestroy();}
    private void cancelWork(){sequence++;importing=false;if(task!=null)task.cancel(true);task=null;}
    private boolean active(long token){return token==sequence&&!isFinishing()&&!isDestroyed()&&AiProfile.owns(this,profile);}
    private void reload(String message){
        final long token=++sequence;task=worker.submit(()->{
            try{PolicyLibrary.Snapshot snapshot=library.snapshot();runOnUiThread(()->{if(!active(token))return;render(snapshot);status.setText(message.isEmpty()?"本机资料 "+snapshot.documents.size()+" / 20 份。添加、编辑或删除会使旧摘要失效。":message);});}
            catch(RuntimeException failure){runOnUiThread(()->{if(active(token))status.setText("无法读取政策资料，未使用资料生成摘要。请检查存储空间后重试。");});}
        });
    }
    private void render(PolicyLibrary.Snapshot snapshot){
        displayedRevision=snapshot.revision;
        list.removeAllViews();if(snapshot.documents.isEmpty()){note(list,"尚未添加资料。可先导入与你关注的通知相关的政策文件。",14,ReaderUi.MUTED);return;}
        for(PolicyLibrary.Document document:snapshot.documents){
            note(list,document.title,18,ReaderUi.INK);note(list,description(document),13,ReaderUi.MUTED);
            button(list,"查看文字与页码",()->showText(document));button(list,"编辑资料信息",()->edit(document,null,document.sourceLabel));
            button(list,"删除这份资料",()->show(new AlertDialog.Builder(this).setTitle("删除资料？").setMessage("删除“"+document.title+"”的本机文字，并清理当前账号的 AI 摘要缓存。")
                .setPositiveButton("删除",(d,w)->mutate(()->library.delete(document.id),"资料已删除；相关摘要已失效。")).setNegativeButton("取消",null).create()));
        }
    }
    private static String description(PolicyLibrary.Document d){return "状态："+d.status+"（由你填写）\n版本："+known(d.version)+"  ·  发布单位："+known(d.issuer)+"\n生效："+known(d.validFrom)+"  至  "+known(d.validUntil)+"\n适用范围："+known(d.scope)+"\n资料来源："+known(d.sourceLabel)+"\n"+d.pages.size()+" 页文字";}
    private static String known(String value){return value.isEmpty()?"未填写":value;}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=PICK_DOCUMENT||result!=RESULT_OK||data==null||data.getData()==null||!AiProfile.owns(this,profile))return;
        final Uri uri=data.getData();if(!"content".equals(uri.getScheme())){status.setText("请选择系统文件选择器中的文件。");return;}
        importing=true;final long token=++sequence;status.setText("正在本机提取文字；不会上传资料…");
        task=worker.submit(()->{
            try{
                String name="政策资料";try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                    if(cursor!=null&&cursor.moveToFirst()){int size=cursor.getColumnIndex(OpenableColumns.SIZE);if(size>=0&&!cursor.isNull(size)&&cursor.getLong(size)>PolicyImport.MAX_FILE_BYTES)throw new PolicyImport.ImportException("文件超过 8 MiB，请选择较小文件");int label=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(label>=0&&!cursor.isNull(label))name=cursor.getString(label);}
                }
                if(name.length()>500)throw new PolicyImport.ImportException("文件名过长，请重命名后导入");
                PolicyImport.Result parsed;try(InputStream input=getContentResolver().openInputStream(uri)){parsed=PolicyImport.read(this,input,name,getContentResolver().getType(uri));}
                final String filename=name;runOnUiThread(()->{if(!active(token))return;importing=false;status.setText("已提取文字，请核对资料信息后保存。还没有发送给 AI 服务。");edit(null,parsed,filename);});
            }catch(Exception failure){String message=failure instanceof PolicyImport.ImportException?failure.getMessage():"文件无法读取，请选择可用的文字版 PDF 或 UTF-8 TXT";runOnUiThread(()->{if(active(token)){importing=false;status.setText(message==null?"文件解析失败，未导入资料。":message);}});}
        });
    }
    private void edit(PolicyLibrary.Document existing,PolicyImport.Result imported,String filename){
        if(!AiProfile.owns(this,profile))return;
        LinearLayout form=column();form.setPadding(dp(18),dp(8),dp(18),dp(8));
        note(form,"字段由你填写，仅作检索和核对参考；留空代表未知。资料有新版本时请删除旧版后重新导入，或分别标明有效状态。",13,ReaderUi.MUTED);
        EditText title=field(form,"资料标题",existing==null?filename.replaceFirst("(?i)\\.(pdf|txt)$",""):existing.title,200);
        EditText issuer=field(form,"发布单位",existing==null?"":existing.issuer,200),version=field(form,"版本 / 文号",existing==null?"":existing.version,100);
        EditText from=field(form,"生效日期（YYYY-MM-DD，未知留空）",existing==null?"":existing.validFrom,10),until=field(form,"失效日期（YYYY-MM-DD，未知留空）",existing==null?"":existing.validUntil,10);
        EditText scope=field(form,"适用对象 / 校区 / 范围",existing==null?"":existing.scope,500),source=field(form,"来源说明 / 原文件名",existing==null?filename:existing.sourceLabel,500);
        note(form,"效力状态（需自行核对）",14,ReaderUi.INK);Spinner state=new Spinner(this);state.setMinimumHeight(dp(48));ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,PolicyLibrary.STATUSES);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);state.setAdapter(adapter);if(existing!=null)state.setSelection(Arrays.asList(PolicyLibrary.STATUSES).indexOf(existing.status));form.addView(state);
        TextView error=note(form,"",13,0xffb3261e);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"核对并添加政策":"编辑政策信息").setView(scroll).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(v->{secure(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                PolicyLibrary.Metadata metadata=new PolicyLibrary.Metadata(title.getText().toString(),issuer.getText().toString(),version.getText().toString(),from.getText().toString(),until.getText().toString(),scope.getText().toString(),(String)state.getSelectedItem(),source.getText().toString());
                dialog.dismiss();mutate(()->{if(existing==null)library.add(metadata,imported.pages);else library.update(existing.id,metadata);},"资料已保存；旧摘要已失效。未向 AI 服务发送内容。");
            }catch(IllegalArgumentException invalid){error.setText(invalid.getMessage());}
        });});trackDialog(dialog);dialog.show();
    }
    private void mutate(Runnable change,String message){
        if(!AiProfile.owns(this,profile))return;final long token=++sequence;status.setText("正在保存本机资料…");
        task=worker.submit(()->{
            try{synchronized(AiSettings.LOCK){if(!active(token)||Thread.currentThread().isInterrupted())return;change.run();}runOnUiThread(()->{if(active(token))reload(message);});}
            catch(RuntimeException failure){String text=failure instanceof IllegalArgumentException?failure.getMessage():"资料未能保存，请检查本机存储空间后重试。";runOnUiThread(()->{if(active(token))status.setText(text);});}
        });
    }
    private void showText(PolicyLibrary.Document document){
        LinearLayout page=column();page.setPadding(dp(18),dp(8),dp(18),dp(8));note(page,description(document),13,ReaderUi.MUTED);
        note(page,"这是提取文字，非原文件排版。图片及表格可能未完整解析；请同时核对自己保存的原文件。",13,0xffb3261e);
        for(int i=0;i<document.pages.size();i++){note(page,"第 "+(i+1)+" 页",16,ReaderUi.BLUE);TextView text=note(page,document.pages.get(i).trim().isEmpty()?"本页未提取到文字，不代表本页没有内容。":document.pages.get(i),15,ReaderUi.INK);text.setTextIsSelectable(true);}
        ScrollView scroll=new ScrollView(this);scroll.addView(page);show(new AlertDialog.Builder(this).setTitle(document.title).setView(scroll).setPositiveButton("关闭",null).create());
    }
    private void show(AlertDialog dialog){dialog.setOnShowListener(v->secure(dialog));trackDialog(dialog);dialog.show();}
    private void trackDialog(AlertDialog dialog){dismissDialog();openDialog=dialog;dialog.setOnDismissListener(v->{if(openDialog==dialog)openDialog=null;});}
    private void dismissDialog(){AlertDialog previous=openDialog;openDialog=null;if(previous!=null)previous.dismiss();}
    private void secure(AlertDialog dialog){if(dialog.getWindow()!=null){ScreenPrivacy.protect(dialog.getWindow());dialog.getWindow().setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.85));}}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private EditText field(LinearLayout page,String label,String value,int max){note(page,label,13,ReaderUi.INK);EditText text=new EditText(this);text.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);text.setTextSize(14);text.setMinHeight(dp(48));text.setSingleLine(true);text.setSaveEnabled(false);if(android.os.Build.VERSION.SDK_INT>=26)text.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);text.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});text.setText(value);page.addView(text);return text;}
    private TextView note(LinearLayout page,String value,int size,int color){TextView view=ReaderUi.text(this,value,size,color);view.setPadding(0,dp(8),0,dp(8));view.setLineSpacing(dp(3),1);page.addView(view);return view;}
    private void button(LinearLayout page,String label,Runnable action){Button view=new Button(this);view.setText(label);view.setAllCaps(false);view.setMinHeight(dp(48));view.setOnClickListener(v->action.run());page.addView(view,new LinearLayout.LayoutParams(-1,-2));}
    private int dp(int value){return ReaderUi.dp(this,value);}
}
