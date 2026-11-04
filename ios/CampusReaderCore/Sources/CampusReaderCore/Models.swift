import Foundation
import CryptoKit

public enum StableHash {
    public static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

public enum PortalError: Error, LocalizedError, Equatable, Sendable {
    case authenticationRequired, accessDenied, transport, invalidResponse(String), cancelled
    public var errorDescription: String? {
        switch self {
        case .authenticationRequired: return "门户登录已过期，请重新登录；本机缓存与阅读状态仍保留。"
        case .accessDenied: return "门户拒绝访问，请检查账号权限。"
        case .transport: return "当前网络无法访问门户，请连接校园网络或校园 VPN 后重试。"
        case .invalidResponse(let message): return "门户页面未通过校验：\(message)"
        case .cancelled: return "操作已取消。"
        }
    }
}

public struct NoticeAttachment: Codable, Equatable, Identifiable, Sendable {
    public var name: String
    public var url: URL
    public var id: String { url.absoluteString }
    public init(name: String, url: URL) { self.name = name; self.url = url }
}

public struct Notice: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var source: String
    public var title: String
    public var publishedAt: Date
    public var bodyHTML: String
    public var bodyText: String
    public var originalURL: URL
    public var images: [URL]
    public var attachments: [NoticeAttachment]
    public var isRead: Bool
    public var readAt: Date?
    public var later: Bool
    public var tags: [String]
    public var fetchedAt: Date
    public init(id: String, source: String = "campus:portal:notices", title: String, publishedAt: Date,
                bodyHTML: String = "", bodyText: String = "", originalURL: URL, images: [URL] = [],
                attachments: [NoticeAttachment] = [], isRead: Bool = false, readAt: Date? = nil,
                later: Bool = false, tags: [String] = [], fetchedAt: Date = Date()) {
        self.id = id; self.source = source; self.title = title; self.publishedAt = publishedAt
        self.bodyHTML = bodyHTML; self.bodyText = bodyText; self.originalURL = originalURL
        self.images = images; self.attachments = attachments; self.isRead = isRead; self.readAt = readAt
        self.later = later; self.tags = tags; self.fetchedAt = fetchedAt
    }
    public var bodyVersion: String {
        StableHash.sha256(title + "\n" + String(publishedAt.timeIntervalSince1970) + "\n" + bodyHTML + "\n" + bodyText)
    }
    public var hasBody: Bool { !bodyHTML.isEmpty || !bodyText.isEmpty }
}

public struct NoticePage: Sendable {
    public var notices: [Notice]
    public var next: MoreRequest?
    public init(notices: [Notice], next: MoreRequest? = nil) { self.notices = notices; self.next = next }
}

public struct MoreRequest: Codable, Equatable, Sendable {
    public var url: URL
    public init(url: URL) { self.url = url }
    public func advance(returnedCount: Int) throws -> MoreRequest? {
        var parameters = try PortalParser.paginationParameters(url)
        guard let count = Int(parameters["count"] ?? ""), let start = Int(parameters["start"] ?? ""),
              returnedCount >= 0, returnedCount <= count else { throw PortalError.invalidResponse("分页条数不合法") }
        if returnedCount < count { return nil }
        guard start <= Int(Int32.max) - count else { throw PortalError.invalidResponse("分页偏移超出范围") }
        parameters["start"] = String(start + count)
        return MoreRequest(url: try PortalParser.paginationURL(base: url, parameters: parameters))
    }
}

public struct Subscription: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var title: String
    public var url: URL
    public init(url: URL, title: String = "", id: String? = nil) {
        self.url = url; self.title = title; self.id = id ?? StableHash.sha256(url.absoluteString)
    }
}
