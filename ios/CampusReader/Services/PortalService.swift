import Foundation
import Combine
import CryptoKit
import CoreFoundation
import WebKit
import CampusReaderCore

struct PortalCredential: Codable {
    let account: String
    let password: String
    let binding: String
}

/// Cookies never use HTTPCookieStorage.shared. This jar is scoped to one account or probe.
struct PortalCookieJar {
    private(set) var cookies: [HTTPCookie] = []
    var received: [HTTPCookie] = []
    mutating func receive(_ response: HTTPURLResponse) {
        guard let url = response.url, PortalConfiguration.portal(url) else { return }
        let fields = response.allHeaderFields.reduce(into: [String: String]()) { result, item in
            if let key = item.key as? String { result[key] = String(describing: item.value) }
        }
        for cookie in HTTPCookie.cookies(withResponseHeaderFields: fields, for: url) where Self.allowed(cookie) { set(cookie); received.append(cookie) }
    }
    mutating func set(_ cookie: HTTPCookie) {
        guard Self.allowed(cookie) else { return }
        cookies.removeAll { $0.name == cookie.name && $0.domain == cookie.domain && $0.path == cookie.path }
        if cookie.expiresDate.map({ $0 <= Date() }) != true { cookies.append(cookie) }
        if cookies.count > 128 { cookies.removeFirst(cookies.count - 128) }
    }
    private static func allowed(_ cookie: HTTPCookie) -> Bool {
        let domain = cookie.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return ["my.bupt.edu.cn", "bupt.edu.cn"].contains(domain) && cookie.value.utf8.count < 8192
    }
    func header(for url: URL) -> String? {
        guard PortalConfiguration.portal(url), let host = url.host else { return nil }
        let matching = cookies.filter { cookie in
            let domain = cookie.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
            let path = url.path.isEmpty ? "/" : url.path
            let pathMatch = path == cookie.path || (path.hasPrefix(cookie.path) && (cookie.path.hasSuffix("/") || path.dropFirst(cookie.path.count).hasPrefix("/")))
            return (host == domain || host.hasSuffix("." + domain)) && pathMatch &&
                (!cookie.isSecure || url.scheme == "https") && cookie.expiresDate.map { $0 > Date() } != false
        }
        guard !matching.isEmpty else { return nil }
        let value = HTTPCookie.requestHeaderFields(with: matching)["Cookie"]
        return value.flatMap { $0.utf8.count <= 16384 ? $0 : nil }
    }
}

struct PortalHTTPResponse {
    let response: HTTPURLResponse
    let data: Data
    var text: String {
        if let utf8 = String(data: data, encoding: .utf8) { return utf8 }
        let gb = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        return String(data: data, encoding: gb) ?? ""
    }
}

protocol PortalTransport {
    func get(_ request: URLRequest, maxBytes: Int, allowPrefix: Bool) async throws -> PortalHTTPResponse
}

final class PortalHTTPClient: NSObject, PortalTransport, URLSessionTaskDelegate {
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()
    func get(_ request: URLRequest, maxBytes: Int, allowPrefix: Bool) async throws -> PortalHTTPResponse {
        let deadline = Date().addingTimeInterval(request.timeoutInterval)
        let (bytes, response) = try await session.bytes(for: request)
        defer { bytes.task.cancel() }
        guard let http = response as? HTTPURLResponse else { throw PortalError.invalidResponse("门户响应不是 HTTP 页面") }
        if !allowPrefix, http.expectedContentLength > Int64(maxBytes) { throw PortalError.invalidResponse("文件超过大小限制") }
        var data = Data()
        for try await byte in bytes {
            try Task.checkCancellation()
            if data.count & 4095 == 0, Date() >= deadline { throw URLError(.timedOut) }
            if data.count >= maxBytes {
                if allowPrefix { break }
                throw PortalError.invalidResponse("文件超过大小限制")
            }
            data.append(byte)
        }
        return PortalHTTPResponse(response: http, data: data)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil) // Every hop is independently checked by PortalService.
    }
}

