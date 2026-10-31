package org.campusreader.reader;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Full-image viewer with bounded decoding, zoom and accessible image actions. */
public final class MediaViewerActivity extends Activity {
    private String profile;private ArrayList<String> images;private int index,revision;
    private TextView status;private ZoomImage view;private ProgressBar loading;private MediaTransfer.Entry current;private boolean hintShown,failed;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);profile=getIntent().getStringExtra("profile");images=getIntent().getStringArrayListExtra("images");
        if(images==null||images.isEmpty()||images.size()>200||!MediaActions.currentProfile(profile)){finish();return;}
        index=state==null?getIntent().getIntExtra("index",0):state.getInt("index",0);index=Math.max(0,Math.min(index,images.size()-1));
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);getWindow().getDecorView().setSystemUiVisibility(0);
        // Public-project layout: a full-frame image with a centered loading indicator.
        setContentView(R.layout.reader_activity_viewimage);
        ViewGroup frame=findViewById(R.id.reader_previewFrame);frame.setBackgroundColor(Color.BLACK);
        ImageView original=findViewById(R.id.reader_photoPreview);ViewGroup.LayoutParams originalParams=original.getLayoutParams();int position=frame.indexOfChild(original);frame.removeView(original);
        view=new ZoomImage();view.setId(R.id.reader_photoPreview);view.setElevation(dp(3));frame.addView(view,position,originalParams);
        view.setContentDescription("图片。点按打开保存、分享和切换图片菜单；双指缩放，双击还原。");view.setFocusable(true);view.setOnClickListener(v->showImageActions());view.setOnLongClickListener(v->{showImageActions();return true;});
        loading=findViewById(R.id.reader_load_progress);loading.setElevation(dp(4));
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        status=ReaderUi.text(this,"",14,Color.WHITE);status.setGravity(Gravity.CENTER);status.setPadding(dp(24),dp(24),dp(24),dp(24));status.setElevation(dp(5));
        RelativeLayout.LayoutParams messageLayout=new RelativeLayout.LayoutParams(-1,-2);messageLayout.addRule(RelativeLayout.CENTER_IN_PARENT);frame.addView(status,messageLayout);status.setOnClickListener(v->load());
        load();
    }
    private void showImageActions(){
        java.util.List<String> labels=new java.util.ArrayList<>();java.util.List<Runnable> actions=new java.util.ArrayList<>();
        if(current!=null){labels.add("保存到相册");actions.add(()->MediaActions.saveImage(this,current));labels.add("分享");actions.add(()->MediaActions.share(this,current,false));}
        else if(failed){labels.add("重新加载");actions.add(this::load);}
        if(index>0){labels.add("上一张");actions.add(()->{index--;load();});}
        if(index<images.size()-1){labels.add("下一张");actions.add(()->{index++;load();});}
        labels.add("返回正文");actions.add(this::finish);
        new android.app.AlertDialog.Builder(this).setTitle("图片 "+(index+1)+" / "+images.size()).setItems(labels.toArray(new String[0]),(dialog,item)->actions.get(item).run()).setNegativeButton("取消",null).show();
    }
    @Override protected void onResume(){super.onResume();if(!MediaActions.currentProfile(profile))finish();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("index",index);super.onSaveInstanceState(state);}
    private void load(){
        final int token=++revision;current=null;failed=false;status.setVisibility(View.GONE);loading.setVisibility(View.VISIBLE);view.setImageDrawable(null);view.setContentDescription("图片 "+(index+1)+" / "+images.size()+"。点按打开操作菜单；双指缩放，双击还原。");
        String address=images.get(index);
        MediaActions.WORKER.execute(()->{
            try{
                MediaTransfer.Entry entry=MediaTransfer.fetch(this,profile,address,"校园图片",true);
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(entry.file.getAbsolutePath(),bounds);
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(bounds.outWidth/options.inSampleSize>2400||bounds.outHeight/options.inSampleSize>2400)options.inSampleSize*=2;
                Bitmap bitmap=BitmapFactory.decodeFile(entry.file.getAbsolutePath(),options);if(bitmap==null)throw new java.io.IOException("Invalid image");
                runOnUiThread(()->{if(token!=revision||!MediaActions.alive(this)||!MediaActions.currentProfile(profile)){bitmap.recycle();return;}current=entry;view.setImageBitmap(bitmap);view.reset();loading.setVisibility(View.GONE);if(!hintShown){hintShown=true;Toast.makeText(this,"轻点图片可保存、分享或切换；双指可缩放",Toast.LENGTH_LONG).show();}});
            }catch(Exception failure){runOnUiThread(()->{if(token==revision&&MediaActions.alive(this)){failed=true;loading.setVisibility(View.GONE);status.setText("图片加载失败\n检查连接后点按重试");status.setVisibility(View.VISIBLE);}});}
        });
    }
    private int dp(int value){return ReaderUi.dp(this,value);}
    @Override protected void onDestroy(){revision++;if(view!=null)view.setImageDrawable(null);super.onDestroy();}
    private final class ZoomImage extends ImageView {
        private final Matrix transform=new Matrix();private final ScaleGestureDetector scale;private final GestureDetector gestures;private float zoom=1;
        ZoomImage(){super(MediaViewerActivity.this);setScaleType(ScaleType.MATRIX);
            scale=new ScaleGestureDetector(getContext(),new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){float factor=detector.getScaleFactor();float next=Math.max(.5f,Math.min(zoom*factor,8));factor=next/zoom;zoom=next;transform.postScale(factor,factor,detector.getFocusX(),detector.getFocusY());setImageMatrix(transform);return true;}});
            gestures=new GestureDetector(getContext(),new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){transform.postTranslate(-dx,-dy);setImageMatrix(transform);return true;}@Override public boolean onSingleTapConfirmed(MotionEvent e){performClick();return true;}@Override public void onLongPress(MotionEvent e){performLongClick();}@Override public boolean onDoubleTap(MotionEvent e){reset();return true;}});
        }
        void reset(){post(()->{if(getDrawable()==null)return;float width=getDrawable().getIntrinsicWidth(),height=getDrawable().getIntrinsicHeight();float fit=Math.min(getWidth()/width,getHeight()/height);transform.reset();transform.postScale(fit,fit);transform.postTranslate((getWidth()-width*fit)/2,(getHeight()-height*fit)/2);zoom=1;setImageMatrix(transform);});}
        @Override public boolean performClick(){return super.performClick();}
        @Override public boolean onTouchEvent(MotionEvent event){scale.onTouchEvent(event);if(!scale.isInProgress())gestures.onTouchEvent(event);return true;}
    }
}
