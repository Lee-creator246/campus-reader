package org.campusreader.reader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;

/** User-selected SAF destination, including Android 23-28 without broad storage permission. */
public final class MediaExportActivity extends Activity {
    private MediaTransfer.Entry entry;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        entry=MediaTransfer.read(this,getIntent().getStringExtra("profileHash"),getIntent().getStringExtra("id"));
        if(entry==null||!MediaActions.currentEntry(entry)){MediaActions.toast(this,"账号已改变或缓存已被清理，请重新打开");finish();return;}
        if(state==null){
            Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(entry.mime).putExtra(Intent.EXTRA_TITLE,entry.name);
            try{startActivityForResult(save,30);}catch(Exception error){MediaActions.toast(this,"无法打开文件选择器");finish();}
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=30||result!=RESULT_OK||data==null||data.getData()==null){finish();return;}
        Uri destination=data.getData();
        MediaActions.WORKER.execute(()->{
            boolean okay=false;
            if(!MediaActions.currentEntry(entry)){runOnUiThread(()->{MediaActions.toast(this,"账号已改变，已取消导出");finish();});return;}
            try(InputStream input=new FileInputStream(entry.file);OutputStream output=getContentResolver().openOutputStream(destination,"wt")){
                if(output==null)throw new IOException("No output");
                MediaSafety.copyBounded(input,output,MediaTransfer.MAX_ATTACHMENT,entry.file.length());okay=true;
            }catch(Exception failure){try{getContentResolver().delete(destination,null,null);}catch(Exception ignored){}}
            final boolean saved=okay;runOnUiThread(()->{MediaActions.toast(this,saved?"文件已保存到所选位置":"文件保存失败");finish();});
        });
    }
}
