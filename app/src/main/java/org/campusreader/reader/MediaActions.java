package org.campusreader.reader;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** User-invoked media actions. Shares only through the Android chooser, never sends a message. */
public final class MediaActions {
    static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final ExecutorService ATTACHMENTS=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private MediaActions(){}
    public static void showImage(Activity activity,String profile,String url){showImages(activity,profile,Collections.singletonList(url),0);}
    public static void showImages(Activity activity,String profile,List<String> urls,int index) {
        if(urls==null||urls.isEmpty())return;
        if(urls.size()>200){toast(activity,"图片过多，请选择单张预览");return;}
        activity.startActivity(new Intent(activity,MediaViewerActivity.class).putExtra("profile",profile)
                .putStringArrayListExtra("images",new ArrayList<>(urls)).putExtra("index",Math.max(0,Math.min(index,urls.size()-1))));
    }
    public static void downloadAttachment(Activity activity,String profile,String url,String name) {
        downloadAttachment(activity,profile,url,name,null);
    }
    /** Pass the notice's canonical originalUrl as same-origin Referer for portal download JSPs. */
    public static void downloadAttachment(Activity activity,String profile,String url,String name,String originalNoticeUrl) {
        toast(activity,"正在下载附件（最多20MiB）");
        ATTACHMENTS.execute(()->{
            android.util.Log.i("CampusMedia","event=attachment_started");
            try {
                // Attachments must remain within the authenticated school portal. Public RSS links use the browser.
                if(!ReaderHttp.isCampus(url))throw new MediaSafety.Rejected("ORIGIN_CHANGED");
                MediaTransfer.Entry entry=MediaTransfer.fetch(activity.getApplicationContext(),profile,url,name,false,originalNoticeUrl);
                android.util.Log.i("CampusMedia","event=attachment_saved bytes="+entry.file.length());
                MAIN.post(()->{if(alive(activity)&&currentProfile(profile))showEntry(activity,entry);});
            }catch(Exception failure){
                String code=MediaSafety.failureCode(failure);
                android.util.Log.w("CampusMedia","event=attachment_failed code="+code);
                MAIN.post(()->{if(alive(activity))new AlertDialog.Builder(activity).setTitle("附件未下载")
                        .setMessage(failureMessage(code)+"\n诊断代码："+code).setPositiveButton("重试",(dialog,index)->downloadAttachment(activity,profile,url,name,originalNoticeUrl)).setNegativeButton("返回",null).show();});
            }
        });
    }
    private static String failureMessage(String code){
        if("TIMEOUT".equals(code))return "下载响应超时，请检查校园通道并重试。";
        if("DNS".equals(code)||"CONNECT".equals(code)||"CAMPUS_OFFLINE".equals(code))return "校园下载通道不可用，请重新检查连接。";
        if("LOGIN_OR_HTML".equals(code)||"HTTP_401".equals(code)||"HTTP_403".equals(code))return "下载入口返回认证或网页内容，未保存为附件。请重新登录信息门户后重试。";
        if("SIZE_LIMIT".equals(code))return "附件超过20MiB限制，请使用原文入口。";
        if("ORIGIN_CHANGED".equals(code)||"HTTPS_DOWNGRADE".equals(code))return "下载跳转离开允许的门户来源，已停止，未向外域发送校园Cookie。";
        if("TRUNCATED".equals(code)||"EMPTY_FILE".equals(code))return "服务器未返回完整附件，请重试。";
        if("ACCOUNT_CHANGED".equals(code))return "账号已改变，请从当前账号的通知重新打开附件。";
        return "附件未能完成下载，请保留诊断代码后重试。";
    }
    public static void viewDownloads(Activity activity){activity.startActivity(new Intent(activity,MediaDownloadsActivity.class));}
    static boolean alive(Activity activity){return !activity.isFinishing()&&!activity.isDestroyed();}
    public static String activeProfile(){return org.campusreader.auth.CampusConfiguration.isConfigured()?PortalAccess.currentProfile():"local";}
    static boolean currentProfile(String profile){return profile!=null&&profile.equals(activeProfile());}
    static boolean currentEntry(MediaTransfer.Entry entry){return entry!=null&&entry.profileHash.equals(MediaTransfer.hash(activeProfile()));}
    static void toast(Context context,String value){Toast.makeText(context.getApplicationContext(),value,Toast.LENGTH_LONG).show();}
    static void showEntry(Activity activity,MediaTransfer.Entry entry) {
        if(!currentEntry(entry)){toast(activity,"账号已改变，请重新打开下载列表");return;}
        new AlertDialog.Builder(activity).setTitle(entry.name+"\n"+formatSize(entry.file.length()))
                .setItems(new String[]{"用其他应用打开","选择应用分享","另存到文件"},(dialog,index)->{
                    if(index==2)export(activity,entry);else share(activity,entry,index==0);
                }).setNegativeButton("关闭",null).show();
    }
    static void share(Activity activity,MediaTransfer.Entry entry,boolean view) {
        if(!currentEntry(entry)){toast(activity,"账号已改变，请重新打开资源");return;}
        Uri uri=MediaShareProvider.uri(activity,entry);
        Intent action=new Intent(view?Intent.ACTION_VIEW:Intent.ACTION_SEND);
        if(view)action.setDataAndType(uri,entry.mime);else{action.setType(entry.mime);action.putExtra(Intent.EXTRA_STREAM,uri);}
        action.setClipData(ClipData.newRawUri("资源",uri));action.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{activity.startActivity(Intent.createChooser(action,view?"选择打开方式":"选择分享应用"));}
        catch(ActivityNotFoundException failure){toast(activity,"没有可处理此文件的应用，可先另存到文件");}
    }
    static void export(Activity activity,MediaTransfer.Entry entry) {
        if(!currentEntry(entry)){toast(activity,"账号已改变，请重新打开资源");return;}
        activity.startActivity(new Intent(activity,MediaExportActivity.class).putExtra("profileHash",entry.profileHash).putExtra("id",entry.id));
    }
    static void saveImage(Activity activity,MediaTransfer.Entry entry) {
        if(Build.VERSION.SDK_INT<29){export(activity,entry);return;}
        WORKER.execute(()->{
            Uri uri=null;
            try {
                if(!currentEntry(entry))throw new IOException("Profile changed");
                ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,entry.name);
                values.put(MediaStore.Images.Media.MIME_TYPE,entry.mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/北邮人信息");
                values.put(MediaStore.Images.Media.IS_PENDING,1);
                ContentResolver resolver=activity.getContentResolver();
                uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                if(uri==null)throw new IOException("Unable to create media entry");
                try(InputStream input=new FileInputStream(entry.file);OutputStream output=resolver.openOutputStream(uri)){
                    if(output==null)throw new IOException("No output stream");MediaSafety.copyBounded(input,output,MediaTransfer.MAX_IMAGE,entry.file.length());
                }
                values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);resolver.update(uri,values,null,null);
                MAIN.post(()->toast(activity,"已保存到相册“北邮人信息”，可在企业微信从相册选择识别二维码"));
            }catch(Exception error){if(uri!=null)activity.getContentResolver().delete(uri,null,null);MAIN.post(()->toast(activity,"保存相册失败，可选择另存到文件"));}
        });
    }
    static String formatSize(long bytes){
        if(bytes<1024)return bytes+" B";
        if(bytes<1024L*1024)return String.format(Locale.ROOT,"%.1f KiB",bytes/1024d);
        return String.format(Locale.ROOT,"%.1f MiB",bytes/(1024d*1024d));
    }
}
