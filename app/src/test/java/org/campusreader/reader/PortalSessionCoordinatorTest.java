package org.campusreader.reader;

import android.app.Application;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class PortalSessionCoordinatorTest {
    private Context context;private final String profile="campus:"+new String(new char[64]).replace("\0","a");
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("portal-recovery",0).edit().clear().commit();context.getSharedPreferences("reader-settings",0).edit().clear().commit();PortalSessionCoordinator.finished(profile);}
    @After public void cleanup(){PortalSessionCoordinator.finished(profile);}
    @Test public void conveniencesDefaultOnAndExplicitOffIsRespected(){ReaderPreferences p=new ReaderPreferences(context);assertTrue(p.isRefreshOnOpen());assertTrue(p.isPortalAutoRestoreEnabled());p.setRefreshOnOpen(false);p.setPortalAutoRestoreEnabled(false);assertFalse(new ReaderPreferences(context).isRefreshOnOpen());assertFalse(PortalSessionCoordinator.shouldAttempt(context,profile));}
    @Test public void oneForegroundRecoveryAtATimeAndRepeatedResumeCannotRetry(){assertTrue(PortalSessionCoordinator.shouldAttempt(context,profile));assertTrue(PortalSessionCoordinator.acquire(context,profile,true));assertFalse(PortalSessionCoordinator.acquire(context,profile,false));PortalSessionCoordinator.finished(profile);assertFalse(PortalSessionCoordinator.shouldAttempt(context,profile));}
    @Test public void failedCredentialPausesAutomaticButExplicitRecoveryRemainsPossible(){PortalSessionCoordinator.pause(context,profile);assertFalse(PortalSessionCoordinator.shouldAttempt(context,profile));assertTrue(PortalSessionCoordinator.acquire(context,profile,false));PortalSessionCoordinator.success(context,profile);PortalSessionCoordinator.finished(profile);assertFalse(PortalSessionCoordinator.shouldAttempt(context,profile));}
    @Test public void localOrMalformedProfilesCannotAutomaticallyRestore(){assertFalse(PortalSessionCoordinator.shouldAttempt(context,"local"));assertFalse(PortalSessionCoordinator.shouldAttempt(context,null));assertFalse(PortalSessionCoordinator.shouldAttempt(context,"campus:unknown"));}
}
