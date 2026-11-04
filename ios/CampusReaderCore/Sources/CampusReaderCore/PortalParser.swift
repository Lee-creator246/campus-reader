import Foundation
import SwiftSoup

/// A port of the verified mobile template, not a heuristic scraper of the portal home page.
public enum PortalParser {
    public static let source = "campus:portal:notices"
    public static func validateMoreRequest(_ request: MoreRequest) throws {
        _ = try paginationParameters(request.url)
    }
    public static func isPortalURL(_ url: URL) -> Bool {
        guard url.host?.lowercased() == "my.bupt.edu.cn", url.user == nil, url.password == nil,
              url.fragment == nil else { return false }
        return (url.scheme == "http" && (url.port == nil || url.port == 80)) ||
            (url.scheme == "https" && (url.port == nil || url.port == 443))
    }
    public static func parseList(url: URL, status: Int, html: String) throws -> NoticePage {
        let doc = try document(url, status, html)
        guard url.path == "/list.jsp" else { throw invalid("必须使用移动通知列表，不能将门户首页当作列表") }
        let category = try digits(query(url)["wbtreeid"])
        let list = try only(doc.select(".main ul.am-list"), "通知列表")
        var notices: [Notice] = []
        for row in try list.select("li.am-list-item-dated").array() {
            if row.id().hasPrefix("more_template_") { continue }
            let link = try only(row.select("a.am-list-item-hd[href]"), "通知链接")
            let identity = try identity(resolve(url, link.attr("href")))
            guard try digits(row.id()) == identity.numericID else { throw invalid("列表编号与链接不一致") }
            let published = try date(text(only(row.select("span.am-list-date"), "发布时间")))
            notices.append(Notice(id: "n:" + identity.numericID, title: try text(link), publishedAt: published, originalURL: identity.url))
        }
        guard !notices.isEmpty else { throw invalid("空列表尚未通过页面结构校验") }
        return NoticePage(notices: mergeNewest(notices), next: try discoverMore(doc, url, category))
    }

    public static func parseDetail(url: URL, status: Int, html: String) throws -> Notice {
        let doc = try document(url, status, html), id = try identity(url)
        let form = try only(doc.select("form[name=_newscontent_fromname]"), "通知正文")
        let heading = try only(form.select("form[name=_newscontent_fromname] > div > h1"), "通知标题")
        guard let meta = try heading.nextElementSibling(), meta.tagName() == "div" else { throw invalid("缺少发布日期") }
        let metadata = try text(meta).replacingOccurrences(of: "\u{00a0}", with: " ")
        guard metadata.count >= 10 else { throw invalid("发布日期不完整") }
        let published = try date(String(metadata.prefix(10)))
        let body = try only(form.select("#vsb_content_100 > .v_news_content"), "正文容器")
        guard try !body.text().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !body.select("img[src]").isEmpty() else { throw invalid("正文为空") }
        try body.select("script,style,iframe,object,embed,form,input,button,svg,math").remove()
        var images: [URL] = []
        for image in try body.select("img").array() {
            guard let target = try safeResource(url, image.attr("src")) else { try image.remove(); continue }
            try image.attr("src", target.absoluteString)
            if !images.contains(target) { images.append(target) }
        }
        for anchor in try body.select("a[href]").array() {
            if let target = try safeResource(url, anchor.attr("href")) { try anchor.attr("href", target.absoluteString) }
            else { try anchor.removeAttr("href") }
        }
        var attachments: [NoticeAttachment] = []
        for anchor in try form.select("a[href]").array() {
            guard let target = try safeResource(url, anchor.attr("href")), isPortalURL(target), target.path == "/system/_content/download.jsp" else { continue }
            let q = try query(target)
            guard q["urltype"] == "news.DownloadAttachUrl" else { throw invalid("附件格式变化") }
            _ = try digits(q["owner"]); _ = try digits(q["wbfileid"])
            if !attachments.contains(where: { $0.url == target }) { attachments.append(NoticeAttachment(name: try text(anchor), url: target)) }
        }
        let clean = try SwiftSoup.clean(body.html(), url.absoluteString, Whitelist.relaxed()) ?? ""
        let plain = try SwiftSoup.parse(clean).text()
        return Notice(id: "n:" + id.numericID, title: try text(heading), publishedAt: published,
                      bodyHTML: clean, bodyText: plain, originalURL: id.url, images: images, attachments: attachments)
    }

