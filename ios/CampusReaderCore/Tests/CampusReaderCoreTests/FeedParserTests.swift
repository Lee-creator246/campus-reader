import XCTest
@testable import CampusReaderCore

final class FeedParserTests: XCTestCase {
    let url=URL(string:"https://example.org/feed.xml")!
    func testRSSUsesStableGuidAndSanitizesContent() throws {
        let xml="<rss><channel><item><guid>stable-id</guid><title>Example</title><link>https://example.org/article</link><pubDate>Wed, 04 Nov 2026 12:00:00 +0800</pubDate><description><![CDATA[<p>Text</p><script>bad()</script><img src='/image.png' onerror='bad()'>]]></description></item></channel></rss>"
        let a=try FeedParser.parse(url:url,xml:xml), b=try FeedParser.parse(url:url,xml:xml.replacingOccurrences(of:"Example",with:"Edited"))
        XCTAssertEqual(a.count,1);XCTAssertEqual(a[0].id,b[0].id);XCTAssertNotEqual(a[0].bodyVersion,b[0].bodyVersion)
        XCTAssertFalse(a[0].bodyHTML.contains("script"));XCTAssertFalse(a[0].bodyHTML.contains("onerror"))
        XCTAssertEqual(a[0].images.first,URL(string:"https://example.org/image.png"))
    }
    func testAtomUsesPublishedDateAndEnclosure() throws {
        let xml="<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>urn:item:1</id><title>Atom item</title><link href='https://example.org/article'/><link rel='enclosure' href='https://example.org/a.pdf'/><published>2026-11-04T04:00:00Z</published><content>&lt;p&gt;Text&lt;/p&gt;</content></entry></feed>"
        let notice=try XCTUnwrap(FeedParser.parse(url:url,xml:xml).first)
        XCTAssertEqual(notice.bodyText,"Text");XCTAssertEqual(notice.attachments.count,1)
        XCTAssertEqual(notice.originalURL,URL(string:"https://example.org/article"))
    }
    func testStableLinkFallbackAndFeedScopes() throws {
        let xml="<rss><channel><item><title>Item</title><link>https://example.org/a</link></item></channel></rss>"
        let a=try FeedParser.parse(url:url,xml:xml)[0], b=try FeedParser.parse(url:URL(string:"https://other.example/feed")!,xml:xml)[0]
        XCTAssertNotEqual(a.id,b.id);XCTAssertEqual(a.publishedAt,Date(timeIntervalSince1970:0))
    }
    func testUnstableIdentityAndExternalEntitiesFailClosed() {
        for xml in ["<rss><channel><item><title>Missing ID</title></item></channel></rss>","<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///private'>]><feed/>","<html>Not a feed</html>"] { XCTAssertThrowsError(try FeedParser.parse(url:url,xml:xml)) }
    }
    func testRecognizedEmptyFeedAndDuplicateGuid() throws {
        XCTAssertEqual(try FeedParser.parse(url:url,xml:"<rss><channel/></rss>"),[])
        let item="<item><guid>same</guid><title>Item</title></item>"
        XCTAssertEqual(try FeedParser.parse(url:url,xml:"<rss><channel>"+item+item+"</channel></rss>").count,1)
    }
}
