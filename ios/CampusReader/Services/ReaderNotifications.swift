import Foundation
import UserNotifications
import CampusReaderCore

@MainActor final class ReaderNotifications {
    static let shared = ReaderNotifications()
    let center = UNUserNotificationCenter.current()
    func register() {
        let read = UNNotificationAction(identifier: "READ", title: "标为已读", options: [])
        let later = UNNotificationAction(identifier: "LATER", title: "稍后读", options: [])
        center.setNotificationCategories([UNNotificationCategory(identifier: "NOTICE", actions: [read, later], intentIdentifiers: [], options: [])])
    }
    func requestAuthorization() async -> Bool { (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false }
    private func receiptURL(_ profile: String) throws -> URL { try AppFiles.directory(profile: profile).appendingPathComponent("notification-receipts.json") }
    func publish(_ incoming: [Notice], model: AppModel) async {
        let owner = model.profileID
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        guard owner == model.profileID, !owner.isEmpty, model.settings.notifications else { return }
        do {
            let file = try receiptURL(owner)
            var receipts = try AppFiles.read([String: Date].self, from: file, maximum: 128_000) ?? [:]
            receipts = receipts.filter { Date().timeIntervalSince($0.value) < 14 * 86400 }
            let rows = incoming.filter { !$0.isRead && !Recommendation.isBlocked($0, preferences: model.settings.preferences) && receipts[StableHash.sha256($0.id)] == nil }
            guard !rows.isEmpty else { return }
            // Save the identity receipt first: a retry must not ring twice after a partial callback.
            for notice in rows { receipts[StableHash.sha256(notice.id)] = Date() }
            receipts = Dictionary(uniqueKeysWithValues: receipts.sorted { $0.value > $1.value }.prefix(400).map { ($0.key, $0.value) })
            try AppFiles.write(receipts, to: file)
            let high = rows.contains { Recommendation.priorityReason($0, preferences: model.settings.preferences) != nil }
            let content = UNMutableNotificationContent()
            content.title = high ? "你关注的校园消息" : "新的校园通知"
            content.body = rows.prefix(4).map(\.title).joined(separator: "\n")
            content.interruptionLevel = high ? .active : .passive
            if high { content.sound = .default }
            if rows.count == 1 { content.categoryIdentifier = "NOTICE" }
            content.userInfo = ["profile": owner, "notice": rows[0].id]
            content.threadIdentifier = owner
            guard owner == model.profileID, model.settings.notifications else { return }
            try await center.add(UNNotificationRequest(identifier: "new-" + owner, content: content, trigger: nil))
        } catch { /* No content or credential logging; refresh history already records the data sync. */ }
    }
    func remind(_ notice: Notice, at date: Date, model: AppModel) async throws {
        guard date > Date(), date < Date().addingTimeInterval(366 * 86400), !Recommendation.isBlocked(notice, preferences: model.settings.preferences) else { throw PortalError.invalidResponse("请选择一年内的未来时间，并检查屏蔽规则") }
        let owner = model.profileID
        guard await requestAuthorization() else { throw PortalError.invalidResponse("请在系统设置中允许通知") }
        let pending = await center.pendingNotificationRequests()
        let identifier = "reminder-" + StableHash.sha256(owner + notice.id)
        guard pending.count < 50 || pending.contains(where: { $0.identifier == identifier }) else { throw PortalError.invalidResponse("最多保留 50 个待提醒事项") }
        guard owner == model.profileID else { throw PortalError.cancelled }
        let content = UNMutableNotificationContent(); content.title = "校园事项提醒"; content.body = notice.title
        content.sound = .default; content.categoryIdentifier = "NOTICE"; content.userInfo = ["profile": owner, "notice": notice.id]
        let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        try await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)))
    }
    func reconcile(model: AppModel) async {
        let owner = model.profileID
        let pending = await center.pendingNotificationRequests()
        guard owner == model.profileID else { return }
        let removed = pending.filter { request in
            guard request.content.userInfo["profile"] as? String == owner else { return true }
            guard let id = request.content.userInfo["notice"] as? String, let row = model.library.notices.first(where: { $0.id == id }) else { return true }
            return Recommendation.isBlocked(row, preferences: model.settings.preferences) || !model.settings.notifications
        }.map(\.identifier)
        center.removePendingNotificationRequests(withIdentifiers: removed)
        let delivered = await center.deliveredNotifications()
        guard owner == model.profileID else { return }
        let hidden = delivered.filter { notification in
            let info = notification.request.content.userInfo
            guard info["profile"] as? String == owner, let id = info["notice"] as? String,
                  let row = model.library.notices.first(where: { $0.id == id }) else { return true }
            return row.isRead || Recommendation.isBlocked(row, preferences: model.settings.preferences) || !model.settings.notifications
        }.map { $0.request.identifier }
        center.removeDeliveredNotifications(withIdentifiers: hidden)
    }
    func cancelAll() { center.removeAllPendingNotificationRequests(); center.removeAllDeliveredNotifications() }
}
