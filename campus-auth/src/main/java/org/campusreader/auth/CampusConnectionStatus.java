package org.campusreader.auth;

/** Pure presentation policy. A portal is verified only by a successful authenticated response. */
public final class CampusConnectionStatus {
    public enum PortalState { UNKNOWN, CHECKING, WAITING, LOGIN_REQUIRED, VERIFIED }
    public enum Action { CONFIGURE, RESTART, CONNECT, WAIT, CHECK_PORTAL, LOGIN_PORTAL, REFRESH }
    public final String title, detail, actionLabel;
    public final Action action;

    private CampusConnectionStatus(String title, String detail, Action action, String actionLabel) {
        this.title = title; this.detail = detail; this.action = action; this.actionLabel = actionLabel;
    }

    public static CampusConnectionStatus describe(boolean configured, boolean restartRequired,
                                                   String campusState, PortalState portal) {
        if (restartRequired) return status("配置等待生效", "关闭应用后从桌面重新打开；缓存和阅读状态保留。", Action.RESTART, "关闭并重新打开");
        if (!configured) return status("尚未配置校园访问", "导入或填写访问入口，同意连接服务说明后再连接。", Action.CONFIGURE, "校园配置");
        String state = campusState == null ? "" : campusState;
        switch (state) {
            case "RESTORING": return status("正在恢复校园连接", "先检查已有校园认证，通道就绪后再检查门户；可继续阅读缓存。", Action.WAIT, "正在恢复…");
            case "AUTHENTICATING": return status("正在认证校园账号", "校园认证尚未完成，门户登录状态仍待检查。", Action.WAIT, "正在认证…");
            case "CHALLENGE_REQUIRED": return status("校园认证需要继续验证", "返回校园连接完成验证；缓存仍可阅读。", Action.CONNECT, "继续校园验证");
            case "STARTING_TUNNEL": return status("正在建立校园通道", "认证结果不代表通道就绪，请等待实际连接完成。", Action.WAIT, "正在连接…");
            case "ACCESS_READY": break;
            case "FAILED": return status("校园连接未完成", "进入校园连接查看原因或重新认证；缓存和阅读状态保留。", Action.CONNECT, "检查校园连接");
            case "DISCONNECTED": return status("校园通道暂不可用", "可检查校园连接；恢复后重新验证门户，缓存仍可阅读。", Action.CONNECT, "检查校园连接");
            default: return status("校园未连接 · 门户待检查", "先建立校园访问通道，再单独检查或登录信息门户。", Action.CONNECT, "连接校园网");
        }
        if (portal == null) portal = PortalState.UNKNOWN;
        switch (portal) {
            case CHECKING: return status("校园已连接 · 门户检查中", "正在通过实际通知请求验证门户会话。", Action.WAIT, "正在检查门户…");
            case WAITING: return status("校园已连接 · 门户等待检查", "正在等待当前刷新或登录状态清理完成。", Action.WAIT, "等待门户检查…");
            case LOGIN_REQUIRED: return status("校园已连接 · 门户需登录", "校园通道有效，请单独登录信息门户；已有缓存保留。", Action.LOGIN_PORTAL, "登录信息门户");
            case VERIFIED: return status("校园已连接 · 门户已验证", "最近一次通知请求已验证门户会话；后续刷新仍会检查。", Action.REFRESH, "刷新通知");
            default: return status("校园已连接 · 门户待检查", "校园连接已建立，门户登录是否有效需要实际请求确认。", Action.CHECK_PORTAL, "检查 / 登录信息门户");
        }
    }
    private static CampusConnectionStatus status(String title, String detail, Action action, String label) {
        return new CampusConnectionStatus(title, detail, action, label);
    }
}
