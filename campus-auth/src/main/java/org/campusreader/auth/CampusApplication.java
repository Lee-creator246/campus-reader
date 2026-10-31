package org.campusreader.auth;

import android.app.Application;
import android.content.Context;
import android.app.ActivityManager;
import android.os.Build;
import android.app.Activity;
import android.os.Bundle;

/** Loads private settings without starting the connection SDK or making a network request. */
public class CampusApplication extends Application {
    private static CampusApplication instance;
    private CampusAuthController controller;
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        CampusConfiguration.load(base);
    }
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        controller = new CampusAuthController(this);
        if (isMainProcess()) {
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override public void onActivityStarted(Activity activity) {
                    // A scheduled job may create Application without a visible screen. Only a
                    // user-visible activity may start SDK restoration / Android VPN consent.
                    if (activity instanceof CampusConfigActivity) return;
                    controller.restoreSessionIfPossible();
                    controller.synchronizeSdkState();
                }
                @Override public void onActivityCreated(Activity activity, Bundle state) {}
                @Override public void onActivityResumed(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        }
    }
    public static CampusAuthController auth() {
        if (instance == null || instance.controller == null) throw new IllegalStateException("CampusApplication is not initialized");
        return instance.controller;
    }
    private boolean isMainProcess() {
        if (Build.VERSION.SDK_INT >= 28) return getPackageName().equals(Application.getProcessName());
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null || manager.getRunningAppProcesses() == null) return false;
        for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
            if (process.pid == android.os.Process.myPid()) return getPackageName().equals(process.processName);
        }
        return false;
    }
}
