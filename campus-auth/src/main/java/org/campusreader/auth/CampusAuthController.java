package org.campusreader.auth;

import org.campusreader.config.CampusSite;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.sangfor.sdk.SFUemSDK;
import com.sangfor.sdk.base.SFAuthResultListener;
import com.sangfor.sdk.base.SFAuthStatus;
import com.sangfor.sdk.base.SFAuthType;
import com.sangfor.sdk.base.SFBaseMessage;
import com.sangfor.sdk.base.SFConstants;
import com.sangfor.sdk.base.SFLogoutListener;
import com.sangfor.sdk.base.SFLogoutType;
import com.sangfor.sdk.base.SFTunnelStatus;
import com.sangfor.sdk.base.SFTunnelStatusListener;
import java.util.HashMap;
import java.util.Map;

/** One app-scoped SDK owner. No password/code persistence and no simulated connection success. */
public final class CampusAuthController implements SFAuthResultListener, SFTunnelStatusListener, SFLogoutListener {
    public enum State { IDLE, RESTORING, AUTHENTICATING, CHALLENGE_REQUIRED, STARTING_TUNNEL, ACCESS_READY, DISCONNECTED, FAILED }
    public interface Listener {
        void onState(State state, long errorCode, String message);
        void onChallenge(SFAuthType type, SFBaseMessage message);
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SFUemSDK sdk; // Never touch the vendor singleton before stored consent.
    private String accountScope;
    private volatile State state = State.IDLE;
    private volatile String profileId;
    private String pendingPassword;
    private String sdkUsername;
    private SFAuthType pendingType;
    private SFBaseMessage pendingMessage;
    private Listener listener;
    private volatile boolean portalAuthenticationRequired;
    private long errorCode;
    private boolean restoringSession;
    private boolean userStopped;
    // Deliberately process-local: a persisted "connected" flag is not tunnel evidence.
    private boolean successfulTunnelStart;
    private volatile String diagnostics = "controller=IDLE sdkAuth=UNKNOWN sdkTunnel=UNKNOWN";
    private String lastLoggedDiagnostics = "";
    private String lastLoggedEvent = "";
    private final Runnable delayedSync = () -> synchronizeSdkState();
    private final Runnable restoreDeadline = () -> {
        if (!restoringSession || (state != State.RESTORING && state != State.STARTING_TUNNEL)) return;
        if (synchronizeSdkState()) return;
        clearSecrets();
        change(State.FAILED, -1, "已有认证恢复超时，请重新输入动态密码；缓存继续保留");
        // Do not tear down an asynchronously recovering SDK tunnel on a UI timeout.
        // A late real start-result may still reconcile it; explicit cancel/disconnect stops it.
    };
    private String stateMessage = "尚未连接校园网";

    CampusAuthController(Context context) {
        this.context = context.getApplicationContext();
        onConfigurationInstalled();
    }

    void onConfigurationInstalled() {
        requireMain();
        if (sdk != null) throw new IllegalStateException("修改校园配置后需要关闭应用");
        accountScope = CampusSite.scopeId();
        String storedProfile = accountPreferences().getString("profile_id", "");
        try { profileId = CampusAccount.profileId(storedProfile); }
        catch (IllegalArgumentException invalidOldProfile) { profileId = ""; }
        userStopped = accountPreferences().getBoolean("user_stopped", false);
        successfulTunnelStart = false;
        portalAuthenticationRequired = false;
        change(State.IDLE, 0, "尚未连接校园网");
    }

    private SharedPreferences accountPreferences() {
        return CampusConfiguration.accountPreferences(context, accountScope);
    }
    private boolean connectionAllowed() {
        return CampusConfiguration.isConfigured() && accountScope.equals(CampusSite.scopeId());
    }
    private void ensureSdkReady() {
        CampusConfiguration.requireConnectionAllowed();
        if (!accountScope.equals(CampusSite.scopeId())) throw new IllegalStateException("校园配置已更改，请重新打开应用");
        if (sdk != null) return;
        CampusSdk.initialize(context);
        sdk = SFUemSDK.getInstance();
        sdk.setAuthResultListener(this);
        sdk.getSFTunnel().registerTunnelStatusListener(this);
        sdk.registerLogoutListener(this);
    }

