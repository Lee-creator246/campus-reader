package org.campusreader.reader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Registration only; never performs network/SDK authentication inside a receiver. */
public final class RefreshBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent!=null&&(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())||Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())))RefreshScheduler.schedule(context);
    }
}
