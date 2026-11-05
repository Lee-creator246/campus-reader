package org.campusreader.reader;

import android.app.*;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.Collections;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Simulates the picker result arriving before the parent Activity resumes. No live provider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AiUiTest.ShortPathApplication.class,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PolicyLibraryActivityTest {
    @Test public void policyChangeWhileVisibleDismissesOldExtractedText()throws Exception{
        AiUiTest.ShortPathApplication app=(AiUiTest.ShortPathApplication)RuntimeEnvironment.getApplication();prepare(app);
        PolicyLibrary library=new PolicyLibrary(app,"local");PolicyLibrary.Document document=library.add(metadata(),Collections.singletonList("合成测试条款。"));
        ActivityController<PolicyLibraryActivity> controller=open(app);
        try{PolicyLibraryActivity activity=controller.get();findText(activity.findViewById(android.R.id.content),"查看文字与页码").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing());
            library.delete(document.id);shadowOf(Looper.getMainLooper()).idleFor(600,TimeUnit.MILLISECONDS);awaitLoad(activity);assertFalse(dialog.isShowing());assertNull(findText(activity.findViewById(android.R.id.content),document.title));
        }finally{controller.pause().stop().destroy();app.dispose();}
    }
    @Test public void profileSwitchHidesPolicyDialogAndStopsActivity()throws Exception{
        AiUiTest.ShortPathApplication app=(AiUiTest.ShortPathApplication)RuntimeEnvironment.getApplication();prepare(app);new PolicyLibrary(app,"local").add(metadata(),Collections.singletonList("合成测试条款。"));
        ActivityController<PolicyLibraryActivity> controller=open(app);
        try{PolicyLibraryActivity activity=controller.get();findText(activity.findViewById(android.R.id.content),"查看文字与页码").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing());
            app.getSharedPreferences("reader-local",0).edit().putString(ReaderPreferences.lastProfileKey(),"campus:"+org.campusreader.notices.SummaryInput.hash("another-synthetic-profile")).commit();shadowOf(Looper.getMainLooper()).idleFor(600,TimeUnit.MILLISECONDS);assertTrue(activity.isFinishing());assertFalse(dialog.isShowing());
        }finally{controller.pause().stop().destroy();app.dispose();}
    }
    @Test public void pickerResultBeforeResumeStillOpensMetadataConfirmation()throws Exception{
        AiUiTest.ShortPathApplication app=(AiUiTest.ShortPathApplication)RuntimeEnvironment.getApplication();
        app.getSharedPreferences("reader-local",0).edit().clear().putString(ReaderPreferences.lastProfileKey(),"local").commit();
        File source=File.createTempFile("cr-policy-picker-",".txt");Files.write(source.toPath(),"合成资料：仅作测试。".getBytes(StandardCharsets.UTF_8));
        CountDownLatch opened=new CountDownLatch(1),release=new CountDownLatch(1);
        ContentProvider provider=new ContentProvider(){
            @Override public boolean onCreate(){return true;}
            @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});cursor.addRow(new Object[]{"synthetic.txt",source.length()});return cursor;}
            @Override public String getType(Uri uri){return "text/plain";}
            @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
                opened.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new FileNotFoundException("Synthetic provider timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new FileNotFoundException("Cancelled");}
                return ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);
            }
            @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
            @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
            @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
        };
        ProviderInfo info=new ProviderInfo();info.authority="synthetic-policy-provider";provider.attachInfo(app,info);ShadowContentResolver.registerProviderInternal(info.authority,provider);
        ActivityController<PolicyLibraryActivity> controller=Robolectric.buildActivity(PolicyLibraryActivity.class,new Intent(app,PolicyLibraryActivity.class).putExtra("profile","local")).create().start();
        try{
            PolicyLibraryActivity activity=controller.get();activity.onActivityResult(421,Activity.RESULT_OK,new Intent().setData(Uri.parse("content://synthetic-policy-provider/document")));
            Future<?> pending=ReflectionHelpers.getField(activity,"task");assertTrue(opened.await(2,TimeUnit.SECONDS));controller.resume().visible();release.countDown();pending.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing());assertEquals("核对并添加政策",shadowOf(dialog).getTitle());
            assertEquals(!Boolean.getBoolean("campus.captureAllowed"), (dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            assertEquals(0,new PolicyLibrary(app,"local").snapshot().documents.size());assertEquals(0,new AiSettings(app,"local").usedToday());
            dialog.dismiss();
        }finally{release.countDown();controller.pause().stop().destroy();source.delete();app.dispose();}
    }
    private static void prepare(AiUiTest.ShortPathApplication app){app.getSharedPreferences("reader-local",0).edit().clear().putString(ReaderPreferences.lastProfileKey(),"local").commit();}
    private static PolicyLibrary.Metadata metadata(){return new PolicyLibrary.Metadata("合成验证政策","","","","","","待核验","synthetic.txt");}
    private static ActivityController<PolicyLibraryActivity> open(AiUiTest.ShortPathApplication app)throws Exception{
        ActivityController<PolicyLibraryActivity> controller=Robolectric.buildActivity(PolicyLibraryActivity.class,new Intent(app,PolicyLibraryActivity.class).putExtra("profile","local")).setup();awaitLoad(controller.get());return controller;
    }
    private static void awaitLoad(PolicyLibraryActivity activity)throws Exception{Future<?> future=ReflectionHelpers.getField(activity,"task");if(future!=null)future.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();}
    private static TextView findText(View view,String value){if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView match=findText(group.getChildAt(i),value);if(match!=null)return match;}}return null;}
}
