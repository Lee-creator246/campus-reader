import Foundation
import UserNotifications
import CampusReaderCore

@MainActor final class ReaderNotifications {
    static let shared = ReaderNotifications()
    let center = UNUserNotificationCenter.current()
    private var lifecycle = 0
    private var publicationSequence: [String: Int] = [:]
    private var reminderSequence: [String: Int] = [:]

    func register() {
        let read = UNNotificationAction(identifier: "READ", title: "标为已读", options: [])
        let later = UNNotificationAction(identifier: "LATER", title: "稍后读", options: [])
        center.setNotificationCategories([UNNotificationCategory(identifier: "NOTICE", actions: [read, later], intentIdentifiers: [], options: [])])
    }
    func requestAuthorization() async -> Bool { (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false }
    private func receiptURL(_ profile: String) throws -> URL { try AppFiles.directory(profile: profile).appendingPathComponent("notification-receipts.json") }
    private func owns(_ model: AppModel, _ owner: String, _ revision: Int, _ epoch: Int) -> Bool {
        !owner.isEmpty && owner == model.profileID && owner == model.portal.profileID &&
            revision == model.sessionRevision && epoch == lifecycle && !model.previewMode
    }
    private func row(_ id: String, model: AppModel, unread: Bool) -> Notice? {
        guard model.settings.notifications, let current = model.library.notices.first(where: { $0.id == id }),
              (!unread || !current.isRead), !Recommendation.isBlocked(current, preferences: model.settings.preferences) else { return nil }
        return current
    }
    private func remove(_ identifiers: [String]) {
        guard !identifiers.isEmpty else { return }
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }
    private func identities(_ content: UNNotificationContent) -> [String] {
        if let ids = content.userInfo["notices"] as? [String], !ids.isEmpty { return ids }
        if let id = content.userInfo["notice"] as? String { return [id] }
        return []
    }

    func publish(_ incoming: [Notice], model: AppModel) async {
        let owner = model.profileID, revision = model.sessionRevision, epoch = lifecycle
        guard owns(model, owner, revision, epoch) else { return }
        let serial = publicationSequence[owner, default: 0] + 1; publicationSequence[owner] = serial
        func live() -> Bool { owns(model, owner, revision, epoch) && publicationSequence[owner] == serial }
        let settings = await center.notificationSettings()
        guard live(), settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        do {
            let file = try receiptURL(owner)
            var receipts = try AppFiles.read([String: Date].self, from: file, maximum: 128_000) ?? [:]
            receipts = receipts.filter { Date().timeIntervalSince($0.value) < 14 * 86400 }
            var seen = Set<String>()
            // Re-read current flags/content after the asynchronous system-settings lookup.
            let rows = incoming.compactMap { old -> Notice? in
                guard seen.insert(old.id).inserted, receipts[StableHash.sha256(old.id)] == nil else { return nil }
                return row(old.id, model: model, unread: true)
            }
            guard !rows.isEmpty, live() else { return }
            for notice in rows { receipts[StableHash.sha256(notice.id)] = Date() }
            receipts = Dictionary(uniqueKeysWithValues: receipts.sorted { $0.value > $1.value }.prefix(400).map { ($0.key, $0.value) })
            try AppFiles.write(receipts, to: file)
            let high = rows.contains { Recommendation.priorityReason($0, preferences: model.settings.preferences) != nil }
            let content = UNMutableNotificationContent()
            content.title = (high ? "你关注的校园消息" : "新的校园通知") + (rows.count > 4 ? " · 共 \(rows.count) 条" : "")
            content.body = rows.prefix(4).map(\.title).joined(separator: "\n")
            content.interruptionLevel = high ? .active : .passive
            if high { content.sound = .default }
            if rows.count == 1 { content.categoryIdentifier = "NOTICE" }
            content.userInfo = ["profile": owner, "notice": rows[0].id, "notices": rows.map(\.id)]
            content.threadIdentifier = owner
            let identifier = "new-" + owner + "-" + UUID().uuidString
            guard live(), model.settings.notifications else { return }
            try await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
            // Account switches may occur while the OS accepts a request. Remove only this UUID,
            // never a newer publication, if the post-submit owner/content check fails.
            guard live(), rows.allSatisfy({ old in
                guard let current = row(old.id, model: model, unread: true) else { return false }
                return current.title == old.title && current.bodyVersion == old.bodyVersion
            }), high == rows.contains(where: { Recommendation.priorityReason($0, preferences: model.settings.preferences) != nil }) else { remove([identifier]); return }
        } catch { /* No content, credentials or server responses are logged. */ }
    }

    func remind(_ notice: Notice, at date: Date, model: AppModel) async throws {
        let owner = model.profileID, revision = model.sessionRevision, epoch = lifecycle
        guard owns(model, owner, revision, epoch), date > Date(), date < Date().addingTimeInterval(366 * 86400),
              row(notice.id, model: model, unread: false) != nil else { throw PortalError.invalidResponse("请选择一年内的未来时间，并检查屏蔽规则和通知开关") }
        let key = StableHash.sha256(owner + "\n" + notice.id)
        let serial = reminderSequence[key, default: 0] + 1; reminderSequence[key] = serial
        func live() -> Bool { owns(model, owner, revision, epoch) && reminderSequence[key] == serial }
        guard await requestAuthorization() else { throw PortalError.invalidResponse("请在系统设置中允许通知") }
        guard live() else { throw PortalError.cancelled }
        let pending = await center.pendingNotificationRequests()
        guard live(), let current = row(notice.id, model: model, unread: false), date > Date() else { throw PortalError.cancelled }
        let prior = pending.filter { $0.identifier.hasPrefix("reminder-") && $0.content.userInfo["profile"] as? String == owner && identities($0.content) == [notice.id] }.map(\.identifier)
        guard pending.filter({ $0.identifier.hasPrefix("reminder-") }).count < 50 || !prior.isEmpty else { throw PortalError.invalidResponse("最多保留 50 个待提醒事项") }
        let content = UNMutableNotificationContent(); content.title = "校园事项提醒"; content.body = current.title
        content.sound = .default; content.categoryIdentifier = "NOTICE"
        content.userInfo = ["profile": owner, "notice": current.id, "notices": [current.id]]
        let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        let identifier = "reminder-" + key + "-" + UUID().uuidString
        try await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)))
        guard live(), let latest = row(notice.id, model: model, unread: false), latest.title == current.title,
              latest.bodyVersion == current.bodyVersion else { remove([identifier]); throw PortalError.cancelled }
        // Replace only older requests observed before this publication, not another task's UUID.
        remove(prior)
    }

    func reconcile(model: AppModel) async {
        let owner = model.profileID, revision = model.sessionRevision, epoch = lifecycle
        let pending = await center.pendingNotificationRequests()
        guard owns(model, owner, revision, epoch) else { return }
        func invalid(_ request: UNNotificationRequest) -> Bool {
            guard request.content.userInfo["profile"] as? String == owner else { return true }
            let ids = identities(request.content)
            guard !ids.isEmpty else { return true }
            let unread = !request.identifier.hasPrefix("reminder-")
            return ids.contains { row($0, model: model, unread: unread) == nil }
        }
        remove(pending.filter(invalid).map(\.identifier))
        let delivered = await center.deliveredNotifications()
        guard owns(model, owner, revision, epoch) else { return }
        // Remove an aggregate if any member becomes blocked/read; reposting remaining members
        // would create another alert. Those unread notices remain available in the reader.
        remove(delivered.map(\.request).filter(invalid).map(\.identifier))
    }
    func cancelAll() {
        lifecycle += 1; publicationSequence.removeAll(); reminderSequence.removeAll()
        center.removeAllPendingNotificationRequests(); center.removeAllDeliveredNotifications()
    }
}
