package org.campusreader.reader;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Measures the real native form without starting SDK, WebView navigation or a login. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE,qualifiers="w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class PortalLoginLayoutTest {
    private Object field(PortalLoginActivity a,String name)throws Exception{
        java.lang.reflect.Field f=PortalLoginActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);
    }
    @Test public void keyboardSizedWindowKeepsFormScrollableAndFooterReachable()throws Exception{
        PortalLoginActivity a=Robolectric.buildActivity(PortalLoginActivity.class).get();
        try{
            java.lang.reflect.Field account=PortalLoginActivity.class.getDeclaredField("account");account.setAccessible(true);account.set(a,"student-test");
            java.lang.reflect.Method build=PortalLoginActivity.class.getDeclaredMethod("build");build.setAccessible(true);build.invoke(a);
            LinearLayout form=(LinearLayout)field(a,"nativeForm");ScrollView scroll=(ScrollView)field(a,"formScroll");
            form.setVisibility(View.VISIBLE);scroll.setVisibility(View.VISIBLE);
            ViewGroup container=a.findViewById(android.R.id.content);
            container.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY));container.layout(0,0,320,360);
            LinearLayout root=(LinearLayout)container.getChildAt(0);View actions=root.getChildAt(3);
            assertTrue(scroll.getHeight()>0);assertTrue(scroll.getHeight()<form.getHeight());
            assertTrue("footer="+actions.getBottom()+", root="+root.getHeight(),actions.getBottom()<=root.getHeight());
            // Robolectric does not render a live Chromium surface; this check covers native form/footer bounds.
            assertTrue(((CheckBox)field(a,"remember")).isChecked());
            assertEquals("保存并登录",((Button)field(a,"submit")).getText().toString());
            ((CheckBox)field(a,"remember")).setChecked(false);
            assertEquals("仅本次登录",((Button)field(a,"submit")).getText().toString());
        }finally{a.onDestroy();}
    }
}
