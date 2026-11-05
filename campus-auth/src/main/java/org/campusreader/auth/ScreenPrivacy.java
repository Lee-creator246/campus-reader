package org.campusreader.auth;

import android.view.Window;
import android.view.WindowManager;

/** Capture policy is fixed at build time, including for separately created dialogs. */
public final class ScreenPrivacy {
    private ScreenPrivacy() {}

    public static void protect(Window window) {
        if (window == null) return;
        if (window.getContext().getResources().getBoolean(R.bool.allow_screen_capture)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
