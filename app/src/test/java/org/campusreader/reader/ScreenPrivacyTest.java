package org.campusreader.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.view.Window;
import android.view.WindowManager;
import org.campusreader.auth.ScreenPrivacy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, manifest = Config.NONE)
public class ScreenPrivacyTest {
    @Test public void compiledVariantControlsBothActivityAndDialogWindows() {
        boolean captureAllowed = Boolean.getBoolean("campus.captureAllowed");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            assertEquals(captureAllowed, activity.getResources().getBoolean(
                    org.campusreader.auth.R.bool.allow_screen_capture));
            verifyWindow(activity.getWindow(), captureAllowed);
            AlertDialog dialog = new AlertDialog.Builder(activity).setMessage("Synthetic preview").create();
            dialog.show();
            try {
                verifyWindow(dialog.getWindow(), captureAllowed);
            } finally {
                dialog.dismiss();
            }
        }
    }

    private static void verifyWindow(Window window, boolean captureAllowed) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScreenPrivacy.protect(window);
        assertEquals(!captureAllowed,
                (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertTrue((window.getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScreenPrivacy.protect(window);
        assertEquals(!captureAllowed,
                (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }
}
