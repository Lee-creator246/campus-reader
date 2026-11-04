import XCTest
@testable import CampusReaderCore

final class AIProtocolTests: XCTestCase {
    func testDuplicateEscapedKeysRejected() {
        XCTAssertThrowsError(try AIJSON.object(Data(#"{"value":1,"\u0076alue":2}"#.utf8)))
        XCTAssertThrowsError(try AIJSON.object(Data(#"{"x":{"v":1,"v":2}}"#.utf8)))
        XCTAssertNoThrow(try AIJSON.object(Data(#"{"x":1,"child":{"x":2}}"#.utf8)))
    }
    func testDepthAndTrailingContentRejected() {
        XCTAssertThrowsError(try AIJSON.object(Data((String(repeating: "[", count: 20) + "0" + String(repeating: "]", count: 20)).utf8)))
        XCTAssertThrowsError(try AIJSON.object(Data("{} {}".utf8)))
    }
    func testMalformedAndOversizedContentRejected() {
        XCTAssertThrowsError(try AIJSON.object(Data(#"{"x":01}"#.utf8)))
        XCTAssertThrowsError(try AIJSON.object(Data(#"{"x":true,}"#.utf8)))
        XCTAssertThrowsError(try AIJSON.object(Data(repeating: 32, count: 65537)))
    }
    private func response(finish: String, content: Any) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["choices": [["finish_reason": finish, "message": ["content": content, "reasoning_content": "not a visible summary"]]]])
    }
    func testHttp200LengthMustNotDisplayPartialSummary() throws {
        XCTAssertThrowsError(try AIResponseEnvelope.content(data: response(finish: "length", content: "partial"), status: 200)) { XCTAssertEqual($0 as? AIError, .truncated) }
    }
    func testReasoningWithoutContentIsNotDisplayed() throws {
        XCTAssertThrowsError(try AIResponseEnvelope.content(data: response(finish: "stop", content: NSNull()), status: 200)) { XCTAssertEqual($0 as? AIError, .emptyContent) }
        XCTAssertThrowsError(try AIResponseEnvelope.content(data: response(finish: "stop", content: "  "), status: 200))
    }
    func testStopOnlyAndHttpFailure() throws {
        XCTAssertEqual(try AIResponseEnvelope.content(data: response(finish: "stop", content: "{}"), status: 200), Data("{}".utf8))
        XCTAssertThrowsError(try AIResponseEnvelope.content(data: response(finish: "content_filter", content: "{}"), status: 200))
        XCTAssertThrowsError(try AIResponseEnvelope.content(data: Data("private server message".utf8), status: 401)) { XCTAssertEqual($0 as? AIError, .http(401)); XCTAssertFalse($0.localizedDescription.contains("private server")) }
    }
    func testDeepSeekFlagNeverLeaksToCompatibleEndpoint() throws {
        let notice = Notice(id: "n:1", title: "合成通知", publishedAt: Date(), bodyText: "提交报名表。", originalURL: URL(string: "https://portal.example.edu/item")!)
        let source = try AISourceSnapshot(notice: notice)
        let official = try AIJSON.object(AIResponseEnvelope.request(configuration: .default, source: source), maxBytes: 128 * 1024)
        XCTAssertEqual((official["thinking"] as? [String: String])?["type"], "disabled")
        var config = SummaryConfiguration.default; config.provider = .compatible; config.endpoint = "https://api.example.edu/v1/chat/completions"
        let custom = try AIJSON.object(AIResponseEnvelope.request(configuration: config, source: source), maxBytes: 128 * 1024)
        XCTAssertNil(custom["thinking"])
    }
    func testEndpointValidation() {
        for address in ["http://api.deepseek.com/chat/completions", "https://api.deepseek.com.evil.invalid/chat/completions", "https://user:secret@api.deepseek.com/chat/completions", "https://api.deepseek.com/chat/completions?key=secret"] {
            var config = SummaryConfiguration.default; config.endpoint = address; XCTAssertThrowsError(try config.validated())
        }
    }
    func testFailedAttemptIsChargedAndNotAutomaticallyRepeated() throws {
        var ledger = AIQuotaLedger(); let now = Date(timeIntervalSince1970: 1_700_000_000)
        try ledger.reserve(identity: "first", limit: 2, automatic: true, now: now)
        XCTAssertEqual(ledger.used, 1); XCTAssertFalse(ledger.permitsAutomatic("first"))
        XCTAssertThrowsError(try ledger.reserve(identity: "first", limit: 2, automatic: true, now: now))
        try ledger.reserve(identity: "first", limit: 2, automatic: false, now: now)
        XCTAssertEqual(ledger.used, 2)
        XCTAssertThrowsError(try ledger.reserve(identity: "second", limit: 2, automatic: false, now: now))
    }
    func testClockRollbackDoesNotRefundAttempts() throws {
        var ledger = AIQuotaLedger(); let now = Date(timeIntervalSince1970: 1_700_000_000)
        try ledger.reserve(identity: "first", limit: 1, automatic: true, now: now)
        XCTAssertThrowsError(try ledger.reserve(identity: "second", limit: 1, automatic: true, now: now.addingTimeInterval(-86400)))
        XCTAssertEqual(ledger.used, 1)
    }
    func testChangingLimitDoesNotRefundAndDayChangeKeepsAttemptIdentity() throws {
        var ledger = AIQuotaLedger(); let now = Date(timeIntervalSince1970: 1_700_000_000)
        try ledger.reserve(identity: "first", limit: 2, automatic: true, now: now)
        try ledger.reserve(identity: "second", limit: 100, automatic: true, now: now)
        XCTAssertEqual(ledger.used, 2)
        let restored = try JSONDecoder().decode(AIQuotaLedger.self, from: JSONEncoder().encode(ledger))
        XCTAssertEqual(restored.used, 2); XCTAssertFalse(restored.permitsAutomatic("first"))
        try ledger.reserve(identity: "third", limit: 20, automatic: true, now: now.addingTimeInterval(86400))
        XCTAssertEqual(ledger.used, 1); XCTAssertFalse(ledger.permitsAutomatic("first"))
    }
}
