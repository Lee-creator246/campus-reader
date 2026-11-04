import Foundation

/// Persist this ledger successfully before a request is sent. Failure/cancellation never refunds it.
public struct AIQuotaLedger: Codable, Sendable {
    public private(set) var day = ""
    public private(set) var used = 0
    public private(set) var attempted: Set<String> = []
    public init() {}
    public func permitsAutomatic(_ identity: String) -> Bool { !attempted.contains(identity) && attempted.count < 5000 }
    public mutating func reserve(identity: String, limit: Int, automatic: Bool, now: Date = Date()) throws {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(identifier: "Asia/Shanghai"); formatter.dateFormat = "yyyy-MM-dd"
        let today = formatter.string(from: now)
        if day.isEmpty || today > day { day = today; used = 0 }
        if automatic && !permitsAutomatic(identity) { throw AIError.rejected }
        guard used < max(1, min(100, limit)) else { throw AIError.quota }
        used += 1
        // At capacity, manual requests remain possible; automatic retries fail closed.
        if attempted.count < 5000 { attempted.insert(identity) }
    }
}