@MainActor
final class PortalService: ObservableObject {
    @Published private(set) var activeAccount = ""
    @Published private(set) var sessionState: PortalSessionState = .signedOut
    @Published private(set) var storageMessage = ""
    /// Merely selecting a username must not replace the root login view with cached-reader UI.
    private(set) var canReadCachedAccount = false
    var profileID: String {
        guard hasAccount else { return "" }
        return SHA256.hash(data: Data(("bupt.edu.cn\u{0}" + activeAccount).utf8)).map { String(format: "%02x", $0) }.joined()
    }
    var hasAccount: Bool { !activeAccount.isEmpty }
    private(set) var generation: UInt64 = 0
    // A new WebKit store is allocated on account changes. In-flight writes can only
    // touch the discarded store; persistence is explicit and account-bound in Keychain.
    private(set) var websiteDataStore = WKWebsiteDataStore.nonPersistent()
    private let transport: PortalTransport
    private var jar = PortalCookieJar()
    private var restored = false
    private var switching = false
    private var verifying = false
    private let sessionKey = "portal-session-v1"
    init(transport: PortalTransport = PortalHTTPClient()) { self.transport = transport }

    func restore() async {
        guard !restored, !hasAccount else { return }
        restored = true
        guard !UserDefaults.standard.bool(forKey: "portal-logout-pending") else { return }
        do {
            let data = try KeychainStore.read(sessionKey)
            storageMessage = ""
            guard let data else { return }
            let saved = try JSONDecoder().decode(SavedSession.self, from: data)
            guard validAccount(saved.account), saved.cookies.count <= 128 else { throw PortalError.invalidResponse("本机会话格式已变化") }
            try await selectAccount(saved.account)
            let owner = generation
            for cookie in saved.cookies.compactMap({ $0.cookie }) {
                guard owner == generation else { return }
                jar.set(cookie)
                await websiteDataStore.httpCookieStore.setCookie(cookie)
            }
            guard owner == generation else { return }
            canReadCachedAccount = true
            sessionState = .loginRequired // A saved cookie is not authentication proof.
        } catch let failure as KeychainStore.Failure {
            storageMessage = failure.localizedDescription
            restored = false
        } catch {
            storageMessage = "保存的门户会话暂时无法恢复，请重新登录。"
        }
    }

    func selectAccount(_ input: String) async throws {
        let account = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard validAccount(account), !switching else { throw PortalError.invalidResponse("请等待账号检查完成，或检查学工号") }
        guard account != activeAccount else { return }
        switching = true
        defer { switching = false }
        generation &+= 1
        let owner = generation
        canReadCachedAccount = false
        jar = PortalCookieJar()
        let previousWebStore = websiteDataStore
        websiteDataStore = WKWebsiteDataStore.nonPersistent()
        await clearWebData(previousWebStore)
        guard owner == generation else { throw PortalError.cancelled }
        activeAccount = account
        sessionState = .loginRequired
    }

    func probe() async -> PortalRoute {
        // Never import the actual WebView cookies into this anonymous jar.
        var anonymous = PortalCookieJar()
        do {
            let response = try await request(PortalConfiguration.entry, jar: &anonymous, maxBytes: 32768, expectedGeneration: generation, probe: true)
            guard let url = response.response.url else { return .rejected("门户响应缺少地址") }
            if [401,403].contains(response.response.statusCode) { return .reachable }
            if response.response.statusCode >= 300 && response.response.statusCode < 400,
               let location = response.response.value(forHTTPHeaderField: "Location"),
               let next = URL(string: location, relativeTo: url)?.absoluteURL, PortalConfiguration.loginPage(next) { return .reachable }
            guard response.response.statusCode == 200 else { return .rejected("门户服务暂不可用，请稍后重试") }
            let html = response.text
            if ["wbnewsid", "wbtreeid", "authserver/login", "casLoginForm", "loginForm"].contains(where: html.contains) { return .reachable }
            return .rejected("收到未识别页面，请确认已完成校园 Wi-Fi 认证")
        } catch let error as URLError {
            if [.notConnectedToInternet,.cannotFindHost,.cannotConnectToHost,.dnsLookupFailed,.timedOut,.networkConnectionLost].contains(error.code) { return .unavailable }
            return .rejected("连接验证失败；请检查网络或证书，不会跳过安全校验")
        } catch { return .rejected("门户连接检查未完成，请重试") }
    }