    public State getState() { return state; }
    public boolean isAccessReady() { return connectionAllowed() && state == State.ACCESS_READY; }
    public boolean isSdkInitialized() { return CampusSdk.wasInitializationAttempted(); }
    public boolean isRestartRequired() { return CampusConfiguration.requiresRestart(); }
    public String getProfileId() { return profileId; }
    public boolean isPortalAuthenticationRequired() { return portalAuthenticationRequired; }
    /** Sanitized enum/boolean/numeric diagnostics only; contains no account, ticket or password. */
    public String getDiagnostics() { return diagnostics; }

    /** Recheck SDK state on foreground/explicit refresh. Does not start auth or trust service existence. */
    public boolean synchronizeSdkState() {
        requireMain();
        if (!connectionAllowed() || sdk == null) return false;
        try {
            SFAuthStatus auth = sdk.getAuthStatus();
            SFTunnelStatus tunnel = sdk.getSFTunnel().getTunnelStatus();
            boolean ready = !userStopped && successfulTunnelStart && !profileId.isEmpty()
                    && auth == SFAuthStatus.AuthStatusAuthOk && tunnel == SFTunnelStatus.ONLINE;
            if (ready && state != State.ACCESS_READY) {
                clearSecrets();
                change(State.ACCESS_READY, 0, "校园通道已恢复；信息门户登录状态单独检查");
            } else if (!ready && state == State.ACCESS_READY) {
                change(State.DISCONNECTED, 0, "校园通道暂不可用，正在等待SDK恢复；缓存继续保留");
            } else if (!userStopped && successfulTunnelStart && auth == SFAuthStatus.AuthStatusAuthOk
                    && tunnel != SFTunnelStatus.ONLINE && (state == State.DISCONNECTED || state == State.FAILED)
                    && !"认证仍有效，校园通道暂时离线，等待SDK重连；缓存继续保留".equals(stateMessage)) {
                change(State.DISCONNECTED, 0, "认证仍有效，校园通道暂时离线，等待SDK重连；缓存继续保留");
            }
            recordDiagnostics(auth, tunnel, 0);
            return ready;
        } catch (RuntimeException failure) {
            recordDiagnostics(null, null, -1);
            if (state == State.ACCESS_READY) change(State.DISCONNECTED, -1, "校园连接状态查询失败，请重试；缓存继续保留");
            return false;
        }
    }
    /** Portal SSO expiry is separate from SDK/VPN expiry. Never deletes notification data. */
    public void markPortalAuthenticationRequired() { requireMain(); portalAuthenticationRequired = true; }
    public void clearPortalAuthenticationRequired() { requireMain(); portalAuthenticationRequired = false; }

    public void setListener(Listener listener) {
        requireMain(); synchronizeSdkState(); this.listener = listener;
        if (listener != null) {
            listener.onState(state, errorCode, stateMessage);
            if (state == State.CHALLENGE_REQUIRED) listener.onChallenge(pendingType, pendingMessage);
        }
    }
    public void removeListener(Listener listener) { requireMain(); if (this.listener == listener) this.listener = null; }

    /**
     * Restores only using the SDK's own session mechanism. startAutoTicket(true) is NOT readiness:
     * its asynchronous recovery can still fail/logout, and L3 must return a successful start result.
     * Must be invoked in the main app process after SDK/listener initialization.
     */
    public void restoreSessionIfPossible() {
        requireMain();
        if (!connectionAllowed() || state != State.IDLE || userStopped || profileId.isEmpty()
                || !accountPreferences().getBoolean("restore_allowed", false)
                || !CampusConfiguration.ownsSdkTicket(context, accountScope)) return;
        try { ensureSdkReady(); } catch (RuntimeException failure) { failLocally(failure); return; }
        sdkUsername = CampusAccount.sdkUsername(profileId);
        restoringSession = true;
        change(State.RESTORING, 0, "正在检查已有校园认证");
        main.postDelayed(restoreDeadline, 30_000);
        try {
            boolean authenticated = sdk.getAuthStatus() == SFAuthStatus.AuthStatusAuthOk;
            if (!authenticated && !sdk.startAutoTicket()) {
                change(State.IDLE, 0, "已有认证无法恢复，请输入新的动态密码；缓存继续保留");
                return;
            }
            beginTunnel("正在恢复校园通道");
        } catch (RuntimeException e) { failLocally(e); }
    }

