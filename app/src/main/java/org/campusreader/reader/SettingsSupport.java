package org.campusreader.reader;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** User-initiated standard settings only; never grants permissions or whitelists automatically. */
public final class SettingsSupport {
    public static final String BACKGROUND_GUIDANCE="后台刷新由系统择机执行，省电模式、休眠及厂商限制可能延迟。若需要更及时，可在应用电池设置允许后台活动；部分手机还需在系统应用管理中手动允许自启动。Android 没有通用的厂商自启动授权接口。";
    private SettingsSupport(){}
    public static boolean openNotifications(Context context){
        Intent intent=Build.VERSION.SDK_INT>=26?new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.getPackageName()):details(context);
        return launch(context,intent)||openAppDetails(context);
    }
    public static boolean openBatterySettings(Context context){return launch(context,new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))||openAppDetails(context);}
    public static boolean openAppDetails(Context context){return launch(context,details(context));}
    private static Intent details(Context context){return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.getPackageName()));}
    private static boolean launch(Context context,Intent intent){try{context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}catch(RuntimeException unavailable){return false;}}
}