    func validateSession() async throws -> NoticePage {
        let owner = generation
        sessionState = .checking
        do {
            let page = try await fetchList()
            guard owner == generation else { throw PortalError.cancelled }
            sessionState = .authenticated
            return page
        } catch {
            guard owner == generation else { throw PortalError.cancelled }
            if case PortalError.authenticationRequired = error { sessionState = .loginRequired }
            else { sessionState = .unavailable("门户验证未完成，已保留本机缓存") }
            throw error
        }
    }
    func fetchList(_ next: MoreRequest? = nil) async throws -> NoticePage {
        let owner = generation
        do {
            if let next { try PortalParser.validateMoreRequest(next) }
            let result = try await authenticatedRequest(next?.url ?? PortalConfiguration.notices, maxBytes: 5 * 1024 * 1024)
            guard let url = result.response.url else { throw PortalError.invalidResponse("门户响应缺少地址") }
            let page: NoticePage
            if let next { page = try PortalParser.parseMore(url: url, status: result.response.statusCode, xml: result.text, request: next) }
            else { page = try PortalParser.parseList(url: url, status: result.response.statusCode, html: result.text) }
            sessionState = .authenticated
            canReadCachedAccount = true
            saveSession()
            return page
        } catch { recordFailure(error, owner: owner); throw error }
    }
    func fetchDetail(_ notice: Notice) async throws -> Notice {
        let result = try await authenticatedRequest(notice.originalURL, maxBytes: 5 * 1024 * 1024)
        guard let url = result.response.url else { throw PortalError.invalidResponse("门户响应缺少地址") }
        let detail = try PortalParser.parseDetail(url: url, status: result.response.statusCode, html: result.text)
        guard detail.id == notice.id else { throw PortalError.invalidResponse("正文编号与列表不一致") }
        return detail
    }
    func resource(_ url: URL, maxBytes: Int = 20 * 1024 * 1024) async throws -> Data {
        let result = try await authenticatedRequest(url, maxBytes: min(max(maxBytes, 1), 25 * 1024 * 1024))
        guard result.response.statusCode == 200 else { throw PortalError.invalidResponse("图片或附件下载失败") }
        if (result.response.mimeType ?? "").contains("text/html") {
            let text = result.text.lowercased()
            if text.contains("type=\"password\"") || text.contains("authserver/login") { throw PortalError.authenticationRequired }
        }
        return result.data
    }
    private func authenticatedRequest(_ url: URL, maxBytes: Int) async throws -> PortalHTTPResponse {
        guard hasAccount, !switching else { throw PortalError.authenticationRequired }
        let owner = generation
        var cookies = jar
        cookies.received = []
        let result: PortalHTTPResponse
        do { result = try await request(url, jar: &cookies, maxBytes: maxBytes, expectedGeneration: owner, probe: false) }
        catch { recordFailure(error, owner: owner); throw error }
        guard owner == generation else { throw PortalError.cancelled }
        return result
    }
    private func recordFailure(_ error: Error, owner: UInt64) {
        guard owner == generation else { return }
        if case PortalError.authenticationRequired = error { sessionState = .loginRequired }
        else if let network = error as? URLError, network.code != .cancelled { sessionState = .unavailable("当前网络无法完成门户请求，缓存已保留") }
    }
    private func request(_ initial: URL, jar: inout PortalCookieJar, maxBytes: Int, expectedGeneration: UInt64, probe: Bool) async throws -> PortalHTTPResponse {
        var current = initial
        let deadline = Date().addingTimeInterval(probe ? 15 : 35)
        for _ in 0..<6 {
            try Task.checkCancellation()
            guard expectedGeneration == generation else { throw PortalError.cancelled }
            guard PortalConfiguration.portal(current) else { throw PortalError.authenticationRequired }
            guard deadline.timeIntervalSinceNow > 0 else { throw URLError(.timedOut) }
            var request = URLRequest(url: current, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: min(12, deadline.timeIntervalSinceNow))
            request.httpShouldHandleCookies = false
            request.setValue(PortalConfiguration.userAgent, forHTTPHeaderField: "User-Agent")
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            request.setValue(jar.header(for: current), forHTTPHeaderField: "Cookie")
            let result = try await transport.get(request, maxBytes: maxBytes, allowPrefix: probe)
            try Task.checkCancellation()
            guard expectedGeneration == generation else { throw PortalError.cancelled }
            guard result.response.url == current else { throw PortalError.invalidResponse("响应地址与当前请求不一致") }
            jar.receive(result.response)
            if !probe {
                // Keep response cookies even when the next hop requires interactive SSO.
                // The callback may depend on the session created by this first portal request.
                var updates = PortalCookieJar(); updates.receive(result.response)
                for cookie in updates.received { self.jar.set(cookie) }
                await copyCookiesToWeb(updates.received, owner: expectedGeneration)
                guard expectedGeneration == generation else { throw PortalError.cancelled }
            }
            let code = result.response.statusCode
            if [301,302,303,307,308].contains(code) {
                guard let location = result.response.value(forHTTPHeaderField: "Location"), let next = URL(string: location, relativeTo: current)?.absoluteURL else { throw PortalError.invalidResponse("门户返回不完整跳转") }
                if PortalConfiguration.loginPage(next) {
                    if probe { return result }
                    throw PortalError.authenticationRequired
                }
                guard PortalConfiguration.portal(next), !(current.scheme == "https" && next.scheme != "https") else { throw PortalError.invalidResponse("已拒绝未知认证跳转") }
                current = next
                continue
            }
            if !probe && (code == 401 || current.path.contains("clogin")) { throw PortalError.authenticationRequired }
            return result
        }
        throw PortalError.invalidResponse("门户跳转次数过多")
    }

