package org.campusreader.auth;

import android.content.Context;
import android.os.Looper;
import com.sangfor.sdk.SFUemSDK;
import com.sangfor.sdk.base.SFSDKFlags;
import com.sangfor.sdk.base.SFSDKMode;
import com.sangfor.sdk.base.SFSDKOptions;

/** Initializes the actual 2.7.10.1 distribution, following its Android L3VPN guide. */
public final class CampusSdk {
    // Set before entering vendor initialization: even a partially failed init requires restart
    // before another site's settings may be installed in this process.
    private static volatile boolean initializationAttempted;
    private static volatile boolean initialized;
    private CampusSdk() {}
    public static void initialize(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("SDK initialization requires main thread");
        CampusConfiguration.requireConnectionAllowed();
        if (initialized) return;
        if (initializationAttempted) throw new IllegalStateException("请关闭应用后重新打开，再尝试连接");
        initializationAttempted = true;
        int flags = SFSDKFlags.FLAGS_HOST_APPLICATION | SFSDKFlags.FLAGS_VPN_MODE_L3VPN;
        SFUemSDK.getInstance().initSDK(context, SFSDKMode.MODE_SUPPORT_MUTABLE, flags, null);
        // Keep vendor-supported image challenges and terminal binding UI enabled.
        SFUemSDK.getInstance().getSFConfig().setOptions(SFSDKOptions.OPTIONS_KEY_RAND_SDK, "1");
        SFUemSDK.getInstance().getSFConfig().setOptions(SFSDKOptions.OPTIONS_KEY_TRUST_TERMINAL_SDK, "1");
        initialized = true;
    }
    public static boolean isInitialized() { return initialized; }
    public static boolean wasInitializationAttempted() { return initializationAttempted; }
}
