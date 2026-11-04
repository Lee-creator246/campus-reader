package org.campusreader.reader;

import android.content.*;

/** No network, password restoration or model calls from a reminder broadcast. */
public final class NoticeReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!NoticeReminders.ACTION.equals(intent.getAction())||intent.getData()==null)return;
        PendingResult pending=goAsync();Context app=context.getApplicationContext();String id=intent.getData().getLastPathSegment();
        new Thread(()->{try{NoticeReminders.deliver(app,id);}finally{pending.finish();}},"notice-reminder").start();
    }
}
