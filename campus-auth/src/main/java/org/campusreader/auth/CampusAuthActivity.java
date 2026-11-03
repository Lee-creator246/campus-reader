package org.campusreader.auth;

import org.campusreader.config.CampusSite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sangfor.sdk.base.SFAuthType;
import com.sangfor.sdk.base.SFBaseMessage;
import com.sangfor.sdk.base.SFRandCodeMessage;

/** Native account/dynamic-password UI; authentication truth remains owned by CampusAuthController. */
public final class CampusAuthActivity extends Activity implements CampusAuthController.Listener {
    private static final int CONFIGURATION = 201;
    private static final int BLUE=0xff2767d8,INK=0xff202124,MUTED=0xff647082,BACKGROUND=0xfff5f6f8;
    private CampusAuthController auth;
    private TextView status,stateBadge;
    private EditText account,password;
    private Button login,cancel,disconnect,returnToReader;
    private LinearLayout challengeBox;
    private boolean completing,autoReturnAfterConnect;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(!CampusConfiguration.isConfigured()){
            startActivityForResult(new Intent(this,CampusConfigActivity.class),CONFIGURATION);
            return;
        }
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(android.os.Build.VERSION.SDK_INT>=26?Color.WHITE:INK);
        int systemUi=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(android.os.Build.VERSION.SDK_INT>=26)systemUi|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
        auth=CampusApplication.auth();
        autoReturnAfterConnect=!auth.isAccessReady()&&!getIntent().getBooleanExtra("manageConnection",false);

