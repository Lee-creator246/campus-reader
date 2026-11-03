package org.campusreader.reader;

import android.app.Application;
import org.campusreader.portal.CampusPortalParser;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class CampusActionTest {
    public static class ReaderProbe extends ReaderActivity {
        int refreshed;String selected;
        @Override protected void showList(){}
        @Override protected void refresh(boolean more){refreshed++;try{selected=(String)field("source").get(this);}catch(Exception e){throw new AssertionError(e);}}
    }
    private static java.lang.reflect.Field field(String name)throws Exception{java.lang.reflect.Field f=ReaderActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    @Test public void campusActionFromRssChecksCampusInsteadOfRefreshingRss()throws Exception{
        ReaderProbe reader=Robolectric.buildActivity(ReaderProbe.class).get();
        field("preferences").set(reader,RuntimeEnvironment.getApplication().getSharedPreferences("probe",0));
        field("profile").set(reader,"local");field("source").set(reader,"rss:https://feed.example.org/rss");
        reader.checkCampusPortal();assertEquals(1,reader.refreshed);assertEquals(CampusPortalParser.SOURCE,reader.selected);
        assertFalse((Boolean)field("priorityOnly").get(reader));reader.onDestroy();
    }
}
