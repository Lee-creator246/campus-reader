import XCTest
@testable import CampusReaderCore

enum PortalFixture {
    static let base = "http://my.bupt.edu.cn"
    static let list = URL(string: base + "/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1154")!
    static func url(_ id: String = "101", tree: String = "52") -> URL {
        URL(string: base + "/xntz_content.jsp?urltype=news.NewsContentUrl&wbtreeid=\(tree)&wbnewsid=\(id)")!
    }
    static func row(_ id: String, date: String = "2026-11-04", tree: String = "52") -> String {
        "<li class='am-list-item-dated' id='\(id)'><a class='am-list-item-hd' href='\(url(id,tree:tree))'>合成通知 \(id)</a><span class='am-list-date'>\(date)</span></li>"
    }
    static let script = "<script>var count_12=2;var start_12=2;function more(){ $.get('/system/resource/getmore.jsp',{count:count_12,start:start_12,wbtreeid:1154,wbviewid:12,owner:99,dbname:'vsb',tf:'yyyy-MM-dd&zh',titlelen:0,summarylen:0,newsshowtype:'0',hastitlepic:'false',mode:10,ellipsismode:1,pageurl:'/mobile/list.jsp'},function(info){},'xml');}</script>"
    static func listing(_ content: String? = nil) -> String {
        let rows = content ?? (row("101") + row("100",date:"2026-01-01"))
        return "<div class='main'><ul class='am-list'>\(rows)<li class='am-list-item-dated' id='more_template_12'><a>{title}</a></li></ul>\(script)</div>"
    }
    static func detail(_ body: String = "<p>合成正文</p>", extra: String = "") -> String {
        "<form name='_newscontent_fromname'><div><h1>合成标题</h1><div>2026-11-04&nbsp;合成办公室</div><hr><div id='vsb_content_100'><div class='v_news_content'>\(body)</div></div>\(extra)</div></form>"
    }
    static func item(_ id: String, tree: String = "52") -> String {
        "<news id='\(id)'><showTitle><![CDATA[合成分页通知]]></showTitle><showDate>2026-11-03</showDate><url><![CDATA[../xntz_content.jsp?urltype=news.NewsContentUrl&wbtreeid=\(tree)&wbnewsid=\(id)]]></url></news>"
    }
}