    public void login(String account, String dynamicPassword) {
        requireMain();
        CampusConfiguration.requireConnectionAllowed();
        if (state == State.RESTORING || state == State.AUTHENTICATING || state == State.CHALLENGE_REQUIRED || state == State.STARTING_TUNNEL)
            throw new IllegalStateException("认证尚未结束，请先取消");
        if (state == State.ACCESS_READY) throw new IllegalStateException("校园通道已建立，请先断开后切换账号");
        String normalizedAccount = CampusAccount.profileId(account);
        if (dynamicPassword == null || !dynamicPassword.matches("[0-9]{6}")) throw new IllegalArgumentException("请输入六位动态密码");
        ensureSdkReady();
        profileId = normalizedAccount;
        userStopped = false;
        successfulTunnelStart = false;
        accountPreferences().edit().putBoolean("user_stopped", false).putBoolean("restore_allowed", false).apply();
        sdkUsername = CampusAccount.sdkUsername(normalizedAccount);
        pendingPassword = dynamicPassword;
        clearChallenge();
        change(State.AUTHENTICATING, 0, "正在认证校园账号");
        try { sdk.startPasswordAuth(CampusSite.vpnUrl(), sdkUsername, dynamicPassword); }
        catch (RuntimeException e) { failLocally(e); }
    }

    /** Parameter keys and behavior match the distribution's SecondAuthActivity. */
    public void submitCode(String code) {
        requireMain();
        CampusConfiguration.requireConnectionAllowed();
        if (state != State.CHALLENGE_REQUIRED || pendingType == null) throw new IllegalStateException("没有待完成的认证");
        if (code == null || code.trim().isEmpty()) throw new IllegalArgumentException("验证码不能为空");
        Map<String,String> params = new HashMap<>();
        switch (pendingType) {
            case AUTH_TYPE_RAND:
                params.put(SFConstants.AUTH_KEY_USERNAME, sdkUsername);
                params.put(SFConstants.AUTH_KEY_PASSWORD, pendingPassword);
                params.put(SFConstants.AUTH_KEY_RANDCODE, code.trim()); break;
            case AUTH_TYPE_SMS: params.put(SFConstants.AUTH_KEY_SMS, code.trim()); break;
            case AUTH_TYPE_TOKEN:
            case AUTH_TYPE_TOKEN_TOTP:
            case AUTH_TYPE_TOKEN_HTTPS:
            case AUTH_TYPE_TOKEN_RADIUS: params.put(SFConstants.AUTH_KEY_TOKEN, code.trim()); break;
            case AUTH_TYPE_RADIUS: params.put(SFConstants.AUTH_KEY_RADIUS_CODE, code.trim()); break;
            default: throw new IllegalStateException("此认证需要专门界面：" + pendingType.name());
        }
        // Multi-choice must be handled explicitly, never silently choose a different server method.
        if (pendingMessage != null && pendingMessage.mNextServiceList != null && pendingMessage.mNextServiceList.size() > 1)
            throw new IllegalStateException("服务端提供多个认证方式，需要先适配选择界面");
        SFAuthType type = pendingType;
        change(State.AUTHENTICATING, 0, "正在提交验证");
        try { sdk.doSecondaryAuth(type, params); }
        catch (RuntimeException e) { failLocally(e); }
    }

