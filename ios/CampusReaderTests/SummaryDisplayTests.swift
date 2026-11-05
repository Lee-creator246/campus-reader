import XCTest
import CampusReaderCore
@testable import CampusReader

/// Mutate only the HTTP response. Request creation, decoding, persistence, and the lookup
/// used by the reader views all go through their normal implementations.
private final class SummaryFixtureProtocol: URLProtocol {
    private static let summary = #"{"schema":3,"overview":{"text":"提交报名表。","refs":[{"id":"N2","quote":"提交报名表。"}]},"points":[],"tags":[],"policy_links":[]}"#

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}
    override func startLoading() {
        guard let url = request.url, url.host == "model.example.invalid", request.httpMethod == "POST" else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        let content: Any
        let finish: String
        switch request.value(forHTTPHeaderField: "Authorization") {
        case "Bearer synthetic-replacement":
            content = Self.summary
            finish = "stop"
        case "Bearer synthetic-truncated":
            content = String(Self.summary.prefix(30))
            finish = "length"
        case "Bearer synthetic-reasoning-only":
            content = NSNull()
            finish = "stop"
        default:
            client?.urlProtocol(self, didFailWithError: URLError(.userAuthenticationRequired))
            return
        }
        do {
            let bytes = try JSONSerialization.data(withJSONObject: ["choices": [[
                "message": ["content": content, "reasoning_content": "合成推理文本"],
                "finish_reason": finish
            ]]])
            let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: bytes)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
}

final class SummaryDisplayTests: XCTestCase {
    @MainActor
    func testReconfiguredAPIResponseRemainsReadableAfterFailuresAndRelaunch() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var syntheticKeys = [String: Data]()
        var dependencies = AIService.Dependencies()
        dependencies.cacheDirectory = directory
        dependencies.readKey = { syntheticKeys[$0] }
        dependencies.writeKey = { syntheticKeys[$1] = $0 }
        dependencies.deleteKey = { syntheticKeys.removeValue(forKey: $0) }
        dependencies.isActive = { true }
        dependencies.sessionConfiguration = {
            let session = URLSessionConfiguration.ephemeral
            session.protocolClasses = [SummaryFixtureProtocol.self]
            return session
        }

        let profile = "synthetic-display-test"
        let notice = Notice(id: "n:42", title: "报名通知", publishedAt: Date(timeIntervalSince1970: 1_000),
                            bodyText: "提交报名表。", originalURL: URL(string: "https://portal.example.invalid/item")!)
        let service = AIService(dependencies: dependencies)
        service.activate(profile: profile)
        var config = SummaryConfiguration.default
        config.provider = .compatible
        config.endpoint = "https://model.example.invalid/chat/completions"
        config.model = "old-model"
        config.enabled = true
        try service.configure(config, key: "synthetic-original")
        config.model = "replacement-model"
        try service.configure(config, key: "synthetic-replacement")
        try service.configure(config, key: nil) // Saving the same endpoint again retains its key.

        let generated = try await service.summarize(notice)
        XCTAssertEqual(generated.snippet, "提交报名表。")
        XCTAssertEqual(generated.overview.citations.first?.quote, notice.bodyText)
        XCTAssertEqual(service.cached(notice), generated)

        for (key, failure) in [("synthetic-truncated", AIError.truncated), ("synthetic-reasoning-only", AIError.emptyContent)] {
            try service.configure(config, key: key)
            do {
                _ = try await service.summarize(notice)
                XCTFail("An incomplete response replaced a readable summary")
            } catch { XCTAssertEqual(error as? AIError, failure) }
            XCTAssertEqual(service.cached(notice), generated)
        }

        let reopened = AIService(dependencies: dependencies)
        reopened.activate(profile: profile)
        XCTAssertEqual(reopened.configuration, config)
        XCTAssertEqual(reopened.cached(notice), generated)
        var revised = notice
        revised.bodyText = "报名要求已更改。"
        XCTAssertNil(reopened.cached(revised))
        reopened.activate(profile: "another-synthetic-profile")
        XCTAssertNil(reopened.cached(notice))
    }
}