    func importWebCookies(owner: UInt64) async throws {
        let cookies = await websiteDataStore.httpCookieStore.allCookies()
        guard owner == generation, !switching else { throw PortalError.cancelled }
        // Replace to reflect explicit expiry/deletion performed by the school.
        var updated = PortalCookieJar()
        cookies.forEach { updated.set($0) }
        jar = updated
    }
    private func copyCookiesToWeb(_ updates: [HTTPCookie], owner: UInt64) async {
        for cookie in updates {
            guard owner == generation, !switching else { return }
            if cookie.expiresDate.map({ $0 <= Date() }) == true { await websiteDataStore.httpCookieStore.delete(cookie) }
            else { await websiteDataStore.httpCookieStore.setCookie(cookie) }
        }
    }
    func savedCredential() throws -> PortalCredential? {
        guard !UserDefaults.standard.bool(forKey: "portal-logout-pending") else { return nil }
        guard hasAccount, let data = try KeychainStore.read("portal-password:" + profileID) else { return nil }
        let saved = try JSONDecoder().decode(PortalCredential.self, from: data)
        guard saved.account == activeAccount, saved.binding == "https://auth.bupt.edu.cn/authserver/login" else { return nil }
        return saved
    }
    var automaticRestoreAllowed: Bool {
        guard hasAccount else { return false }
        let defaults = UserDefaults.standard
        return !defaults.bool(forKey: "portal-auto-paused:" + profileID) && defaults.double(forKey: "portal-auto-next:" + profileID) <= Date().timeIntervalSince1970
    }
    func noteAutomaticAttempt() {
        guard hasAccount else { return }
        UserDefaults.standard.set(Date().addingTimeInterval(300).timeIntervalSince1970, forKey: "portal-auto-next:" + profileID)
    }
    func pauseAutomaticRestore() {
        guard hasAccount else { return }
        UserDefaults.standard.set(true, forKey: "portal-auto-paused:" + profileID)
    }
    func completeLogin(credential: PortalCredential?, remember: Bool, owner: UInt64) async throws {
        guard !verifying, owner == generation else { throw PortalError.cancelled }
        verifying = true
        defer { verifying = false }
        try await importWebCookies(owner: owner)
        _ = try await validateSession()
        guard owner == generation else { throw PortalError.cancelled }
        UserDefaults.standard.removeObject(forKey: "portal-auto-paused:" + profileID)
        UserDefaults.standard.removeObject(forKey: "portal-auto-next:" + profileID)
        if let credential, credential.account == activeAccount {
            do {
                if remember { try KeychainStore.write(JSONEncoder().encode(credential), key: "portal-password:" + profileID) }
                else { try forgetSavedPassword() }
            } catch { storageMessage = "登录成功，但系统未能保存密码。下次可能需要重新输入。" }
        } else {
            // A manually corrected password cannot authorize retaining an earlier failed secret.
            do { try forgetSavedPassword() }
            catch { pauseAutomaticRestore(); storageMessage = "网页登录成功；旧密码清理未完成，自动登录已暂停。" }
        }
    }
    func forgetSavedPassword() throws { try KeychainStore.delete("portal-password:" + profileID) }
    func logout() async {
        // A failed/locked keychain deletion must never silently restore a logged-out session.
        UserDefaults.standard.set(true, forKey: "portal-logout-pending")
        generation &+= 1
        canReadCachedAccount = false
        jar = PortalCookieJar()
        do { try forgetSavedPassword() }
        catch { storageMessage = "系统密码清理未完成，请解锁后重试。" }
        do { try KeychainStore.delete(sessionKey) }
        catch { storageMessage = "系统登录记录清理未完成，请解锁后重试。" }
        activeAccount = ""
        sessionState = .signedOut
        let previousWebStore = websiteDataStore
        websiteDataStore = WKWebsiteDataStore.nonPersistent()
        await clearWebData(previousWebStore)
    }
    private func clearWebData(_ store: WKWebsiteDataStore) async {
        await store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
    }
    private func validAccount(_ input: String) -> Bool {
        !input.isEmpty && input.count <= 128 && !input.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
    }
    private func saveSession() {
        let saved = SavedSession(account: activeAccount, cookies: jar.cookies.map(SavedCookie.init))
        do {
            try KeychainStore.write(JSONEncoder().encode(saved), key: sessionKey)
            UserDefaults.standard.removeObject(forKey: "portal-logout-pending")
            storageMessage = ""
        } catch {
            storageMessage = "门户可访问，本次会话未保存。\(error.localizedDescription)"
        }
    }
    private struct SavedSession: Codable { let account: String; let cookies: [SavedCookie] }
    struct SavedCookie: Codable {
        let name: String, value: String, domain: String, path: String
        let secure: Bool, httpOnly: Bool
        let expires: Date?
        init(_ cookie: HTTPCookie) { name = cookie.name; value = cookie.value; domain = cookie.domain; path = cookie.path; secure = cookie.isSecure; httpOnly = cookie.isHTTPOnly; expires = cookie.expiresDate }
        var cookie: HTTPCookie? {
            var properties: [HTTPCookiePropertyKey: Any] = [.name:name,.value:value,.domain:domain,.path:path]
            if secure { properties[.secure] = "TRUE" }
            if httpOnly { properties[HTTPCookiePropertyKey("HttpOnly")] = "TRUE" }
            if let expires { properties[.expires] = expires }
            return HTTPCookie(properties: properties)
        }
    }
}
