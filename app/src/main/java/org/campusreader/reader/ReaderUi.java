package org.campusreader.reader;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

/** Project-authored native presentation helpers and geometric icons, licensed under MIT. */
public final class ReaderUi {
    public static final int BLUE=0xff2767d8,INK=0xff202124,MUTED=0xff687078,BG=0xfff5f6f8,LINE=0xffe8eaed;
    private ReaderUi(){}
    public static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    public static GradientDrawable background(int color,int radiusDp,Context c){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radiusDp));return d;}
    public static TextView text(Context c,String value,int size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    public static Drawable icon(Context c,String name,int color){return new Glyph(dp(c,24),name,color);}
    public static ImageButton iconButton(Context c,String name,String description,View.OnClickListener listener){
        ImageButton b=new ImageButton(c);b.setImageDrawable(icon(c,name,INK));b.setContentDescription(description);
        b.setScaleType(ImageView.ScaleType.CENTER);b.setPadding(0,0,0,0);b.setMinimumWidth(dp(c,48));b.setMinimumHeight(dp(c,48));
        android.util.TypedValue value=new android.util.TypedValue();c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,value,true);
        if(value.resourceId!=0)b.setBackgroundResource(value.resourceId);else b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(listener);return b;
    }
    private static final class Glyph extends Drawable {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size,color;private final String name;private int alpha=255;private boolean enabled=true;
        Glyph(int size,String name,int color){this.size=size;this.name=name;this.color=color;p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        @Override public int getIntrinsicWidth(){return size;}
        @Override public int getIntrinsicHeight(){return size;}
        @Override public boolean isStateful(){return true;}
        @Override protected boolean onStateChange(int[] states){boolean value=false;for(int state:states)if(state==android.R.attr.state_enabled)value=true;if(value==enabled)return false;enabled=value;invalidateSelf();return true;}
        @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        private void line(Canvas c,float... xy){Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);c.drawPath(path,p);}
        private void bookmark(Canvas c,boolean filled){p.setStyle(filled?Paint.Style.FILL:Paint.Style.STROKE);line(c,7,3,17,3,17,21,12,17,7,21,7,3);}
        @Override public void draw(Canvas c){
            Rect b=getBounds();int saved=c.save();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);
            p.setColor(color);p.setAlpha(Math.round(alpha*(enabled?1f:.38f)));p.setStyle(Paint.Style.STROKE);
            switch(name){
                case "menu":line(c,4,6,20,6);line(c,4,12,20,12);line(c,4,18,20,18);break;
                case "search":c.drawCircle(10,10,6,p);line(c,14.5f,14.5f,21,21);break;
                case "summary":line(c,4,5,14,5);line(c,4,10,12,10);line(c,4,15,10,15);line(c,16,10,17.5f,14.5f,22,16,17.5f,17.5f,16,22,14.5f,17.5f,10,16,14.5f,14.5f,16,10);break;
                case "check":case "read":case "article_read":line(c,4,12,9,17,20,6);break;
                case "unread":case "article_unread":c.drawCircle(12,12,8,p);c.drawCircle(12,12,2,p);break;
                case "star":case "article_star":bookmark(c,false);break;
                case "star_filled":case "article_star_filled":bookmark(c,true);break;
                case "tag":case "tag_filled":if(name.endsWith("filled"))p.setStyle(Paint.Style.FILL);line(c,3,3,12,3,21,12,12,21,3,12,3,3);p.setStyle(Paint.Style.STROKE);c.drawCircle(8,8,1,p);break;
                case "share":line(c,7,11,17,6);line(c,7,13,17,18);c.drawCircle(5,12,2,p);c.drawCircle(19,5,2,p);c.drawCircle(19,19,2,p);break;
                case "more":p.setStyle(Paint.Style.FILL);for(int y=5;y<=19;y+=7)c.drawCircle(12,y,1.5f,p);break;
                case "close":line(c,5,5,19,19);line(c,19,5,5,19);break;
                case "back":line(c,10,5,3,12,10,19);line(c,3,12,21,12);break;
                case "next":line(c,8,5,15,12,8,19);break;
                case "previous":line(c,16,5,9,12,16,19);break;
                case "down":line(c,5,8,12,15,19,8);break;
                case "up":line(c,5,16,12,9,19,16);break;
                case "download":line(c,12,3,12,16);line(c,7,11,12,16,17,11);line(c,4,17,4,21,20,21,20,17);break;
                case "refresh":c.drawArc(new RectF(4,4,20,20),35,285,false,p);line(c,20,3,20,9,14,9);break;
                case "eye":Path eye=new Path();eye.moveTo(2,12);eye.quadTo(12,-2,22,12);eye.quadTo(12,26,2,12);c.drawPath(eye,p);c.drawCircle(12,12,3,p);break;
                case "link":c.save();c.rotate(-40,12,12);c.drawRoundRect(3,8,13,16,4,4,p);c.drawRoundRect(11,8,21,16,4,4,p);line(c,9,12,15,12);c.restore();break;
                case "feed":c.drawArc(new RectF(-12,4,20,36),270,90,false,p);c.drawArc(new RectF(-5,11,13,29),270,90,false,p);p.setStyle(Paint.Style.FILL);c.drawCircle(4,20,1.8f,p);break;
                case "grid":for(int x=4;x<20;x+=10)for(int y=4;y<20;y+=10)c.drawRoundRect(x,y,x+6,y+6,1,1,p);break;
                case "list":for(int y=5;y<23;y+=7){line(c,9,y,21,y);c.drawCircle(4,y,.8f,p);}break;
                case "magazine":c.drawRect(3,4,9,10,p);c.drawRect(3,14,9,20,p);line(c,13,5,21,5);line(c,13,9,19,9);line(c,13,15,21,15);line(c,13,19,19,19);break;
                case "folder":case "folder_open":line(c,3,6,10,6,12,9,21,9,21,20,3,20,3,6);break;
                case "settings_profile":c.drawCircle(12,7,4,p);c.drawArc(new RectF(4,13,20,27),180,180,false,p);break;
                case "settings_notifications":line(c,5,17,7,14,7,9,9,5,15,5,17,9,17,14,19,17,5,17);c.drawArc(new RectF(9,17,15,23),0,180,false,p);break;
                case "settings_about":c.drawCircle(12,12,9,p);line(c,12,10,12,17);p.setStyle(Paint.Style.FILL);c.drawCircle(12,6.5f,1,p);break;
                case "settings":case "settings_interface":case "settings_miscellaneous":for(int y=6;y<=18;y+=6)line(c,3,y,21,y);line(c,8,3,8,9);line(c,16,9,16,15);line(c,8,15,8,21);break;
                default:line(c,3,4,10,4,12,6,14,4,21,4,21,20,14,20,12,22,10,20,3,20,3,4);line(c,12,6,12,22);break;
            }
            c.restoreToCount(saved);
        }
    }
}
