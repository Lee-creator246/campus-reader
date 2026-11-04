import XCTest
@testable import CampusReaderCore

final class AISummaryTests: XCTestCase {
    private func notice(_ text: String = "报名截止时间为11月8日18:00，仅限本科生，提交1份证明。") -> Notice {
        Notice(id: "n:1", title: "志愿活动报名", publishedAt: Date(timeIntervalSince1970: 100), bodyText: text, originalURL: URL(string: "https://portal.example.edu/item")!)
    }
    private func payload(text: String, quote: String, id: String = "N2", points: [[String: Any]] = [], tags: [String] = [], links: [[String: String]] = []) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["schema": 3, "overview": ["text": text, "refs": [["id": id, "quote": quote]]], "points": points, "tags": tags, "policy_links": links])
    }
    func testExactCitationCarriesLocalContext() throws {
        let item = notice(); let source = try AISourceSnapshot(notice: item)
        let summary = try AISummaryParser.parse(payload(text: "仅限本科生，11月8日18:00前提交1份证明。", quote: item.bodyText), source: source)
        XCTAssertEqual(summary.noticeID, item.id); XCTAssertEqual(summary.bodyVersion, item.bodyVersion)
        XCTAssertEqual(summary.overview.citations.first?.sourceText, item.bodyText)
        XCTAssertFalse(summary.overview.isExcerpt)
    }
    func testUnknownOrNonContiguousQuoteRejected() throws {
        let source = try AISourceSnapshot(notice: notice())
        XCTAssertThrowsError(try AISummaryParser.parse(payload(text: "报名截止", quote: "11月8日，提交证明"), source: source))
        XCTAssertThrowsError(try AISummaryParser.parse(payload(text: "报名截止", quote: "11月8日", id: "N999"), source: source))
    }
    func testInventedNumberFallsBackToExactExcerpt() throws {
        let item = notice(); let source = try AISourceSnapshot(notice: item)
        let summary = try AISummaryParser.parse(payload(text: "仅限本科生，提交2份证明。", quote: item.bodyText), source: source)
        XCTAssertTrue(summary.overview.isExcerpt); XCTAssertEqual(summary.snippet, item.bodyText)
    }
    func testOmittedRestrictionFallsBack() throws {
        let item = notice(); let source = try AISourceSnapshot(notice: item)
        let summary = try AISummaryParser.parse(payload(text: "提交1份证明即可报名。", quote: item.bodyText), source: source)
        XCTAssertTrue(summary.overview.isExcerpt)
    }
    func testUnsupportedRestrictionFallsBack() throws {
        let item = notice("提交报名表。"); let source = try AISourceSnapshot(notice: item)
        let summary = try AISummaryParser.parse(payload(text: "免费提交报名表。", quote: item.bodyText), source: source)
        XCTAssertEqual(summary.snippet, item.bodyText)
    }
    func testSignedAmountsAreNotInterchangeable() {
        XCTAssertFalse(AISummaryParser.numbersSupported("获得5元", evidence: "余额-5元"))
        XCTAssertFalse(AISummaryParser.numbersSupported("余额-5元", evidence: "获得5元"))
        XCTAssertTrue(AISummaryParser.numbersSupported("余额-5元", evidence: "当前余额-5元"))
    }
    func testAssociationAndDateMustMatchNotOnlyDigits() {
        XCTAssertFalse(AISummaryParser.numbersSupported("11月9日", evidence: "11月8日，9人报名"))
        XCTAssertFalse(AISummaryParser.numbersSupported("12元", evidence: "12人，每人5元"))
    }
    func testSixPointsAcceptedButSevenRejected() throws {
        let item = notice(); let source = try AISourceSnapshot(notice: item)
        let points: [[String: Any]] = (0..<6).map { _ in ["kind": "行动", "text": "提交1份证明", "refs": [["id": "N2", "quote": "提交1份证明"]]] }
        XCTAssertNoThrow(try AISummaryParser.parse(payload(text: "提交1份证明", quote: "提交1份证明", points: points), source: source))
        XCTAssertThrowsError(try AISummaryParser.parse(payload(text: "提交1份证明", quote: "提交1份证明", points: points + [points[0]]), source: source))
    }
    func testPoliciesCannotProvideNoticeFacts() throws {
        let source = try AISourceSnapshot(notice: notice(), policies: [PolicyPassage(id: "P1_1", documentTitle: "合成政策", location: "第1页", text: "资助100元。")])
        XCTAssertThrowsError(try AISummaryParser.parse(payload(text: "资助100元。", quote: "资助100元。", id: "P1_1"), source: source))
        let summary = try AISummaryParser.parse(payload(text: "提交1份证明", quote: "提交1份证明", links: [["notice": "N2", "policy": "P1_1"]]), source: source)
        XCTAssertEqual(summary.policyLinks.count, 1)
        XCTAssertEqual(summary.policyLinks[0].policy.documentTitle, "合成政策")
    }
    func testTagsAndFieldsAreControlled() throws {
        let source = try AISourceSnapshot(notice: notice())
        XCTAssertThrowsError(try AISummaryParser.parse(payload(text: "提交1份证明", quote: "提交1份证明", tags: ["伪造类别"]), source: source))
        var object = try AIJSON.object(payload(text: "提交1份证明", quote: "提交1份证明")); object["hidden"] = "unexpected"
        XCTAssertThrowsError(try AISummaryParser.parse(JSONSerialization.data(withJSONObject: object), source: source))
    }
    func testSourceHasBoundedTextAndNoAttachments() throws {
        var item = notice(String(repeating: "合成正文", count: 20_000))
        item.attachments = [NoticeAttachment(name: "secret.pdf", url: URL(string: "https://portal.example.edu/private")!)]
        let source = try AISourceSnapshot(notice: item)
        XCTAssertLessThanOrEqual(source.units.filter { $0.kind == "notice" && $0.id != "N1" }.map(\.text.count).reduce(0,+), 12_000)
        let request = try AIResponseEnvelope.request(configuration: .default, source: source)
        XCTAssertLessThanOrEqual(request.count, 128 * 1024)
        XCTAssertFalse(String(decoding: request, as: UTF8.self).contains("secret.pdf"))
    }
    func testEmptyBodyCannotSendTitleOnlySummary() { XCTAssertThrowsError(try AISourceSnapshot(notice: notice(""))) }
}
