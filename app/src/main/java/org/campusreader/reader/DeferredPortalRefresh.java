package org.campusreader.reader;

/** Main-thread single-slot request. A portal login refresh survives an in-flight request. */
final class DeferredPortalRefresh {
    private String requestedProfile;
    void request(String profile) { requestedProfile=profile; }
    boolean hasRequest() { return requestedProfile!=null; }
    boolean takeIfReady(String currentProfile,boolean busy,boolean clearingCookies,boolean accessReady) {
        if(requestedProfile==null)return false;
        if(!requestedProfile.equals(currentProfile)){requestedProfile=null;return false;}
        if(busy||clearingCookies||!accessReady)return false;
        requestedProfile=null;return true;
    }
}