    public static func parseMore(url: URL, status: Int, xml: String, request: MoreRequest) throws -> NoticePage {
        try checkResponse(url, status, xml)
        let parameters = try paginationParameters(request.url)
        guard try paginationParameters(url) == parameters else { throw invalid("分页响应与请求不一致") }
        guard !xml.uppercased().contains("<!DOCTYPE"), !xml.uppercased().contains("<!ENTITY") else { throw invalid("分页包含不允许的 XML 声明") }
        let doc = try SwiftSoup.parse(xml, url.absoluteString, Parser.xmlParser())
        guard doc.children().size() == 1, doc.child(0).tagName() == "data" else { throw invalid("分页不是已知 XML 格式") }
        let base = try resolve(url, parameters["pageurl"]!)
        var notices: [Notice] = []
        for item in doc.child(0).children().array() {
            guard item.tagName() == "news" else { throw invalid("分页出现未知记录") }
            let id = try identity(resolve(base, text(only(item.select("url"), "分页链接"))))
            guard try digits(item.id()) == id.numericID else { throw invalid("分页编号与链接不一致") }
            notices.append(Notice(id: "n:" + id.numericID, title: try text(only(item.select("showTitle"), "分页标题")),
                                  publishedAt: try date(text(only(item.select("showDate"), "分页日期"))), originalURL: id.url))
        }
        return NoticePage(notices: mergeNewest(notices), next: try request.advance(returnedCount: notices.count))
    }

    public static func mergeNewest(_ notices: [Notice]) -> [Notice] {
        var byID: [String: Notice] = [:]
        for notice in notices { byID[notice.id] = notice }
        return byID.values.sorted { $0.publishedAt == $1.publishedAt ? $0.id < $1.id : $0.publishedAt > $1.publishedAt }
    }

