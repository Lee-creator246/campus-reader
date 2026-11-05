package org.campusreader.reader;
import org.campusreader.auth.ScreenPrivacy;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Voluntary local questionnaire. Only an explicit Android share action can send the preview. */
public final class SurveyActivity extends Activity {
    public static final String EXTRA_PROFILE="profile";
    private static final String STATEMENT="本问卷仅用于改善北邮人信息的使用体验。参与自愿，跳过不影响使用。答卷不会自动上传，也不会附带校园账号、阅读记录或设备标识；你可以预览后自行分享给项目维护者。请勿填写姓名、学号、密码、API Key 或粘贴通知正文。";
    private Spinner recommendation,notifications,summary;
    private EditText notes;
    private String profile;
    private AlertDialog previewDialog;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);profile=getIntent().getStringExtra(EXTRA_PROFILE);if(!PreferenceProfile.ownsCurrent(this,profile)){finish();return;}
        ScreenPrivacy.protect(getWindow());getWindow().setStatusBarColor(ReaderUi.SURFACE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll=new ScrollView(this);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(16),dp(20),dp(28));page.setBackgroundColor(ReaderUi.SURFACE);scroll.addView(page);setContentView(scroll);
        Button back=button("返回",this::finish);page.addView(back);text(page,"体验反馈",24,ReaderUi.INK);text(page,STATEMENT,14,ReaderUi.MUTED);
        recommendation=rating(page,"推荐内容是否符合你的关注？");notifications=rating(page,"提醒是否及时且不过度打扰？");summary=rating(page,"AI 摘要是否简洁、准确且有帮助？");
        text(page,"建议或遇到的问题（选填）",17,ReaderUi.INK);notes=new EditText(this);notes.setHint("简述体验问题，不填写个人凭据");notes.setMinLines(4);notes.setGravity(Gravity.TOP);notes.setSaveEnabled(false);notes.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1500)});page.addView(notes,new LinearLayout.LayoutParams(-1,-2));
        page.addView(button("预览答卷并选择是否分享",this::preview));text(page,"当前版本未配置问卷接收服务器。只有你在分享面板选择接收方后，答卷才会交给所选应用。",13,ReaderUi.MUTED);
    }
    private Spinner rating(LinearLayout page,String question){text(page,question,17,ReaderUi.INK);Spinner spinner=new Spinner(this);spinner.setContentDescription(question);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"尚未使用 / 暂不评价","1  很不满意","2  不太满意","3  一般","4  满意","5  很满意"});adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);page.addView(spinner,new LinearLayout.LayoutParams(-1,dp(54)));return spinner;}
    private void preview(){
        if(!PreferenceProfile.ownsCurrent(this,profile)){finish();return;}
        final String payload;try{payload=new JSONObject().put("questionnaire_version",1).put("purpose","改善使用体验").put("recommendation",recommendation.getSelectedItemPosition()).put("notifications",notifications.getSelectedItemPosition()).put("ai_summary",summary.getSelectedItemPosition()).put("notes",notes.getText().toString().trim()).toString(2);}catch(JSONException invalid){return;}
        TextView preview=ReaderUi.text(this,payload,14,ReaderUi.INK);preview.setTextIsSelectable(true);preview.setPadding(dp(20),dp(12),dp(20),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(preview);
        previewDialog=new AlertDialog.Builder(this).setTitle("答卷预览：仅以下内容会分享").setView(scroll).setPositiveButton("选择分享对象",(d,w)->{if(!PreferenceProfile.ownsCurrent(this,profile)){finish();return;}Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"北邮人信息体验反馈").putExtra(Intent.EXTRA_TEXT,payload);try{startActivity(Intent.createChooser(send,"自行选择答卷接收方"));}catch(RuntimeException unavailable){Toast.makeText(this,"没有可用的分享应用",Toast.LENGTH_SHORT).show();}}).setNegativeButton("返回修改",null).show();
    }
    private Button button(String value,Runnable action){Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());return button;}
    private void text(LinearLayout page,String value,int size,int color){TextView text=ReaderUi.text(this,value,size,color);text.setPadding(0,dp(12),0,dp(10));text.setLineSpacing(dp(4),1);page.addView(text);}
    private int dp(int value){return ReaderUi.dp(this,value);}
    @Override protected void onResume(){super.onResume();if(profile!=null&&!PreferenceProfile.ownsCurrent(this,profile))finish();}
    @Override protected void onStop(){if(previewDialog!=null){previewDialog.dismiss();previewDialog=null;}super.onStop();}
}
