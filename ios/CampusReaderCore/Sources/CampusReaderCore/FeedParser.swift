import Foundation
import SwiftSoup

/// RSS/Atom identities are scoped to the subscription; this parser has no cookie/session access.
public enum FeedParser {
    public static func parse(url: URL, xml: String) throws -> [Notice] {
        guard safeURL(url), url.scheme == "https", xml.utf8.count <= 5 * 1024 * 1024,
              !xml.uppercased().contains("<!DOCTYPE"), !xml.uppercased().contains("<!ENTITY") else { throw PortalError.invalidResponse("订阅地址或 XML 不合法") }
        let doc = try SwiftSoup.parse(xml, url.absoluteString, Parser.xmlParser())
        guard doc.children().size() == 1 else { throw PortalError.invalidResponse("订阅根节点变化") }
        let root = doc.child(0)
        let atom = root.tagName().lowercased() == "feed"
        let items: [Element]
        if atom { items = root.children().array().filter { $0.tagName().lowercased() == "entry" } }
        else if root.tagName().lowercased() == "rss", let channel = root.children().array().first(where: { $0.tagName().lowercased() == "channel" }) {
            items = channel.children().array().filter { $0.tagName().lowercased() == "item" }
        } else { throw PortalError.invalidResponse("仅支持 RSS 2.0 或 Atom 订阅") }
        let source = "rss:" + StableHash.sha256(url.absoluteString)
        var notices: [Notice] = []
        for item in items.prefix(500) {
            let title = try field(item, ["title"])
            guard !title.isEmpty, title.utf8.count <= 8192 else { continue }
            let identity = try field(item, atom ? ["id"] : ["guid"])
            let links = item.children().array().filter { $0.tagName().lowercased() == "link" }
            let link: URL?
            if atom {
                let alternate = try links.first { let rel = try $0.attr("rel"); return rel.isEmpty || rel == "alternate" }
                link = try alternate.flatMap { URL(string: try $0.attr("href"), relativeTo: url)?.absoluteURL }
            } else {
                link = try links.first.flatMap { URL(string: try $0.text(), relativeTo: url)?.absoluteURL }
            }
            let articleURL = link.flatMap { safeURL($0) ? $0 : nil }
            // Do not guess a title/body hash identity: edits would silently duplicate old entries.
            guard !identity.isEmpty || articleURL != nil else { continue }
            let stable = identity.isEmpty ? "url:" + articleURL!.absoluteString : "guid:" + identity
            let content = try field(item, atom ? ["content", "summary"] : ["content:encoded", "description"])
            let contentBase = articleURL ?? url
            let htmlDoc = try SwiftSoup.parse(content, contentBase.absoluteString)
            try htmlDoc.select("script,style,iframe,object,embed,form,input,button,svg,math").remove()
            var images: [URL] = []
            for img in try htmlDoc.select("img[src]").array() {
                guard let target = URL(string: try img.attr("src"), relativeTo: contentBase)?.absoluteURL, safeURL(target) else { try img.remove(); continue }
                try img.attr("src", target.absoluteString); if !images.contains(target) { images.append(target) }
            }
            for a in try htmlDoc.select("a[href]").array() {
                if let target = URL(string: try a.attr("href"), relativeTo: contentBase)?.absoluteURL, safeURL(target) { try a.attr("href", target.absoluteString) }
                else { try a.removeAttr("href") }
            }
            let bodyHTML = try SwiftSoup.clean(htmlDoc.body()?.html() ?? "", contentBase.absoluteString, Whitelist.relaxed()) ?? ""
            let bodyText = try SwiftSoup.parse(bodyHTML).text()
            let dateString = try field(item, atom ? ["published", "updated"] : ["pubdate", "dc:date"])
            let published = parseDate(dateString) ?? Date(timeIntervalSince1970: 0)
            var attachments: [NoticeAttachment] = []
            for element in item.children().array() {
                let name = element.tagName().lowercased()
                let isAttachment = name == "enclosure" || (name == "link" && (try? element.attr("rel")) == "enclosure")
                guard isAttachment, let target = URL(string: try element.attr(name == "enclosure" ? "url" : "href"), relativeTo: url)?.absoluteURL, safeURL(target) else { continue }
                attachments.append(NoticeAttachment(name: target.lastPathComponent.isEmpty ? "附件" : target.lastPathComponent, url: target))
            }
            notices.append(Notice(id: "r:" + StableHash.sha256(source + "\n" + stable), source: source, title: title,
                                  publishedAt: published, bodyHTML: bodyHTML, bodyText: bodyText,
                                  originalURL: articleURL ?? url, images: images, attachments: attachments))
        }
        if !items.isEmpty && notices.isEmpty { throw PortalError.invalidResponse("订阅条目缺少稳定编号或有效链接") }
        return PortalParser.mergeNewest(notices)
    }
    private static func safeURL(_ url: URL) -> Bool {
        ["http", "https"].contains(url.scheme?.lowercased() ?? "") && url.host != nil && url.user == nil && url.password == nil
    }
    private static func field(_ element: Element, _ names: [String]) throws -> String {
        for name in names {
            if let child = element.children().array().first(where: { $0.tagName().lowercased() == name }) {
                let value = try child.text().trimmingCharacters(in: .whitespacesAndNewlines)
                if !value.isEmpty { return value }
            }
        }
        return ""
    }
    private static func parseDate(_ value: String) -> Date? {
        let iso = ISO8601DateFormatter()
        if let date = iso.date(from: value) { return date }
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = iso.date(from: value) { return date }
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(secondsFromGMT: 0); formatter.isLenient = false
        for pattern in ["EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd"] {
            formatter.dateFormat = pattern; if let date = formatter.date(from: value) { return date }
        }
        return nil
    }
}