    public void cancel() {
        requireMain();
        if (state != State.RESTORING && state != State.AUTHENTICATING
                && state != State.CHALLENGE_REQUIRED && state != State.STARTING_TUNNEL) return;
        userStopped = true;
        successfulTunnelStart = false;
        change(State.IDLE, 0, "认证已取消");
        clearSecrets();
        if (sdk == null) return;
        sdk.cancelAuth();
        sdk.getSFTunnel().stopTunnel();
    }
    public void disconnect() {
        requireMain();
        userStopped = true;
        successfulTunnelStart = false;
        accountPreferences().edit()
                .putBoolean("restore_allowed", false).putBoolean("user_stopped", true).apply();
        change(State.DISCONNECTED, 0, "已断开校园通道；缓存继续保留");
        clearSecrets();
        if (sdk == null) return;
        sdk.getSFTunnel().stopTunnel();
        sdk.logout();
    }

    /** Irreversible for this process once native initialization has started. */
    void stopForConfigurationChange() {
        requireMain();
        userStopped = true;
        successfulTunnelStart = false;
        portalAuthenticationRequired = true;
        profileId = "";
        clearSecrets();
        main.removeCallbacks(delayedSync);
        main.removeCallbacks(restoreDeadline);
        accountPreferences().edit().putBoolean("restore_allowed", false).putBoolean("user_stopped", true).commit();
        change(State.DISCONNECTED, 0, "校园配置正在更新；缓存继续保留");
        if (sdk == null) return;
        // A native callback may still arrive after logout. All callbacks check the process guard.
        try { sdk.cancelAuth(); } catch (RuntimeException ignored) { }
        try { sdk.getSFTunnel().stopTunnel(); } catch (RuntimeException ignored) { }
        try { sdk.logout(); } catch (RuntimeException ignored) { }
    }

    @Override public void onAuthSuccess(SFBaseMessage message) { main.post(() -> {
        if (!connectionAllowed()) return;
        logEvent("auth_success", message == null ? -1 : message.mErrCode);
        if (userStopped) return;
        if ((state == State.FAILED || state == State.DISCONNECTED) && !profileId.isEmpty()) {
            if (!synchronizeSdkState()) beginTunnel("认证已恢复，正在检查校园通道");
            return;
        }
        if (state != State.RESTORING && state != State.AUTHENTICATING && state != State.CHALLENGE_REQUIRED) return;
        clearSecrets();
        beginTunnel("认证通过，正在建立校园通道");
    }); }
    @Override public void onAuthFailed(SFBaseMessage message) { main.post(() -> {
        if (!connectionAllowed()) return;
        logEvent("auth_failed", message == null ? -1 : message.mErrCode);
        if (state != State.RESTORING && state != State.AUTHENTICATING && state != State.CHALLENGE_REQUIRED && state != State.STARTING_TUNNEL) return;
        clearSecrets();
        change(State.FAILED, message == null ? -1 : message.mErrCode,
                "认证失败，请检查账号和动态密码后重试");
    }); }
    @Override public void onAuthProgress(SFAuthType type, SFBaseMessage message) { main.post(() -> {
        if (!connectionAllowed()) return;
        logEvent("auth_progress", message == null ? -1 : message.mErrCode);
        if ((state == State.RESTORING || state == State.STARTING_TUNNEL) && pendingPassword == null) {
            clearSecrets();
            change(State.FAILED, 0, "已有认证需要额外验证，请重新输入动态密码；缓存继续保留");
            sdk.cancelAuth();
            return;
        }
        if (state != State.AUTHENTICATING && state != State.CHALLENGE_REQUIRED) return;
        pendingType = type;
        pendingMessage = message;
        change(State.CHALLENGE_REQUIRED, message == null ? 0 : message.mErrCode, "请完成学校要求的下一步验证");
        if (listener != null) listener.onChallenge(type, message);
    }); }
    @Override public void onTunnelStarted(SFBaseMessage message) { main.post(() -> {
        if (!connectionAllowed()) return;
        logEvent("tunnel_started", message == null ? -1 : message.mErrCode);
        if (userStopped) return;
        if (message == null || message.mErrCode != 0) {
            if (synchronizeSdkState()) return;
            if (state != State.STARTING_TUNNEL) return;
            change(State.FAILED, message == null ? -1 : message.mErrCode,
                    "校园通道启动失败，请检查网络后重试"); return;
        }
        successfulTunnelStart = true;
        CampusConfiguration.recordSdkTicket(context, accountScope);
        accountPreferences().edit()
                .putString("profile_id", profileId).putBoolean("restore_allowed", true).putBoolean("user_stopped", false).apply();
        synchronizeSdkState();
        scheduleStateRecheck();
    }); }
    @Override public void onTunnelStoped() { main.post(() -> {
        if (!connectionAllowed()) return;
        logEvent("tunnel_stopped", 0);
        if (!synchronizeSdkState()) {
            clearSecrets(); change(State.DISCONNECTED, 0, "校园通道已停止，等待连接恢复或重新认证；缓存继续保留");
        }
        scheduleStateRecheck();
    }); }
    @Override public void onTunnelStatusChanged(SFTunnelStatus status) { main.post(() -> {
        if (!connectionAllowed()) return;
        // Re-read both SDK values: queued OFFLINE events may be older than the latest ONLINE.
        logEvent(status == null ? "tunnel_status_unknown" : "tunnel_status_" + status.name(), 0);
        synchronizeSdkState();
        scheduleStateRecheck();
    }); }
    @Override public void onLogout(SFLogoutType type, SFBaseMessage message) { main.post(() -> {
        if (!connectionAllowed()) return;
        logEvent("logout", message == null ? -1 : message.mErrCode);
        // A terminal logout invalidates prior start evidence; a new session needs a new L3 start result.
        successfulTunnelStart = false;
        accountPreferences().edit().putBoolean("restore_allowed", false).apply();
        clearSecrets(); change(State.DISCONNECTED, message == null ? 0 : message.mErrCode,
                "校园认证已结束，请重新认证；缓存继续保留");
        scheduleStateRecheck();
    }); }

