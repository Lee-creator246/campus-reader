package org.campusreader.auth;

/** Presentation cannot turn a stale portal flag into actual campus readiness. */
public final class ConnectionStatusChecks {
    private static int checked;
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checked++;}
    private static CampusConnectionStatus status(boolean configured,boolean restart,String state,CampusConnectionStatus.PortalState portal){
        return CampusConnectionStatus.describe(configured,restart,state,portal);
    }
    public static void main(String[] args){
        for(CampusConnectionStatus.PortalState portal:CampusConnectionStatus.PortalState.values()){
            check(status(false,false,"ACCESS_READY",portal).action==CampusConnectionStatus.Action.CONFIGURE,"Missing config must override readiness");
            check(status(true,true,"ACCESS_READY",portal).action==CampusConnectionStatus.Action.RESTART,"Restart requirement must override readiness");
            check(status(false,true,"IDLE",portal).action==CampusConnectionStatus.Action.RESTART,"Pending restart must remain visible before config");
            String[] offline={"IDLE","FAILED","DISCONNECTED","invalid", ""};
            for(String state:offline){
                CampusConnectionStatus snapshot=status(true,false,state,portal);
                check(snapshot.action==CampusConnectionStatus.Action.CONNECT,"Offline connection needs campus recovery");
                check(!snapshot.title.contains("门户已验证"),"Offline status must not show stale portal verification");
            }
            String[] busy={"RESTORING","AUTHENTICATING","STARTING_TUNNEL"};
            for(String state:busy)check(status(true,false,state,portal).action==CampusConnectionStatus.Action.WAIT,"Pending SDK work must not start portal operations");
            check(status(true,false,"CHALLENGE_REQUIRED",portal).action==CampusConnectionStatus.Action.CONNECT,"Challenge must expose auth continuation");
        }
        check(status(true,false,"ACCESS_READY",CampusConnectionStatus.PortalState.UNKNOWN).action==CampusConnectionStatus.Action.CHECK_PORTAL,"Ready tunnel alone must not imply portal login");
        check(status(true,false,"ACCESS_READY",CampusConnectionStatus.PortalState.LOGIN_REQUIRED).action==CampusConnectionStatus.Action.LOGIN_PORTAL,"Portal expiry must request only portal login");
        check(status(true,false,"ACCESS_READY",CampusConnectionStatus.PortalState.CHECKING).action==CampusConnectionStatus.Action.WAIT,"In-flight check must not duplicate login");
        check(status(true,false,"ACCESS_READY",CampusConnectionStatus.PortalState.WAITING).action==CampusConnectionStatus.Action.WAIT,"Cookie clearing/deferred refresh must wait");
        check(status(true,false,"ACCESS_READY",CampusConnectionStatus.PortalState.VERIFIED).action==CampusConnectionStatus.Action.REFRESH,"Verified response enables refresh");
        check(status(true,false,null,null).action==CampusConnectionStatus.Action.CONNECT,"Unknown state must fail closed");
        check(status(true,false,"ACCESS_READY",null).action==CampusConnectionStatus.Action.CHECK_PORTAL,"Missing portal evidence must be unknown");
        System.out.println("Connection status checks passed: "+checked);
    }
}
