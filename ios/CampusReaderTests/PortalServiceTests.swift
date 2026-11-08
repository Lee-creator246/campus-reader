import XCTest
import WebKit
import CampusReaderCore
@testable import CampusReader

@MainActor
final class PortalServiceTests: XCTestCase {
    func testAnonymousProbeCarriesTemporaryCookieAcrossSameOriginCallback() async throws {
        let mock = MockPortalTransport()
        mock.handler = { request in
            if mock.requests.count == 1 {
                XCTAssertEqual(request.url, PortalConfiguration.entry)
                XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
                return Self.response(request, status: 302, headers: ["Location":"/system/resource/code/auth/clogin.jsp", "Set-Cookie":"JSESSIONID=synthetic-anonymous; Path=/; HttpOnly"])
            }
            XCTAssertEqual(mock.requests.count, 2)
            XCTAssertEqual(request.url?.path, "/system/resource/code/auth/clogin.jsp")
            XCTAssertTrue(request.value(forHTTPHeaderField: "Cookie")?.contains("JSESSIONID=synthetic-anonymous") == true)
            return Self.response(request, status: 302, headers: ["Location":PortalConfiguration.login.absoluteString])
        }
        let service = PortalService(transport: mock)
        let route = await service.probe()
        XCTAssertEqual(route, .reachable)
        XCTAssertEqual(mock.requests.count, 2) // CAS is not contacted with portal cookies.
        XCTAssertEqual(service.sessionState, .signedOut) // Reachable is not authenticated.
    }
    func testProbeDoesNotReadOrWriteRealWebViewCookies() async throws {
        let mock = MockPortalTransport()
        mock.handler = { request in
            XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
            return Self.response(request, status: 302, headers: ["Location":PortalConfiguration.login.absoluteString, "Set-Cookie":"probe=synthetic; Path=/"])
        }
        let service = PortalService(transport: mock)
        let cookie = HTTPCookie(properties: [.name:"existing-test",.value:"synthetic-private",.domain:"my.bupt.edu.cn",.path:"/"])!
        await service.websiteDataStore.httpCookieStore.setCookie(cookie)
        _ = await service.probe()
        let stored = await service.websiteDataStore.httpCookieStore.allCookies()
        XCTAssertTrue(stored.contains { $0.name == "existing-test" && $0.value == "synthetic-private" })
        XCTAssertFalse(stored.contains { $0.name == "probe" })
        await service.websiteDataStore.httpCookieStore.delete(cookie)
    }
    func testProbeJarIsFreshForEveryAttempt() async throws {
        let mock = MockPortalTransport()
        mock.handler = { request in
            XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
            return Self.response(request, status: 302, headers: ["Location":PortalConfiguration.login.absoluteString,"Set-Cookie":"JSESSIONID=synthetic; Path=/"])
        }
        let service = PortalService(transport: mock)
        _ = await service.probe(); _ = await service.probe()
        XCTAssertEqual(mock.requests.count, 2)
    }
    func testForeignRedirectNeverReceivesCookieOrRequest() async {
        let mock = MockPortalTransport()
        mock.handler = { Self.response($0, status: 302, headers: ["Location":"https://other.bupt.edu.cn/collect", "Set-Cookie":"probe=synthetic; Domain=.bupt.edu.cn; Path=/"]) }
        let result = await PortalService(transport: mock).probe()
        if case .rejected = result {} else { XCTFail("Unknown redirects must not prove reachability") }
        XCTAssertEqual(mock.requests.count, 1)
    }
    func testUnrecognizedHTTP200IsNotConnectionOrLoginProof() async {
        let mock = MockPortalTransport()
        mock.handler = { Self.response($0, status: 200, body: "<html><title>System message</title></html>") }
        let service = PortalService(transport: mock)
        let result = await service.probe()
        if case .rejected = result {} else { XCTFail("Unknown page must remain unverified") }
        XCTAssertNotEqual(service.sessionState, .authenticated)
    }
    func testTLSFailureDoesNotRequestVPNFallback() async {
        let mock = MockPortalTransport()
        mock.handler = { _ in throw URLError(.serverCertificateUntrusted) }
        let result = await PortalService(transport: mock).probe()
        if case .rejected = result {} else { XCTFail("TLS is a security failure, not route absence") }
    }
    func testNetworkFailureCanSuggestCampusConnection() async {
        let mock = MockPortalTransport()
        mock.handler = { _ in throw URLError(.cannotConnectToHost) }
        let result = await PortalService(transport: mock).probe()
        XCTAssertEqual(result, .unavailable)
    }
    func testCookieScopeSecureExpiryAndDeletion() throws {
        var jar = PortalCookieJar()
        let secure = HTTPCookie(properties:[.name:"s",.value:"synthetic",.domain:"my.bupt.edu.cn",.path:"/system",.secure:"TRUE"])!
        jar.set(secure)
        XCTAssertNil(jar.header(for: URL(string:"http://my.bupt.edu.cn/system/file")!))
        XCTAssertNotNil(jar.header(for: URL(string:"https://my.bupt.edu.cn/system/file")!))
        XCTAssertNil(jar.header(for: URL(string:"https://my.bupt.edu.cn/systematic")!))
        XCTAssertNil(jar.header(for: PortalConfiguration.login))
        jar.set(HTTPCookie(properties:[.name:"s",.value:"",.domain:"my.bupt.edu.cn",.path:"/system",.expires:Date(timeIntervalSince1970:0)])!)
        XCTAssertNil(jar.header(for: URL(string:"https://my.bupt.edu.cn/system/file")!))
    }
    func testPortalHomeAndNoticeListAreSeparate() {
        XCTAssertEqual(PortalConfiguration.entry.path, "/xs_index.jsp")
        XCTAssertEqual(PortalConfiguration.notices.path, "/list.jsp")
        XCTAssertTrue(PortalConfiguration.notices.query?.contains("wbtreeid=1154") == true)
    }
    func testRestoredCookiePreservesPortalHTTPAndHTTPSBoundary() throws {
        for secure in [false, true] {
            var properties: [HTTPCookiePropertyKey: Any] = [
                .name: "synthetic", .value: "test", .domain: "my.bupt.edu.cn", .path: "/"
            ]
            if secure { properties[.secure] = "TRUE" }
            let cookie = try XCTUnwrap(HTTPCookie(properties: properties))
            let data = try JSONEncoder().encode(PortalService.SavedCookie(cookie))
            let restored = try XCTUnwrap(JSONDecoder().decode(PortalService.SavedCookie.self, from: data).cookie)
            var jar = PortalCookieJar()
            jar.set(restored)
            XCTAssertEqual(restored.isSecure, secure)
            XCTAssertEqual(jar.header(for: PortalConfiguration.notices) != nil, !secure)
        }
    }
    func testForgedPaginationIsRejectedBeforeAnyRequest() async {
        let mock = MockPortalTransport()
        mock.handler = { request in XCTFail("Unvalidated pagination must never reach transport"); return Self.response(request, status: 200) }
        let service = PortalService(transport: mock)
        for address in ["https://other.bupt.edu.cn/system/resource/getmore.jsp", "http://my.bupt.edu.cn/system/logout.jsp", "http://my.bupt.edu.cn/system/resource/getmore.jsp?count=999999"] {
            do { _ = try await service.fetchList(MoreRequest(url: URL(string: address)!)); XCTFail("Forged URL accepted") }
            catch { }
        }
        XCTAssertEqual(mock.requests.count, 0)
    }
    func testCredentialAdapterRequiresExactHTTPSCASAndRecognizedStructure() {
        var form: [String:Any] = ["page":PortalConfiguration.login.absoluteString,"action":PortalConfiguration.login.absoluteString,"id":"loginForm","method":"post","users":1,"passwords":1,"execution":true,"event":true]
        XCTAssertTrue(PortalFormPolicy.accepts(form))
        form["action"] = "https://auth.bupt.edu.cn.evil.invalid/authserver/login"
        XCTAssertFalse(PortalFormPolicy.accepts(form))
        form["action"] = "http://auth.bupt.edu.cn/authserver/login"
        XCTAssertFalse(PortalFormPolicy.accepts(form))
        form["action"] = PortalConfiguration.login.absoluteString; form["passwords"] = 2
        XCTAssertFalse(PortalFormPolicy.accepts(form))
    }
    func testAuthenticationRedirectKeepsInitialSessionCookieForWebLogin() async throws {
        let mock = MockPortalTransport()
        mock.handler = { request in
            Self.response(request, status: 302, headers:["Location":PortalConfiguration.login.absoluteString,"Set-Cookie":"session-for-callback=synthetic; Path=/; HttpOnly"])
        }
        let service = PortalService(transport:mock)
        try await service.selectAccount("synthetic-callback-account")
        XCTAssertFalse(service.canReadCachedAccount)
        do { _ = try await service.fetchList(); XCTFail("Login required") }
        catch PortalError.authenticationRequired { }
        let cookies = await service.websiteDataStore.httpCookieStore.allCookies()
        XCTAssertTrue(cookies.contains { $0.name == "session-for-callback" && $0.value == "synthetic" })
        XCTAssertEqual(service.sessionState, .loginRequired)
        XCTAssertFalse(service.canReadCachedAccount)
        for cookie in cookies { await service.websiteDataStore.httpCookieStore.delete(cookie) }
    }
    func testAutomaticRetryCooldownAndPauseSurviveNewService() async throws {
        let service = PortalService(transport:MockPortalTransport())
        try await service.selectAccount("synthetic-cooldown-account")
        let paused = "portal-auto-paused:" + service.profileID, next = "portal-auto-next:" + service.profileID
        UserDefaults.standard.removeObject(forKey:paused); UserDefaults.standard.removeObject(forKey:next)
        defer { UserDefaults.standard.removeObject(forKey:paused); UserDefaults.standard.removeObject(forKey:next) }
        XCTAssertTrue(service.automaticRestoreAllowed)
        service.noteAutomaticAttempt(); XCTAssertFalse(service.automaticRestoreAllowed)
        UserDefaults.standard.removeObject(forKey:next); service.pauseAutomaticRestore()
        let restarted = PortalService(transport:MockPortalTransport())
        try await restarted.selectAccount("synthetic-cooldown-account")
        XCTAssertFalse(restarted.automaticRestoreAllowed)
    }
    func testAccountSwitchUsesANewWebStoreSoLateWritesCannotCrossAccounts() async throws {
        let service = PortalService(transport:MockPortalTransport())
        try await service.selectAccount("synthetic-owner-a")
        let oldStore = service.websiteDataStore
        let oldProfile = service.profileID
        try await service.selectAccount("synthetic-owner-b")
        XCTAssertFalse(oldStore === service.websiteDataStore)
        XCTAssertNotEqual(oldProfile, service.profileID)
        XCTAssertFalse(service.canReadCachedAccount)
        let late = HTTPCookie(properties:[.name:"late-old-owner",.value:"synthetic",.domain:"my.bupt.edu.cn",.path:"/"])!
        await oldStore.httpCookieStore.setCookie(late)
        let current = await service.websiteDataStore.httpCookieStore.allCookies()
        XCTAssertFalse(current.contains { $0.name == "late-old-owner" })
    }
    func testURLBoundariesRejectUserInfoPortsAndSiblingHosts() {
        for value in ["http://user@my.bupt.edu.cn/list.jsp", "http://my.bupt.edu.cn:8080/list.jsp", "http://my.bupt.edu.cn.evil.invalid/list.jsp", "https://auth.bupt.edu.cn:444/authserver/login"] {
            XCTAssertFalse(PortalConfiguration.web(URL(string:value)!))
        }
    }
    private static func response(_ request: URLRequest, status: Int, headers: [String:String] = [:], body: String = "") -> PortalHTTPResponse {
        PortalHTTPResponse(response: HTTPURLResponse(url:request.url!,statusCode:status,httpVersion:"HTTP/1.1",headerFields:headers)!,data:Data(body.utf8))
    }
}

private final class MockPortalTransport: PortalTransport {
    var requests: [URLRequest] = []
    var handler: ((URLRequest) throws -> PortalHTTPResponse)!
    func get(_ request: URLRequest, maxBytes: Int, allowPrefix: Bool) async throws -> PortalHTTPResponse {
        requests.append(request)
        return try handler(request)
    }
}
