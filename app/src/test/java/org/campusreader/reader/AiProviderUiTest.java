package org.campusreader.reader;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import org.campusreader.auth.CampusApplication;
import org.campusreader.notices.SummaryInput;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CampusApplication.class,manifest=Config.NONE)
public class AiProviderUiTest {
    @Test public void choosingPresetDoesNotEnableConnectOrPersistUntilSave(){
        CampusApplication app=(CampusApplication)RuntimeEnvironment.getApplication();
        app.getSharedPreferences("reader-local",0).edit().clear().putString(ReaderPreferences.lastProfileKey(),"local").commit();
        app.getSharedPreferences("reader-ai-"+SummaryInput.hash("local"),0).edit().clear().commit();
        ActivityController<AiSettingsActivity> controller=Robolectric.buildActivity(AiSettingsActivity.class,new Intent(app,AiSettingsActivity.class).putExtra(AiSettingsActivity.EXTRA_PROFILE,"local")).setup();
        try{
            Activity activity=controller.get();assertFalse(activity.isFinishing());
            find(activity.findViewById(android.R.id.content),"使用 DeepSeek 官方预设").performClick();
            List<EditText> fields=new ArrayList<>();editors(activity.findViewById(android.R.id.content),fields);
            assertEquals(3,fields.size());assertEquals(AiProvider.DEEPSEEK.defaultEndpoint,fields.get(0).getText().toString());assertEquals(AiProvider.DEEPSEEK.defaultModel,fields.get(1).getText().toString());assertEquals("",fields.get(2).getText().toString());
            assertFalse(((Switch)find(activity.findViewById(android.R.id.content),"启用 AI 服务")).isChecked());
            AiSettings settings=new AiSettings(app,"local");assertEquals("",settings.endpoint());assertEquals(AiProvider.CUSTOM,settings.provider());assertFalse(settings.hasKey());assertEquals(0,settings.usedToday());assertFalse(CampusApplication.auth().isSdkInitialized());
            find(activity.findViewById(android.R.id.content),"保存设置").performClick();
            assertEquals(AiProvider.DEEPSEEK,settings.provider());assertEquals(AiProvider.DEEPSEEK.defaultEndpoint,settings.endpoint());assertFalse(settings.enabled());assertFalse(settings.hasKey());assertEquals(0,settings.usedToday());
            find(activity.findViewById(android.R.id.content),"使用自定义兼容接口").performClick();
            find(activity.findViewById(android.R.id.content),"保存设置").performClick();assertEquals(AiProvider.CUSTOM,settings.provider());assertFalse(settings.enabled());assertEquals(0,settings.usedToday());
        }finally{controller.pause().stop().destroy();}
    }
    private static TextView find(View view,String value){
        if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=find(group.getChildAt(i),value);if(found!=null)return found;}}return null;
    }
    private static void editors(View view,List<EditText> result){if(view instanceof EditText)result.add((EditText)view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)editors(group.getChildAt(i),result);}}
}
