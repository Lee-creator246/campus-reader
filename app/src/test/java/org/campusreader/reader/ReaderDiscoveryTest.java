package org.campusreader.reader;

import android.app.Application;
import android.view.View;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class ReaderDiscoveryTest {
    @Test public void topicSelectorScrollsAndKeepsReadableTouchTargets(){
        android.content.Context app=RuntimeEnvironment.getApplication();List<String> chosen=new ArrayList<>();
        HorizontalScrollView view=ReaderDiscoveryBar.create(app,Arrays.asList("全部标签","志愿实践","奖助评优","就业招聘","教学教务","科研竞赛","行政公示","生活服务","更多标签"),"志愿实践",chosen::add);
        view.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,320,view.getMeasuredHeight());
        LinearLayout row=(LinearLayout)view.getChildAt(0);assertTrue(row.getMeasuredWidth()>320);
        for(int i=0;i<row.getChildCount();i++){View choice=row.getChildAt(i);assertTrue(choice.getMeasuredHeight()>=ReaderUi.dp(app,48));assertTrue(choice.getMeasuredWidth()>=ReaderUi.dp(app,48));}
        assertTrue(row.getChildAt(1).isSelected());row.getChildAt(3).performClick();assertEquals(Collections.singletonList("就业招聘"),chosen);
    }
    @Test public void summaryPanelDoesNotTakeOverTheEntireArticleScreen(){
        android.content.Context app=RuntimeEnvironment.getApplication();TextView longSummary=new TextView(app);longSummary.setText(String.join("\n",Collections.nCopies(80,"摘要中的一条信息与依据")));
        ReaderSummaryPanel panel=new ReaderSummaryPanel(app,longSummary);panel.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.AT_MOST));
        assertTrue(panel.getMeasuredHeight()<=ReaderUi.dp(app,240));assertTrue(panel.getChildAt(0).getMeasuredHeight()>panel.getMeasuredHeight());
    }
}
