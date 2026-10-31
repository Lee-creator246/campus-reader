package org.campusreader.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import org.campusreader.config.CampusSite;

/** Private, device-local configuration. No passwords, export, logging, or SDK calls in load(). */
public final class CampusConfiguration {
    private static final String STORE = "campus-configuration";
    private static volatile boolean loaded, consent, changing, restartRequired;
    private CampusConfiguration() {}

    public static synchronized void load(Context context) {
        if (loaded) return; // A warm Activity restart must never apply a pending native SDK change.
        loaded = true;
        CampusSite.clear();
        SharedPreferences prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        consent = prefs.getBoolean("connection_sdk_consent", false);
        if (!consent) return;
        try {
            CampusSite.install(CampusSite.create(prefs.getString("domain", ""),
                    prefs.getString("vpn_url", ""), prefs.getString("portal_list_url", ""),
                    prefs.getString("login_url", "")));
        } catch (IllegalArgumentException invalidStoredSettings) {
            consent = false;
        }
    }

    public static boolean isConfigured() { return consent && CampusSite.isConfigured() && !changing && !restartRequired; }
    public static boolean hasConsent() { return consent; }
    public static boolean isChanging() { return changing; }
    public static boolean requiresRestart() { return restartRequired; }
    public static void requireConnectionAllowed() {
        if (!isConfigured()) throw new IllegalStateException(restartRequired
                ? "校园配置已保存，请关闭应用后重新打开" : "请先填写校园配置并同意连接服务说明");
    }

    static void beginChange(Context context) {
        requireMain();
        if (changing || restartRequired) throw new IllegalStateException("请完成当前配置操作");
        changing = true;
        restartRequired = CampusSdk.wasInitializationAttempted();
        CampusApplication.auth().stopForConfigurationChange();
    }

    /** Called only after WebView cookies, credentials and storage have been cleared. */
    static boolean save(Context context, CampusSite.Profile next, boolean accepted) {
        requireMain();
        if (!changing || !accepted || next == null || !next.configured) throw new IllegalStateException("请检查校园配置");
        SharedPreferences.Editor settings = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit();
        // The native SDK owns a single ticket. Any configuration save requires a new OTP,
        // including switching back to a previously visited site.
        if (!accountPreferences(context, next.scopeId).edit()
                .putBoolean("restore_allowed", false).putBoolean("user_stopped", true).commit())
            throw new IllegalStateException("无法保存校园配置，请重试");
        if (!settings.putString("domain", next.domain).putString("vpn_url", next.vpnUrl)
                .putString("portal_list_url", next.portalListUrl).putString("login_url", next.loginUrl)
                .putBoolean("connection_sdk_consent", true).remove("sdk_scope").commit())
            throw new IllegalStateException("无法保存校园配置，请重试");
        consent = true;
        if (!restartRequired) {
            CampusSite.install(next);
            CampusApplication.auth().onConfigurationInstalled();
        }
        changing = false;
        return restartRequired;
    }

    static void abortChange() { changing = false; }
    static SharedPreferences accountPreferences(Context context, String scope) {
        return context.getSharedPreferences("campus-account-" + scope, Context.MODE_PRIVATE);
    }
    static boolean ownsSdkTicket(Context context, String scope) {
        return scope.equals(context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("sdk_scope", ""));
    }
    static void recordSdkTicket(Context context, String scope) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString("sdk_scope", scope).apply();
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Call from main thread");
    }
}
