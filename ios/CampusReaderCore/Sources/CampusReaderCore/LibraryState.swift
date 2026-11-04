import Foundation

public struct RetentionPolicy: Codable, Equatable, Sendable {
    public var maximumBodies: Int
    public var maximumBodyBytes: Int
    public var readBodyHours: Double
    public var maximumMetadata: Int
    public init(maximumBodies: Int = 300, maximumBodyBytes: Int = 20 * 1024 * 1024,
                readBodyHours: Double = 24, maximumMetadata: Int = 3000) {
        self.maximumBodies = maximumBodies; self.maximumBodyBytes = maximumBodyBytes
        self.readBodyHours = readBodyHours; self.maximumMetadata = maximumMetadata
    }
}
public struct RetentionResult: Equatable, Sendable {
    public var removedBodies: Int = 0
    public var removedMetadata: Int = 0
    public init() {}
}
public struct ReadingState: Codable, Equatable, Sendable {
    public var isRead: Bool = false
    public var readAt: Date?
    public var later: Bool = false
    public var tags: [String] = []
    public init() {}
    public init(notice: Notice) { isRead = notice.isRead; readAt = notice.readAt; later = notice.later; tags = notice.tags }
}

/// One instance/file per account. Connectivity failures never mutate this state.
/// The compact identity ledger is retained when metadata/body caches are evicted.
public struct LibraryState: Codable, Sendable {
    public var notices: [Notice] = []
    public var subscriptions: [Subscription] = []
    public private(set) var stateLedger: [String: ReadingState] = [:]
    public private(set) var capacityReached = false
    public static let maximumIdentities = 20_000
    public init() {}

    @discardableResult public mutating func merge(_ incoming: [Notice], now: Date = Date()) -> [String] {
        var byID = Dictionary(notices.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        for old in notices where stateLedger[old.id] == nil { stateLedger[old.id] = ReadingState(notice: old) }
        var inserted: [String] = []
        for raw in incoming {
            guard !raw.id.isEmpty, raw.id.count <= 256, raw.title.utf8.count <= 8192,
                  raw.bodyHTML.utf8.count + raw.bodyText.utf8.count <= 10 * 1024 * 1024 else { continue }
            var notice = raw
            if stateLedger[notice.id] == nil {
                guard stateLedger.count < Self.maximumIdentities else { capacityReached = true; continue }
                stateLedger[notice.id] = ReadingState(); inserted.append(notice.id)
            }
            if let old = byID[notice.id], !notice.hasBody, old.title == notice.title, old.publishedAt == notice.publishedAt {
                // Metadata-only refresh can retain the body only while its title/date still agree.
                // A changed listing without a verified detail must not combine new facts with old text.
                notice.bodyHTML = old.bodyHTML; notice.bodyText = old.bodyText
                notice.images = old.images; notice.attachments = old.attachments
            }
            if let state = stateLedger[notice.id] {
                notice.isRead = state.isRead; notice.readAt = state.readAt
                notice.later = state.later; notice.tags = state.tags
            }
            notice.fetchedAt = now; byID[notice.id] = notice
        }
        notices = PortalParser.mergeNewest(Array(byID.values))
        return inserted
    }
    public mutating func markRead(id: String, read: Bool, now: Date = Date()) {
        guard var state = stateLedger[id] ?? notices.first(where: { $0.id == id }).map(ReadingState.init(notice:)) else { return }
        state.isRead = read; state.readAt = read ? now : nil; stateLedger[id] = state
        if let index = notices.firstIndex(where: { $0.id == id }) { notices[index].isRead = read; notices[index].readAt = state.readAt }
    }
    public mutating func setLater(id: String, later: Bool) {
        guard var state = stateLedger[id] ?? notices.first(where: { $0.id == id }).map(ReadingState.init(notice:)) else { return }
        state.later = later; stateLedger[id] = state
        if let index = notices.firstIndex(where: { $0.id == id }) { notices[index].later = later }
    }
    public mutating func setTags(id: String, tags: [String]) {
        guard var state = stateLedger[id] ?? notices.first(where: { $0.id == id }).map(ReadingState.init(notice:)) else { return }
        state.tags = Array(Set(tags.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40)) }.filter { !$0.isEmpty })).sorted().prefix(20).map { $0 }
        stateLedger[id] = state
        if let index = notices.firstIndex(where: { $0.id == id }) { notices[index].tags = state.tags }
    }
    @discardableResult public mutating func enforceRetention(policy: RetentionPolicy = RetentionPolicy(), now: Date = Date()) -> RetentionResult {
        var result = RetentionResult()
        let maxBodies = min(2000, max(0, policy.maximumBodies)), maxBytes = min(200 * 1024 * 1024, max(0, policy.maximumBodyBytes))
        let ageCleanupEnabled = policy.readBodyHours != -1
        let readHours = min(8760, max(0, policy.readBodyHours.isFinite ? policy.readBodyHours : 24))
        let cutoff = now.addingTimeInterval(-readHours * 3600)
        for i in notices.indices where ageCleanupEnabled && notices[i].hasBody && notices[i].isRead && !notices[i].later && notices[i].tags.isEmpty {
            if let readAt = notices[i].readAt, readAt <= cutoff { clearBody(at: i); result.removedBodies += 1 }
        }
        let ordered = notices.indices.filter { notices[$0].hasBody }.sorted { a, b in
            let protectedA = notices[a].later || !notices[a].tags.isEmpty, protectedB = notices[b].later || !notices[b].tags.isEmpty
            if protectedA != protectedB { return protectedA }
            if notices[a].isRead != notices[b].isRead { return !notices[a].isRead }
            return notices[a].publishedAt > notices[b].publishedAt
        }
        var count = 0, size = 0
        for i in ordered {
            let bytes = notices[i].bodyHTML.utf8.count + notices[i].bodyText.utf8.count
            if count >= maxBodies || bytes > maxBytes - size { clearBody(at: i); result.removedBodies += 1 }
            else { count += 1; size += bytes }
        }
        let maximumMetadata = min(10_000, max(1, policy.maximumMetadata))
        if notices.count > maximumMetadata {
            notices.sort { a, b in
                let pa = a.later || !a.tags.isEmpty, pb = b.later || !b.tags.isEmpty
                if pa != pb { return pa }; return a.publishedAt > b.publishedAt
            }
            result.removedMetadata = notices.count - maximumMetadata
            notices.removeLast(result.removedMetadata)
            notices = PortalParser.mergeNewest(notices)
        }
        return result
    }
    private mutating func clearBody(at index: Int) {
        notices[index].bodyHTML = ""; notices[index].bodyText = ""
        notices[index].images = [] // Retain small attachment links as metadata, not downloaded files.
    }
}
