package org.campusreader.reader;

import android.content.Context;

/** Reuses the reader's offline profile scope without requiring a live campus session. */
final class AiProfile {
    private AiProfile(){}
    static boolean owns(Context context,String profile){
        if(profile==null||!(profile.equals("local")||profile.matches("campus:[a-f0-9]{64}")))return false;
        String current=CampusSyncEngine.currentProfile();
        if(current==null||current.isEmpty())current=context.getSharedPreferences("reader-local",Context.MODE_PRIVATE).getString(ReaderPreferences.lastProfileKey(),"local");
        return profile.equals(current);
    }
}
