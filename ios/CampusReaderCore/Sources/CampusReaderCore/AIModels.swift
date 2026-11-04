import Foundation
import CryptoKit

public enum AIProvider: String, Codable, CaseIterable, Sendable { case deepSeek, compatible }

public struct SummaryConfiguration: Codable, Equatable, Sendable {
    public var provider: AIProvider = .deepSeek
    public var endpoint = "https://api.deepseek.com/chat/completions"
    public var model = "deepseek-flash"
    public var enabled = false
    public var automatic = false
    public var sharePolicies = false
    public var dailyLimit = 20
    public init() {}
    public static let `default` = SummaryConfiguration()
    public var binding: String { AIHash.text("brief-ios-v1|\(provider.rawValue)|\(endpoint)|\(model)") }
    public func validated() throws -> SummaryConfiguration {
        guard let url = URL(string: endpoint), url.scheme?.lowercased() == "https", let host = url.host,
              !host.isEmpty, url.user == nil, url.password == nil, url.fragment == nil, url.query == nil,
              url.path.hasSuffix("/chat/completions"), endpoint.count <= 1024,
              !model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, model.count <= 120,
              (1...100).contains(dailyLimit) else { throw AIError.configuration }
        if provider == .deepSeek {
            guard host.lowercased() == "api.deepseek.com", url.port == nil || url.port == 443,
                  ["/chat/completions", "/v1/chat/completions"].contains(url.path) else { throw AIError.configuration }
        }
        return self
    }
}

public enum AIError: Error, LocalizedError, Equatable {
    case configuration, missingKey, disabled, busy, quota, tooLarge, invalidJSON, invalidEvidence
    case truncated, emptyContent, rejected, http(Int), cancelled, transport, storage
    public var errorDescription: String? {
        switch self {
        case .configuration: return "请检查 HTTPS 接口地址、模型名称与每日上限。"
        case .missingKey: return "请配置当前服务的 API Key；更换接口需重新填写。"
        case .disabled: return "AI 摘要尚未启用。"
        case .busy: return "已有摘要正在生成，请稍后再试。"
        case .quota: return "已达到今日尝试上限，失败或取消的请求也计入。"
        case .tooLarge: return "内容超出本次摘要的大小限制。"
        case .invalidJSON: return "模型返回格式不完整，未保存结果；可手动重试。"
        case .invalidEvidence: return "结果缺少可核对的原文依据，未保存摘要。"
        case .truncated: return "模型输出未完成或被截断，未保存结果；可手动重试。"
        case .emptyContent: return "接口未返回可展示的摘要内容；请检查模型与接口配置。"
        case .rejected: return "模型未完成这次摘要，未保存结果。"
        case .http(let code): return "摘要服务返回 HTTP \(code)，请检查接口与额度。"
        case .cancelled: return "请求已取消，原有摘要保留；已发送请求可能计费。"
        case .transport: return "摘要服务暂时无法连接，请稍后手动重试。"
        case .storage: return "本机安全存储不可用，请解锁后重试。"
        }
    }
}

public enum AIHash {
    public static func text(_ value: String) -> String { SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined() }
}

public struct PolicyPassage: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let documentTitle: String
    public let location: String
    public let text: String
    public init(id: String, documentTitle: String, location: String, text: String) {
        self.id = id; self.documentTitle = documentTitle; self.location = location; self.text = text
    }
}
public struct SummaryCitation: Codable, Equatable, Identifiable, Sendable {
    public var id: String { AIHash.text(sourceID + quote) }
    public let sourceID: String
    public let quote: String
    public let sourceText: String
    public let documentTitle: String
    public let location: String
}
public struct SummaryClaim: Codable, Equatable, Sendable {
    public let kind: String
    public let text: String
    public let citations: [SummaryCitation]
    public let isExcerpt: Bool
}
public struct SummaryPolicyLink: Codable, Equatable, Identifiable, Sendable {
    public var id: String { notice.id + policy.id }
    public let notice: SummaryCitation
    public let policy: SummaryCitation
}
public struct CitedSummary: Codable, Equatable, Sendable {
    public let overview: SummaryClaim
    public let points: [SummaryClaim]
    public let tags: [String]
    public var policyLinks: [SummaryPolicyLink]
    public let generatedAt: Date
    public let noticeID: String
    public let bodyVersion: String
    public var snippet: String { overview.text }
}

public struct AISourceUnit: Codable, Equatable, Sendable {
    public let id: String
    public let kind: String
    public let text: String
    public let documentTitle: String
    public let location: String
}
public struct AISourceSnapshot: Sendable {
    public let noticeID: String
    public let bodyVersion: String
    public let units: [AISourceUnit]
    public static let tags = ["教学教务", "奖助评优", "志愿实践", "就业招聘", "科研竞赛", "行政公示", "生活服务"]
    public init(notice: Notice, policies: [PolicyPassage] = []) throws {
        guard !notice.bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw AIError.invalidEvidence }
        noticeID = notice.id; bodyVersion = notice.bodyVersion
        var result = [AISourceUnit(id: "N1", kind: "notice", text: String(notice.title.prefix(300)), documentTitle: notice.title, location: "标题")]
        var budget = 12_000
        for line in notice.bodyText.components(separatedBy: .newlines) {
            let cleaned = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if cleaned.isEmpty { continue }
            var remaining = cleaned[...]
            while !remaining.isEmpty && budget > 0 && result.count < 80 {
                let count = min(800, min(remaining.count, budget))
                let text = String(remaining.prefix(count)); remaining = remaining.dropFirst(count); budget -= count
                result.append(AISourceUnit(id: "N\(result.count + 1)", kind: "notice", text: text, documentTitle: notice.title, location: "正文片段 \(result.count)"))
            }
            if budget == 0 || result.count >= 80 { break }
        }
        var policyBudget = 4000; var used = Set<String>()
        for policy in policies.prefix(6) {
            guard policy.id.range(of: "^P[0-9]+_[0-9]+(?:_[0-9]+)?$", options: .regularExpression) != nil,
                  used.insert(policy.id).inserted, !policy.text.isEmpty, policyBudget > 0 else { continue }
            let text = String(policy.text.prefix(min(800, policyBudget))); policyBudget -= text.count
            result.append(AISourceUnit(id: policy.id, kind: "policy", text: text, documentTitle: String(policy.documentTitle.prefix(180)), location: String(policy.location.prefix(100))))
        }
        units = result
    }
}
