package org.campusreader.reader;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

/** Leaves room for the full notice even when an expanded summary contains several points. */
final class ReaderSummaryPanel extends ScrollView {
    ReaderSummaryPanel(Context context,View content){super(context);setFillViewport(false);addView(content);}
    @Override protected void onMeasure(int width,int height){
        int limit=ReaderUi.dp(getContext(),240);
        if(MeasureSpec.getMode(height)!=MeasureSpec.UNSPECIFIED)limit=Math.min(limit,MeasureSpec.getSize(height));
        super.onMeasure(width,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST));
    }
}
