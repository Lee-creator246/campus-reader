package org.campusreader.reader;

/** Ordering of suppression reasons, independent of platform and notification side effects. */
final class NotificationDecision {
    private NotificationDecision(){}
    static NotificationHistory.Reason suppressed(boolean sameProfile,boolean exists,boolean read,boolean blocked,boolean appEnabled,boolean permission,boolean systemEnabled,boolean channelEnabled){
        if(!sameProfile)return NotificationHistory.Reason.ACCOUNT_CHANGED;
        if(!exists)return NotificationHistory.Reason.MISSING;
        if(blocked)return NotificationHistory.Reason.BLOCKED;
        if(read)return NotificationHistory.Reason.ALREADY_READ;
        if(!appEnabled)return NotificationHistory.Reason.APP_DISABLED;
        if(!permission)return NotificationHistory.Reason.PERMISSION_DENIED;
        if(!systemEnabled)return NotificationHistory.Reason.SYSTEM_DISABLED;
        if(!channelEnabled)return NotificationHistory.Reason.CHANNEL_DISABLED;
        return null;
    }
}
