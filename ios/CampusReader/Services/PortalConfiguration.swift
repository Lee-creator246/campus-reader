import Foundation

enum PortalConfiguration {
    static let entry = URL(string: "http://my.bupt.edu.cn/xs_index.jsp?urltype=tree.TreeTempUrl&wbtreeid=1541")!
    static let notices = URL(string: "http://my.bupt.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1154")!
    static let login = URL(string: "https://auth.bupt.edu.cn/authserver/login?service=http%3A%2F%2Fmy.bupt.edu.cn%3A80%2Fsystem%2Fresource%2Fcode%2Fauth%2Fclogin.jsp%3Fowner%3D1664271694")!
    static let vpn = URL(string: "https://vpn.bupt.edu.cn")!
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 Version/16.0 Mobile/15E148 Safari/604.1"

    static func portal(_ url: URL) -> Bool {
        guard clean(url), url.host?.lowercased() == "my.bupt.edu.cn" else { return false }
        return (url.scheme == "http" && (url.port == nil || url.port == 80)) ||
            (url.scheme == "https" && (url.port == nil || url.port == 443))
    }
    static func authentication(_ url: URL) -> Bool {
        clean(url) && url.scheme == "https" && url.host?.lowercased() == "auth.bupt.edu.cn" && (url.port == nil || url.port == 443)
    }
    static func loginPage(_ url: URL) -> Bool { authentication(url) && url.path == "/authserver/login" && url.fragment == nil }
    static func web(_ url: URL) -> Bool { portal(url) || authentication(url) }
    static func clean(_ url: URL) -> Bool { url.user == nil && url.password == nil && url.host != nil && url.fragment == nil }
}

enum PortalRoute: Equatable {
    case reachable
    case unavailable
    case rejected(String)
}

enum PortalSessionState: Equatable {
    case signedOut, checking, loginRequired, authenticated
    case unavailable(String)
}