        LinearLayout root=column();root.setBackgroundColor(BACKGROUND);
        if(android.os.Build.VERSION.SDK_INT>=26)root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        LinearLayout toolbar=new LinearLayout(this);toolbar.setOrientation(LinearLayout.HORIZONTAL);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setBackgroundColor(Color.WHITE);toolbar.setPadding(dp(8),dp(4),dp(16),dp(4));toolbar.setMinimumHeight(dp(56));
        Button back=button("‹",false);back.setTextSize(30);back.setContentDescription("返回阅读");back.setBackgroundColor(Color.TRANSPARENT);back.setOnClickListener(v->finish());toolbar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView toolbarTitle=text("校园连接",20,INK);toolbarTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);toolbarTitle.setPadding(dp(8),0,0,0);toolbar.addView(toolbarTitle,new LinearLayout.LayoutParams(0,-2,1));root.addView(toolbar);

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout page=column();page.setPadding(dp(20),dp(24),dp(20),dp(24));scroll.addView(page);
        TextView school=text("校园访问",22,INK);school.setTypeface(Typeface.DEFAULT,Typeface.BOLD);page.addView(school);
        TextView explanation=text("第 1 步：用校园账号和动态密码建立访问通道。\n第 2 步：返回阅读页检查或单独登录信息门户。两步都就绪后才能同步，门户密码不会交给校园连接界面。",14,MUTED);explanation.setLineSpacing(dp(3),1);add(page,explanation,8);

        LinearLayout form=card();add(page,form,24);
        account=new EditText(this);account.setId(View.generateViewId());account.setHint("请输入学工号");account.setSingleLine(true);account.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);styleInput(account);
        account.setSaveEnabled(false);
        TextView accountLabel=text("学工号",14,INK);accountLabel.setLabelFor(account.getId());form.addView(accountLabel);add(form,account,8);account.setText(auth.getProfileId());
        password=new EditText(this);password.setId(View.generateViewId());password.setHint("请输入六位动态密码");password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);styleInput(password);
        password.setSaveEnabled(false);if(android.os.Build.VERSION.SDK_INT>=26)password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        TextView passwordLabel=text("六位动态密码",14,INK);passwordLabel.setLabelFor(password.getId());add(form,passwordLabel,20);add(form,password,8);
        TextView help=text("在校园认证服务中获取动态密码，手动输入。",13,MUTED);help.setLineSpacing(dp(2),1);add(form,help,10);
        login=button("连接校园网",true);add(form,login,20);

        LinearLayout stateCard=card();add(page,stateCard,16);
        LinearLayout stateHeading=new LinearLayout(this);stateHeading.setOrientation(LinearLayout.HORIZONTAL);stateHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView stateTitle=text("校园通道状态",15,INK);stateTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);stateHeading.addView(stateTitle,new LinearLayout.LayoutParams(0,-2,1));
        stateBadge=text("未连接",12,BLUE);stateBadge.setPadding(dp(10),dp(4),dp(10),dp(4));stateBadge.setBackground(shape(0xffedf3ff,0,20));stateHeading.addView(stateBadge);stateCard.addView(stateHeading);
        status=text("尚未连接校园网",14,MUTED);status.setTextIsSelectable(true);status.setLineSpacing(dp(3),1);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(stateCard,status,10);
        TextView server=text("使用本地配置的校园访问入口",12,MUTED);add(stateCard,server,12);

        challengeBox=card();challengeBox.setVisibility(View.GONE);add(page,challengeBox,16);
        LinearLayout secondary=new LinearLayout(this);secondary.setOrientation(LinearLayout.HORIZONTAL);add(page,secondary,20);
        cancel=button("取消认证",false);disconnect=button("断开连接",false);
        LinearLayout.LayoutParams first=new LinearLayout.LayoutParams(0,-2,1);first.rightMargin=dp(6);secondary.addView(cancel,first);
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);second.leftMargin=dp(6);secondary.addView(disconnect,second);
        returnToReader=button("返回阅读",false);returnToReader.setTextColor(BLUE);returnToReader.setBackgroundColor(Color.TRANSPARENT);add(page,returnToReader,8);
        TextView cacheHelp=text("暂不连接也可以返回阅读，查看已缓存的通知。",13,MUTED);cacheHelp.setGravity(Gravity.CENTER);add(page,cacheHelp,4);

        returnToReader.setOnClickListener(v->{if(auth.isAccessReady())finishConnected();else finish();});
        login.setOnClickListener(v->{String code=password.getText().toString();password.setText("");autoReturnAfterConnect=true;
            try{auth.login(account.getText().toString(),code);}catch(RuntimeException e){status.setText(CampusConfiguration.isConfigured()?"未能开始认证，请检查账号、动态密码和连接状态后重试。":"请先完成校园配置，或关闭应用后重新打开。");}});
        cancel.setOnClickListener(v->{auth.cancel();challengeBox.removeAllViews();challengeBox.setVisibility(View.GONE);});
        disconnect.setOnClickListener(v->{auth.disconnect();challengeBox.removeAllViews();challengeBox.setVisibility(View.GONE);});
        setContentView(root);
    }
    @Override protected void onResume(){super.onResume();if(auth!=null)auth.setListener(this);}
    @Override protected void onPause(){if(auth!=null)auth.removeListener(this);super.onPause();}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=CONFIGURATION)return;
        if(result==RESULT_OK){recreate();return;}
        if(result==CampusConfigActivity.RESULT_RESTART_REQUIRED){
            finishAffinity();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),200);
        }else finish();
    }
    @Override public void onState(CampusAuthController.State state,long code,String message){
        CampusConnectionStatus snapshot=auth.connectionStatus(CampusConnectionStatus.PortalState.UNKNOWN);
        status.setText(message+"\n"+snapshot.detail+(code==0?"":"\n错误码："+code));
        boolean busy=state==CampusAuthController.State.RESTORING||state==CampusAuthController.State.AUTHENTICATING||state==CampusAuthController.State.CHALLENGE_REQUIRED||state==CampusAuthController.State.STARTING_TUNNEL;
        boolean ready=state==CampusAuthController.State.ACCESS_READY;
        login.setEnabled(!busy&&!ready);account.setEnabled(!busy&&!ready);password.setEnabled(!busy&&!ready);
        login.setAlpha(login.isEnabled()?1f:0.5f);
        cancel.setEnabled(busy);cancel.setAlpha(busy?1f:0.4f);
        disconnect.setEnabled(state!=CampusAuthController.State.IDLE);disconnect.setAlpha(disconnect.isEnabled()?1f:0.4f);
        // Returning to cached reading never requires successful network authentication.
        returnToReader.setEnabled(true);
        String badge;
        switch(state){case RESTORING:badge="恢复中";break;case AUTHENTICATING:badge="认证中";break;case CHALLENGE_REQUIRED:badge="待验证";break;case STARTING_TUNNEL:badge="连接中";break;case ACCESS_READY:badge="通道已连接";break;default:badge="未连接";}
        stateBadge.setText(badge);
        if(state!=CampusAuthController.State.CHALLENGE_REQUIRED){challengeBox.removeAllViews();challengeBox.setVisibility(View.GONE);}
        if(ready&&autoReturnAfterConnect)finishConnected();
    }
    private void finishConnected(){
        if(completing)return;completing=true;setResult(RESULT_OK,new Intent().putExtra("profileId",auth.getProfileId()));
        if(getCallingActivity()==null){Intent reader=new Intent().setClassName(getPackageName(),"org.campusreader.reader.ReaderActivity");if(reader.resolveActivity(getPackageManager())!=null)startActivity(reader);}finish();
    }
    @Override public void onChallenge(SFAuthType type,SFBaseMessage message){
        challengeBox.removeAllViews();challengeBox.setVisibility(View.VISIBLE);
        TextView prompt=text("",15,INK);prompt.setTypeface(Typeface.DEFAULT,Typeface.BOLD);challengeBox.addView(prompt);
        if(type==null){prompt.setText("学校返回了未知认证步骤，请取消后重试。");return;}
        if(message!=null&&message.mNextServiceList!=null&&message.mNextServiceList.size()>1){prompt.setText("学校提供了多个验证方式，当前版本还需要适配选择界面。认证尚未完成，可取消后重试。");return;}
        switch(type){
            case AUTH_TYPE_RAND:
                prompt.setText("请输入图形验证码");
                if(!(message instanceof SFRandCodeMessage)){prompt.setText("当前图形验证不是SDK返回的图片验证码，需要进一步适配；认证未完成。");return;}
                byte[] bytes=((SFRandCodeMessage)message).getImageBytes();
                if(bytes==null||bytes.length==0){prompt.setText("未取得验证码图片，请取消后重试。");return;}
                Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                if(bitmap==null){prompt.setText("验证码图片无法读取，请取消后重试。");return;}
                ImageView image=new ImageView(this);image.setContentDescription("学校图形验证码");image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setMaxHeight(dp(160));add(challengeBox,image,12);break;
            case AUTH_TYPE_SMS:prompt.setText("请输入学校发送的短信验证码");break;
            case AUTH_TYPE_TOKEN:case AUTH_TYPE_TOKEN_TOTP:case AUTH_TYPE_TOKEN_HTTPS:case AUTH_TYPE_TOKEN_RADIUS:prompt.setText("请输入此验证步骤要求的动态口令");break;
            case AUTH_TYPE_RADIUS:prompt.setText("请输入学校要求的认证答案");break;
            default:prompt.setText("还需要完成学校的认证步骤："+type.name()+"。当前界面尚未适配，认证未完成；请取消后重试。");return;
        }
        EditText answer=new EditText(this);answer.setId(View.generateViewId());answer.setSingleLine(true);answer.setHint("请输入验证内容");answer.setSaveEnabled(false);answer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);styleInput(answer);prompt.setLabelFor(answer.getId());
        if(android.os.Build.VERSION.SDK_INT>=26)answer.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);add(challengeBox,answer,12);
        Button submit=button("提交验证",true);add(challengeBox,submit,12);
        submit.setOnClickListener(v->{String value=answer.getText().toString();answer.setText("");try{auth.submitCode(value);}catch(RuntimeException e){status.setText("未能提交验证，请检查验证码或取消后重试。");}});
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private LinearLayout card(){LinearLayout layout=column();layout.setPadding(dp(18),dp(18),dp(18),dp(18));layout.setBackground(shape(Color.WHITE,0xffe5e8ee,14));return layout;}
    private GradientDrawable shape(int color,int border,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));if(border!=0)shape.setStroke(dp(1),border);return shape;}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(top);parent.addView(child,params);}
    private void styleInput(EditText edit){edit.setTextSize(16);edit.setTextColor(INK);edit.setHintTextColor(0xff8b93a1);edit.setMinHeight(dp(56));edit.setPadding(dp(14),dp(12),dp(14),dp(12));edit.setBackground(shape(BACKGROUND,0xffdce1e9,10));}
    private Button button(String value,boolean primary){Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setTextSize(15);button.setTextColor(primary?Color.WHITE:INK);button.setTypeface(Typeface.DEFAULT,primary?Typeface.BOLD:Typeface.NORMAL);button.setMinHeight(dp(primary?52:48));button.setPadding(dp(12),dp(10),dp(12),dp(10));button.setBackground(shape(primary?BLUE:Color.WHITE,primary?0:0xffdce1e9,10));return button;}
}