    private struct Identity { let numericID: String; let url: URL }
    private static func identity(_ url: URL) throws -> Identity {
        guard isPortalURL(url), url.path == "/xntz_content.jsp" else { throw invalid("不是校内通知原文地址") }
        let q = try query(url)
        guard q.count == 3, q["urltype"] == "news.NewsContentUrl" else { throw invalid("原文地址参数变化") }
        let id = try digits(q["wbnewsid"]), tree = try digits(q["wbtreeid"])
        let canonical = try resolve(url, "/xntz_content.jsp?urltype=news.NewsContentUrl&wbtreeid=\(tree)&wbnewsid=\(id)")
        return Identity(numericID: id, url: canonical)
    }
    private static func document(_ url: URL, _ status: Int, _ html: String) throws -> Document {
        try checkResponse(url, status, html)
        let doc = try SwiftSoup.parse(html, url.absoluteString)
        let title = try doc.title().lowercased()
        if try !doc.select("input[type=password]").isEmpty() || ["统一身份认证", "登录", "登陆", "login", "sign in"].contains(where: title.contains) { throw PortalError.authenticationRequired }
        return doc
    }
    private static func checkResponse(_ url: URL, _ status: Int, _ text: String) throws {
        if status == 401 { throw PortalError.authenticationRequired }
        if status == 403 { throw PortalError.accessDenied }
        if url.host?.lowercased() != "my.bupt.edu.cn" || ["login", "passport", "/cas/"].contains(where: url.path.lowercased().contains) { throw PortalError.authenticationRequired }
        guard isPortalURL(url), status == 200, text.utf8.count <= 5 * 1024 * 1024 else { throw invalid("响应状态、地址或大小不合法") }
    }
    static func query(_ url: URL) throws -> [String: String] {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: true) else { throw invalid("地址不合法") }
        var values: [String: String] = [:]
        for item in components.queryItems ?? [] {
            guard !item.name.isEmpty, let value = item.value, values[item.name] == nil else { throw invalid("重复或不完整的地址参数") }
            values[item.name] = value
        }
        return values
    }
    private static func digits(_ value: String?) throws -> String {
        guard let value, matches("^[1-9][0-9]{0,18}$", value) else { throw invalid("通知编号不合法") }
        return value
    }
    private static func date(_ value: String) throws -> Date {
        let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard matches("^[0-9]{4}-[0-9]{2}-[0-9]{2}$", clean) else { throw invalid("日期格式变化") }
        let format = DateFormatter(); format.locale = Locale(identifier: "en_US_POSIX")
        format.calendar = Calendar(identifier: .gregorian); format.timeZone = TimeZone(identifier: "Asia/Shanghai")
        format.dateFormat = "yyyy-MM-dd"; format.isLenient = false
        guard let date = format.date(from: clean), format.string(from: date) == clean else { throw invalid("日历日期不合法") }
        return date
    }
    private static func only(_ elements: Elements, _ field: String) throws -> Element {
        guard elements.size() == 1, let first = elements.first() else { throw invalid("缺少或重复的\(field)") }; return first
    }
    private static func text(_ element: Element) throws -> String {
        let value = try element.text().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { throw invalid("内容字段为空") }; return value
    }
    private static func resolve(_ base: URL, _ relative: String) throws -> URL {
        guard let url = URL(string: relative.trimmingCharacters(in: .whitespacesAndNewlines), relativeTo: base)?.absoluteURL else { throw invalid("相对地址不合法") }; return url
    }
    private static func safeResource(_ base: URL, _ relative: String) -> URL? {
        guard !relative.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let target = URL(string: relative, relativeTo: base)?.absoluteURL,
              target.user == nil, target.password == nil, target.host != nil,
              ["http", "https"].contains(target.scheme ?? "") else { return nil }
        return target
    }
    private static func invalid(_ message: String) -> PortalError { .invalidResponse(message) }
    private static func captures(_ pattern: String, _ input: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]) else { return [] }
        let ns = input as NSString
        return regex.matches(in: input, range: NSRange(location: 0, length: ns.length)).map { match in
            (0..<match.numberOfRanges).map { match.range(at: $0).location == NSNotFound ? "" : ns.substring(with: match.range(at: $0)) }
        }
    }
    private static func matches(_ pattern: String, _ input: String) -> Bool { !captures(pattern, input).isEmpty }

    // Parse the observed literal VSB pagination grammar without executing JavaScript.
    private static func discoverMore(_ doc: Document, _ page: URL, _ category: String) throws -> MoreRequest {
        let scripts = try doc.select("script:not([src])").array().map { $0.data() }.filter { $0.contains("/system/resource/getmore.jsp") }
        guard scripts.count == 1 else { throw invalid("分页定义缺少或重复") }
        let source = scripts[0]
        let objects = captures(#"\$\.get\(\s*['\"]/system/resource/getmore\.jsp['\"]\s*,\s*\{([^}]+)\}"#, source)
        guard objects.count == 1 else { throw invalid("分页定义格式变化") }
        var p: [String: String] = [:]
        for part in objects[0][1].split(separator: ",", omittingEmptySubsequences: false) {
            let fields = captures(#"^\s*([A-Za-z][A-Za-z0-9]*)\s*:\s*(?:'([^']*)'|"([^"]*)"|([A-Za-z_0-9]+))\s*$"#, String(part))
            guard fields.count == 1 else { throw invalid("不支持的分页属性") }
            let f = fields[0], key = f[1]
            guard p[key] == nil else { throw invalid("分页属性重复") }
            var value = !f[2].isEmpty ? f[2] : !f[3].isEmpty ? f[3] : f[4]
            if ["count", "start"].contains(key), !matches("^[0-9]+$", value) {
                let vars = captures("\\bvar\\s+" + NSRegularExpression.escapedPattern(for: value) + "\\s*=\\s*([0-9]+)\\s*;", source)
                guard vars.count == 1 else { throw invalid("分页偏移定义缺少或重复") }; value = vars[0][1]
            }
            p[key] = value
        }
        guard p["wbtreeid"] == category else { throw invalid("分页栏目不一致") }
        let next = try paginationURL(base: page, parameters: p)
        _ = try paginationParameters(next)
        return MoreRequest(url: next)
    }
    static func paginationURL(base: URL, parameters: [String: String]) throws -> URL {
        guard var parts = URLComponents(url: base, resolvingAgainstBaseURL: true), isPortalURL(base) else { throw invalid("分页来源不合法") }
        parts.path = "/system/resource/getmore.jsp"; parts.fragment = nil
        parts.queryItems = parameters.keys.sorted().map { URLQueryItem(name: $0, value: parameters[$0]) }
        guard let url = parts.url else { throw invalid("分页地址无法生成") }; return url
    }
    static func paginationParameters(_ url: URL) throws -> [String: String] {
        guard isPortalURL(url), url.path == "/system/resource/getmore.jsp" else { throw invalid("分页来源不合法") }
        let p = try query(url)
        let expected: Set<String> = ["count", "start", "wbtreeid", "wbviewid", "owner", "dbname", "tf", "titlelen", "summarylen", "newsshowtype", "hastitlepic", "mode", "ellipsismode", "pageurl"]
        guard Set(p.keys) == expected, p["dbname"] == "vsb", matches(#"^/[A-Za-z0-9_./-]+\.jsp$"#, p["pageurl"] ?? ""),
              !(p["pageurl"] ?? "").contains(".."), p["tf"] == "yyyy-MM-dd&zh", p["hastitlepic"] == "false",
              let count = Int(p["count"] ?? ""), (1...100).contains(count), let start = Int(p["start"] ?? ""), (0...Int(Int32.max)).contains(start) else { throw invalid("分页参数或范围变化") }
        for field in ["wbtreeid", "wbviewid", "owner"] { _ = try digits(p[field]) }
        for field in ["titlelen", "summarylen", "newsshowtype", "mode", "ellipsismode"] {
            guard matches("^[0-9]{1,6}$", p[field] ?? "") else { throw invalid("分页数值参数变化") }
        }
        return p
    }
}