    private void change(State next, long code, String message) {
        if (next != State.RESTORING && next != State.STARTING_TUNNEL) {
            restoringSession = false;
            main.removeCallbacks(restoreDeadline);
        }
        state = next; errorCode = code; stateMessage = message == null ? "" : message;
        if (listener != null) listener.onState(next, code, stateMessage);
    }
    private void beginTunnel(String message) {
        if (!connectionAllowed() || userStopped || sdk == null) return;
        change(State.STARTING_TUNNEL, 0, message);
        try { sdk.getSFTunnel().startTunnel(); }
        catch (RuntimeException e) { failLocally(e); }
    }
    private void clearChallenge() { pendingType = null; pendingMessage = null; }
    private void scheduleStateRecheck() {
        main.removeCallbacks(delayedSync);
        main.postDelayed(delayedSync, 500);
    }
    private void recordDiagnostics(SFAuthStatus auth, SFTunnelStatus tunnel, long queryError) {
        diagnostics = "controller=" + state.name() + " sdkAuth=" + (auth == null ? "UNKNOWN" : auth.name())
                + " sdkTunnel=" + (tunnel == null ? "UNKNOWN" : tunnel.name())
                + " startConfirmed=" + successfulTunnelStart + " userStopped=" + userStopped + " queryError=" + queryError;
        if (!diagnostics.equals(lastLoggedDiagnostics)) {
            lastLoggedDiagnostics = diagnostics;
            Log.i("CampusAuthState", diagnostics);
        }
    }
    private void logEvent(String event, long code) {
        String value = "event=" + event + " controller=" + state.name() + " code=" + code;
        if (!value.equals(lastLoggedEvent)) { lastLoggedEvent = value; Log.i("CampusAuthState", value); }
    }
    private void clearSecrets() { pendingPassword = null; clearChallenge(); }
    private void failLocally(RuntimeException exception) { clearSecrets(); change(State.FAILED, -1, "SDK调用失败：" + exception.getClass().getSimpleName()); }
    private static void requireMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Call from main thread"); }
}
