package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Real Android layout/graphics with synthetic copy; these are host renders, not phone captures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderLayoutTest {
    private Context themed(float fontScale){
        Context base=RuntimeEnvironment.getApplication();
        Configuration config=new Configuration(base.getResources().getConfiguration());
        config.fontScale=fontScale;
        return new ContextThemeWrapper(base.createConfigurationContext(config),R.style.Theme_CampusReader);
    }
    private ViewGroup inflate(Context context,int resource){return (ViewGroup)LayoutInflater.from(context).inflate(resource,null,false);}
    private void layout(View view,int widthDp){
        int width=ReaderUi.dp(view.getContext(),widthDp);
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        view.layout(0,0,width,view.getMeasuredHeight());
    }
    private Rect bounds(ViewGroup root,View child){Rect rect=new Rect();child.getDrawingRect(rect);root.offsetDescendantRectToMyCoords(child,rect);return rect;}
    private void render(View view,String name)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(ReaderUi.SURFACE);view.draw(canvas);
        boolean ink=false;for(int y=0;y<bitmap.getHeight();y+=3)for(int x=0;x<bitmap.getWidth();x+=3)if(bitmap.getPixel(x,y)!=ReaderUi.SURFACE){ink=true;break;}
        assertTrue("The native render should contain content",ink);
        File directory=new File("build/verification/ui-v7");assertTrue(directory.isDirectory()||directory.mkdirs());
        try(FileOutputStream stream=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}
        bitmap.recycle();
    }
    @Test public void longCardTitleRemainsBelowImageOnNarrowLargeTextScreen()throws Exception{
        Context context=themed(1.3f);ViewGroup card=inflate(context,R.layout.reader_item_article_card);
        TextView title=card.findViewById(R.id.reader_content_txt);title.setText("关于开展校园志愿服务活动及参加本学期阅读交流活动的通知");
        ((TextView)card.findViewById(R.id.reader_title_txt)).setText("校内通知");
        ((TextView)card.findViewById(R.id.reader_date)).setText("2026-01-01");
        card.findViewById(R.id.reader_photo_preview).setBackgroundColor(ReaderUi.TINT);
        layout(card,320);
        assertTrue(bounds(card,title).top>=bounds(card,card.findViewById(R.id.reader_photo_preview)).bottom);
        assertTrue(bounds(card,title).bottom<=card.getMeasuredHeight());
        assertTrue(title.getLayout().getLineCount()>1);
        assertEquals(ReaderUi.INK,title.getCurrentTextColor());
        assertEquals(View.GONE,card.findViewById(R.id.reader_card_gradient).getVisibility());
        render(card,"article-card-320-large");
    }
    @Test public void allFiveReaderActionsKeepTouchTargetsWithLargeText()throws Exception{
        Context context=themed(1.3f);ViewGroup bar=inflate(context,R.layout.reader_reader_bottom_bar);
        int[] ids={R.id.reader_btn_read,R.id.reader_btn_summary,R.id.reader_btn_star,R.id.reader_btn_tag,R.id.reader_btn_more};
        String[] glyphs={"read","summary","star","tag","more"};
        for(int i=0;i<ids.length;i++)((TextView)bar.findViewById(ids[i])).setCompoundDrawablesWithIntrinsicBounds(null,ReaderUi.icon(context,glyphs[i],ReaderUi.INK),null,null);
        layout(bar,320);
        for(int id:ids){View action=bar.findViewById(id);assertTrue(action.getWidth()>=ReaderUi.dp(context,48));assertTrue(action.getHeight()>=ReaderUi.dp(context,48));assertTrue(bounds(bar,action).bottom<=bar.getHeight());}
        render(bar,"reading-actions-320-large");
    }
    @Test public void settingsCategoryDescriptionGrowsWithoutClipping()throws Exception{
        Context context=themed(1.3f);ViewGroup row=inflate(context,R.layout.reader_item_settings);
        TextView title=row.findViewById(R.id.reader_main_text),description=row.findViewById(R.id.reader_settings_description);
        title.setText("刷新与提醒");description.setText("自动刷新、门户登录恢复和系统通知权限");
        ReaderUi.selectable(row);row.setFocusableInTouchMode(true);assertTrue(row.requestFocus());layout(row,320);
        assertTrue(bounds(row,description).bottom<=row.getHeight());
        assertTrue(row.getHeight()>=ReaderUi.dp(context,76));assertTrue(row.getBackground().isStateful());
        render(row,"settings-category-focused-320-large");
    }
}