final class PortalParserTests: XCTestCase {
    func testObservedMobileTemplateSkipsPlaceholderAndOrdersPublishedDate() throws {
        let page = try PortalParser.parseList(url: PortalFixture.list, status: 200, html: PortalFixture.listing())
        XCTAssertEqual(page.notices.map(\.id), ["n:101", "n:100"])
        XCTAssertEqual(page.notices[0].publishedAt.timeIntervalSince1970, 1793721600)
        XCTAssertNotNil(page.next)
        XCTAssertTrue(page.next!.url.absoluteString.contains("yyyy-MM-dd%26zh"))
    }
    func testPortalHomeIsNotMistakenForNoticeList() {
        XCTAssertThrowsError(try PortalParser.parseList(url: URL(string: PortalFixture.base + "/xs_index.jsp?wbtreeid=1541")!, status: 200, html: PortalFixture.listing()))
    }
    func testListIdMustAgreeWithLink() {
        XCTAssertThrowsError(try PortalParser.parseList(url: PortalFixture.list, status: 200, html: PortalFixture.listing().replacingOccurrences(of: "id='101'", with: "id='999'")))
    }
    func testSameNoticeAcrossCategoriesHasOneStableIdentity() throws {
        let page = try PortalParser.parseList(url: PortalFixture.list, status: 200, html: PortalFixture.listing(PortalFixture.row("101") + PortalFixture.row("101", tree:"53")))
        XCTAssertEqual(page.notices.count, 1); XCTAssertEqual(page.notices.first?.id, "n:101")
    }
    func testRejectsDuplicateIDsInURLAndImpossibleDates() {
        XCTAssertThrowsError(try PortalParser.parseDetail(url: URL(string: PortalFixture.url().absoluteString + "&wbnewsid=102")!, status: 200, html: PortalFixture.detail()))
        XCTAssertThrowsError(try PortalParser.parseList(url: PortalFixture.list, status: 200, html: PortalFixture.listing(PortalFixture.row("101", date:"2026-02-30"))))
    }
    func testAuthenticationPageAndForbiddenAreDistinct() {
        XCTAssertThrowsError(try PortalParser.parseList(url: PortalFixture.list, status: 200, html: "<input type='password'>")) { XCTAssertEqual($0 as? PortalError, .authenticationRequired) }
        XCTAssertThrowsError(try PortalParser.parseList(url: PortalFixture.list, status: 403, html: "denied")) { XCTAssertEqual($0 as? PortalError, .accessDenied) }
    }
    func testBlankOrUnknownPagesCannotClaimSuccessfulAuthentication() {
        for html in ["", "<html><title>系统提示</title></html>", PortalFixture.listing("")] {
            XCTAssertThrowsError(try PortalParser.parseList(url: PortalFixture.list, status: 200, html: html))
        }
    }
    func testHTMLSanitizationImagesAndAttachmentEntry() throws {
        let body = "<p>正文</p><img src='/__local/synthetic.png' onerror='bad()'><script>bad()</script><a href='javascript:bad()'>unsafe</a>"
        let attachment = "<a href='/system/_content/download.jsp?urltype=news.DownloadAttachUrl&owner=99&wbfileid=123'>合成附件.pdf</a>"
        let notice = try PortalParser.parseDetail(url: PortalFixture.url(), status: 200, html: PortalFixture.detail(body, extra: attachment))
        XCTAssertEqual(notice.images, [URL(string: PortalFixture.base + "/__local/synthetic.png")!])
        XCTAssertEqual(notice.attachments.first?.name, "合成附件.pdf")
        XCTAssertFalse(notice.bodyHTML.contains("script")); XCTAssertFalse(notice.bodyHTML.contains("onerror"))
        XCTAssertFalse(notice.bodyHTML.contains("javascript:")); XCTAssertTrue(notice.bodyText.contains("正文"))
    }
    func testTextOnlyNoticeHasNoPlaceholderImage() throws {
        let notice = try PortalParser.parseDetail(url: PortalFixture.url(), status: 200, html: PortalFixture.detail())
        XCTAssertTrue(notice.images.isEmpty)
    }
    func testPaginationAdvancesAndRecognizedEmptyPageEnds() throws {
        let first = try XCTUnwrap(PortalParser.parseList(url: PortalFixture.list,status:200,html:PortalFixture.listing()).next)
        let page = try PortalParser.parseMore(url:first.url,status:200,xml:"<data>"+PortalFixture.item("102")+PortalFixture.item("103")+"</data>",request:first)
        XCTAssertEqual(page.notices.count,2); XCTAssertEqual(try PortalParser.query(XCTUnwrap(page.next).url)["start"],"4")
        XCTAssertEqual(page.notices[0].originalURL.path,"/xntz_content.jsp")
        XCTAssertNil(try PortalParser.parseMore(url:first.url,status:200,xml:"<data/>",request:first).next)
    }
    func testRejectsXMLExternalEntityAndMismatchedPagination() throws {
        let request = try XCTUnwrap(PortalParser.parseList(url:PortalFixture.list,status:200,html:PortalFixture.listing()).next)
        for xml in ["<!DOCTYPE data [<!ENTITY a SYSTEM 'file:///private'>]><data/>","<html>Error</html>","<data>"+PortalFixture.item("102").replacingOccurrences(of:"id='102'",with:"id='999'")+"</data>"] {
            XCTAssertThrowsError(try PortalParser.parseMore(url:request.url,status:200,xml:xml,request:request))
        }
        let changed = URL(string:request.url.absoluteString.replacingOccurrences(of:"start=2",with:"start=99"))!
        XCTAssertThrowsError(try PortalParser.parseMore(url:changed,status:200,xml:"<data/>",request:request))
    }
    func testOnlyBoundedObservedPaginationGrammarAccepted() {
        for change in [PortalFixture.listing().replacingOccurrences(of:"count_12=2",with:"count_12=999"),PortalFixture.listing().replacingOccurrences(of:"wbtreeid:1154",with:"wbtreeid:999"),PortalFixture.listing().replacingOccurrences(of:"mode:10",with:"mode:execute()"),PortalFixture.listing().replacingOccurrences(of:"owner:99",with:"owner:99,owner:99")] {
            XCTAssertThrowsError(try PortalParser.parseList(url:PortalFixture.list,status:200,html:change))
        }
    }
    func testURLOriginGuardsRejectUserInfoPortsAndFragments() {
        for value in ["http://my.bupt.edu.cn:443/list.jsp", "https://my.bupt.edu.cn:80/list.jsp", "http://user@my.bupt.edu.cn/list.jsp", "http://my.bupt.edu.cn/list.jsp#x", "http://my.bupt.edu.cn.evil.invalid/list.jsp"] { XCTAssertFalse(PortalParser.isPortalURL(URL(string:value)!)) }
    }
}
