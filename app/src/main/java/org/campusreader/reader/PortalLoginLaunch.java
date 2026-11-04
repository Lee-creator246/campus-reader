package org.campusreader.reader;

import android.os.SystemClock;

/** A one-use, process-local handoff. Secrets never enter an Intent, Bundle or saved state. */
final class PortalLoginLaunch {
    private static PortalCredentialStore.Credentials value;
    private static String owner="";
    private static boolean remember;
    private static long expires;
    static synchronized void put(String profile,String account,String password,boolean keep){
        clear();owner=profile;value=new PortalCredentialStore.Credentials(account,password,"");remember=keep;expires=SystemClock.elapsedRealtime()+60_000L;
    }
    static synchronized PortalCredentialStore.Credentials take(String profile){
        if(!owner.equals(profile)||SystemClock.elapsedRealtime()>expires){clear();return null;}
        PortalCredentialStore.Credentials result=value;value=null;return result;
    }
    static synchronized boolean remembers(String profile){return owner.equals(profile)&&remember;}
    static synchronized void clear(){value=null;owner="";remember=false;expires=0;}
}
