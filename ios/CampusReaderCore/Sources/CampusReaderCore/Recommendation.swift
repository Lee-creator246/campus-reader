import Foundation

public struct Preferences: Codable, Equatable, Sendable {
    public var keywords: [String] = []
    public var blocked: [String] = []
    public var topics: [String] = []
    public var learningEnabled = true
    public var themeAlerts = true
    public var audience = ""
    public var feedback: [String: Int] = [:]
    public init() {}
    public static func terms(_ input: String) -> [String] {
        var result: [String] = []
        for item in input.components(separatedBy: CharacterSet(charactersIn: "\n,，;；")) {
            let term = String(item.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
            if !term.isEmpty, !result.contains(where: { $0.caseInsensitiveCompare(term) == .orderedSame }) { result.append(term) }
            if result.count >= 100 { break }
        }
        return result
    }
    public mutating func recordFeedback(for notice: Notice, more: Bool) {
        guard learningEnabled else { return }
        for topic in Recommendation.topics(for: notice) { feedback[topic] = min(4, max(-4, (feedback[topic] ?? 0) + (more ? 1 : -1))) }
    }
}
public enum ReaderMode: String, Codable, CaseIterable, Sendable {
    case recommended, latest, unread, later
    public var title: String {
        switch self { case .recommended: return "为你推荐"; case .latest: return "最新"; case .unread: return "未读"; case .later: return "稍后读" }
    }
}
public enum Recommendation {
    public static let allTopics = ["教学教务", "奖助评优", "志愿实践", "就业招聘", "科研竞赛", "行政公示", "生活服务"]
    private static let vocabulary: [String: [String]] = [
        "教学教务": ["课程", "选课", "考试", "教学", "学籍", "教务"],
        "奖助评优": ["奖学金", "助学", "评优", "评选", "先进"],
        "志愿实践": ["志愿", "实践", "支教", "公益", "社会服务"],
        "就业招聘": ["就业", "招聘", "实习", "宣讲", "双选"],
        "科研竞赛": ["科研", "竞赛", "大赛", "论文", "创新", "挑战赛"],
        "行政公示": ["任职", "任免", "公示", "干部", "行政"],
        "生活服务": ["食堂", "宿舍", "停水", "停电", "校车", "图书馆", "体育", "运动"]
    ]
    private static func contains(_ text: String, _ term: String) -> Bool {
        !term.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && text.range(of: term, options: [.caseInsensitive, .diacriticInsensitive]) != nil
    }
    public static func topics(for notice: Notice) -> [String] {
        let content = notice.title + "\n" + notice.bodyText
        return allTopics.filter { vocabulary[$0, default: []].contains { contains(content, $0) } }
    }
    public static func isBlocked(_ notice: Notice, preferences: Preferences) -> Bool {
        let content = notice.title + "\n" + notice.bodyText
        return preferences.blocked.prefix(100).contains { contains(content, $0) }
    }
    public static func priorityReason(_ notice: Notice, preferences: Preferences) -> String? {
        guard !isBlocked(notice, preferences: preferences) else { return nil }
        if let term = preferences.keywords.prefix(100).first(where: { contains(notice.title, $0) }) { return "标题匹配关注词：\(term)" }
        if let term = preferences.keywords.prefix(100).first(where: { contains(notice.bodyText, $0) }) { return "正文匹配关注词：\(term)" }
        if preferences.themeAlerts, let topic = topics(for: notice).first(where: preferences.topics.contains) { return "关注主题：\(topic)" }
        return nil
    }
    public static func reason(_ notice: Notice, preferences: Preferences) -> String? {
        if let priority = priorityReason(notice, preferences: preferences) { return priority }
        if let topic = topics(for: notice).first(where: preferences.topics.contains) { return "关注主题：\(topic)" }
        if preferences.learningEnabled, topics(for: notice).contains(where: { preferences.feedback[$0, default: 0] > 0 }) { return "你选择了多推荐此类" }
        return nil
    }
    public static func score(_ notice: Notice, preferences: Preferences, now: Date = Date()) -> Int {
        guard !isBlocked(notice, preferences: preferences) else { return Int.min }
        var value = 0
        if preferences.keywords.contains(where: { contains(notice.title, $0) }) { value += 70 }
        else if preferences.keywords.contains(where: { contains(notice.bodyText, $0) }) { value += 35 }
        let categories = topics(for: notice)
        value += min(40, 20 * categories.filter(preferences.topics.contains).count)
        if preferences.learningEnabled { value += min(12, max(-12, categories.reduce(0) { $0 + preferences.feedback[$1, default: 0] * 3 })) }
        if !preferences.audience.isEmpty, contains(notice.title + notice.bodyText, preferences.audience) { value += 5 }
        let age = now.timeIntervalSince(notice.publishedAt)
        if age >= 0 && age <= 3 * 86400 { value += 6 } else if age >= 0 && age <= 7 * 86400 { value += 3 }
        return value
    }
    public static func visible(_ notices: [Notice], preferences: Preferences, mode: ReaderMode = .latest,
                               topic: String? = nil, query: String = "", now: Date = Date()) -> [Notice] {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let result = notices.filter { notice in
            guard !isBlocked(notice, preferences: preferences), mode != .unread || !notice.isRead,
                  mode != .later || notice.later else { return false }
            if let topic, !topic.isEmpty, !topics(for: notice).contains(topic), !notice.tags.contains(topic) { return false }
            return term.isEmpty || contains(notice.title + "\n" + notice.bodyText + "\n" + notice.tags.joined(separator: " "), term)
        }
        return result.sorted {
            if mode == .recommended {
                let a = score($0, preferences: preferences, now: now), b = score($1, preferences: preferences, now: now)
                if a != b { return a > b }
            }
            return $0.publishedAt == $1.publishedAt ? $0.id < $1.id : $0.publishedAt > $1.publishedAt
        }
    }
}
