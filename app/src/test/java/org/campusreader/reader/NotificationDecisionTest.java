package org.campusreader.reader;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationDecisionTest {
    @Test public void changedAccountAlwaysSuppresses(){assertEquals(NotificationHistory.Reason.ACCOUNT_CHANGED,NotificationDecision.suppressed(false,true,false,false,true,true,true,true));}
    @Test public void missingIsNotConfusedWithRead(){assertEquals(NotificationHistory.Reason.MISSING,NotificationDecision.suppressed(true,false,true,false,true,true,true,true));}
    @Test public void blockingWinsEvenForPriorityReadOrDisabledDelivery(){assertEquals(NotificationHistory.Reason.BLOCKED,NotificationDecision.suppressed(true,true,true,true,false,false,false,false));}
    @Test public void readItemsAreNotNotified(){assertEquals(NotificationHistory.Reason.ALREADY_READ,NotificationDecision.suppressed(true,true,true,false,true,true,true,true));}
    @Test public void appPermissionSystemAndChannelAreDistinct(){
        assertEquals(NotificationHistory.Reason.APP_DISABLED,NotificationDecision.suppressed(true,true,false,false,false,true,true,true));
        assertEquals(NotificationHistory.Reason.PERMISSION_DENIED,NotificationDecision.suppressed(true,true,false,false,true,false,true,true));
        assertEquals(NotificationHistory.Reason.SYSTEM_DISABLED,NotificationDecision.suppressed(true,true,false,false,true,true,false,true));
        assertEquals(NotificationHistory.Reason.CHANNEL_DISABLED,NotificationDecision.suppressed(true,true,false,false,true,true,true,false));
    }
    @Test public void eligibleIsNotYetSubmittedOrSeen(){assertNull(NotificationDecision.suppressed(true,true,false,false,true,true,true,true));}
}
