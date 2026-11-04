package org.campusreader.reader;

import android.content.Context;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;

/** Compact, accessible selectors shared by reading modes and topic filters. */
final class ReaderDiscoveryBar {
    private ReaderDiscoveryBar(){}
    static HorizontalScrollView create(Context context,List<String> choices,String selected,Consumer<String> action){
        HorizontalScrollView scroll=new HorizontalScrollView(context);scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout line=new LinearLayout(context);line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(ReaderUi.dp(context,12),0,ReaderUi.dp(context,12),0);scroll.addView(line);
        for(String label:choices){
            boolean active=label.equals(selected);TextView item=new TextView(context);item.setText(label);
            item.setTextSize(14);item.setTextColor(active?ReaderUi.BLUE:ReaderUi.MUTED);item.setGravity(Gravity.CENTER);
            item.setPadding(ReaderUi.dp(context,12),0,ReaderUi.dp(context,12),0);
            item.setMinWidth(ReaderUi.dp(context,48));item.setMinHeight(ReaderUi.dp(context,48));
            item.setSelected(active);item.setContentDescription(label+(active?"，已选择":""));
            item.setFocusable(true);ReaderUi.selectable(item,active?ReaderUi.TINT:ReaderUi.SURFACE);
            item.setOnClickListener(v->action.accept(label));line.addView(item,new LinearLayout.LayoutParams(-2,ReaderUi.dp(context,48)));
        }
        return scroll;
    }
}
