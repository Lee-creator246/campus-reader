import SwiftUI
import CampusReaderCore

struct ReaderSettings: Codable {
    var preferences = Preferences()
    var retention = RetentionPolicy()
    var refreshOnOpen = true
    var backgroundRefresh = true
    var notifications = true
    var intervalMinutes = 30
    var hasSynced = false
    var onboardingDone = false
}
struct RefreshRecord: Codable, Identifiable {
    var id = UUID().uuidString
    var date = Date()
    var background: Bool
    var saved: Int
    var inserted: Int
    var failed: Int
    var message: String
}

@MainActor final class AppModel: ObservableObject {
    static let shared = AppModel()
    let portal = PortalService()
    let ai = AIService()
    let policies = PolicyLibraryStore()
    @Published private(set) var library = LibraryState()
    @Published var settings = ReaderSettings()
    @Published private(set) var history: [RefreshRecord] = []
    @Published var status = ""
    @Published private(set) var refreshing = false
    @Published private(set) var ready = false
    @Published var showLogin = false
    @Published var showInterests = false
    @Published var selectedNotice: Notice?
    @Published var mode = ReaderMode.recommended
    @Published var topic: String?
    @Published var query = ""
    @Published var source: String?
    @Published var tag: String?
    @Published private(set) var nextPage: MoreRequest?
    private(set) var profileID = ""
    private var revision = 0
    private var writable = false
    private(set) var previewMode = false
    var lastRefreshSucceeded = false
    private var initialized = false
    private var task: Task<Void, Never>?

    init() { policies.onChange = { [weak self] in self?.ai.policiesChanged() } }
    var notices: [Notice] {
        let values = library.notices.filter { (source == nil || $0.source == source) && (tag == nil || $0.tags.contains(tag!)) }
        return Recommendation.visible(values, preferences: settings.preferences, mode: mode, topic: topic, query: query)
    }
    var allTags: [String] { Array(Set(library.notices.flatMap(\.tags))).sorted() }
    var unreadCount: Int { library.notices.filter { !$0.isRead && !Recommendation.isBlocked($0, preferences: settings.preferences) }.count }
    func start(background: Bool = false) async {
        guard !initialized else { return }; initialized = true
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--ui-demo") { loadDemo(); return }
        if ProcessInfo.processInfo.arguments.contains("--ui-testing") { ready = true; return }
        #endif
        await portal.restore()
        if portal.hasAccount { activate(); if settings.refreshOnOpen && !background { await refresh() } }
        ready = true
        BackgroundCoordinator.shared.model = self
        BackgroundCoordinator.shared.schedule(enabled: settings.backgroundRefresh, minutes: settings.intervalMinutes)
    }
    func authenticated() {
        activate(); showLogin = false
        if !settings.onboardingDone { showInterests = true }
        task?.cancel(); task = Task { await refresh() }
    }
    func accountSelectionChanged() {
        guard !previewMode, !profileID.isEmpty, profileID != portal.profileID else { return }
        revision += 1; task?.cancel(); refreshing = false; selectedNotice = nil
        ai.activate(profile: ""); profileID = ""; writable = false
        library = LibraryState(); history = []; nextPage = nil
        ReaderNotifications.shared.cancelAll()
    }
    func activate() {
        let next = portal.profileID
        guard !next.isEmpty, next != profileID else { return }
        revision += 1; task?.cancel(); refreshing = false; profileID = next
        library = LibraryState(); settings = ReaderSettings(); history = []; nextPage = nil; selectedNotice = nil; writable = false
        ai.activate(profile: next); policies.activate(profile: next)
        do {
            let dir = try AppFiles.directory(profile: next)
            library = try AppFiles.read(LibraryState.self, from: dir.appendingPathComponent("library.json")) ?? LibraryState()
            settings = try AppFiles.read(ReaderSettings.self, from: dir.appendingPathComponent("preferences.json")) ?? ReaderSettings()
            history = try AppFiles.read([RefreshRecord].self, from: dir.appendingPathComponent("refresh-history.json"), maximum: 256_000) ?? []
            writable = true; status = ""
        } catch { status = "本机数据暂时无法读取，已保留原文件。请解锁设备后重试，避免覆盖已有记录。" }
    }
    private func owns(_ owner: String, _ token: Int) -> Bool { owner == profileID && token == revision && (previewMode || owner == portal.profileID) }
    private func persist() throws {
        guard writable else { throw CocoaError(.fileWriteNoPermission) }
        ai.reconcile(library.notices.filter { $0.hasBody && !Recommendation.isBlocked($0, preferences: settings.preferences) })
        if previewMode { return }
        let dir = try AppFiles.directory(profile: profileID)
        try AppFiles.write(library, to: dir.appendingPathComponent("library.json"))
        try AppFiles.write(settings, to: dir.appendingPathComponent("preferences.json"))
        try AppFiles.write(Array(history.prefix(200)), to: dir.appendingPathComponent("refresh-history.json"))
    }
    func saveSettings() {
        do { try persist(); status = "设置已保存" } catch { status = "设置保存失败，请解锁设备后重试。" }
        BackgroundCoordinator.shared.schedule(enabled: settings.backgroundRefresh, minutes: settings.intervalMinutes)
        Task { await ReaderNotifications.shared.reconcile(model: self) }
    }
    func finishInterests() { settings.onboardingDone = true; saveSettings(); showInterests = false }
    func refresh(more: Bool = false, background: Bool = false) async {
        guard !refreshing, !previewMode, writable, portal.hasAccount else { return }
        if more && nextPage == nil { return }
        let owner = profileID, token = revision
        refreshing = true; lastRefreshSucceeded = false; status = more ? "正在加载更多…" : "正在刷新…"
        defer { if owns(owner, token) { refreshing = false } }
        var saved = 0, inserted: [String] = [], failures = 0
        do {
            let page = try await portal.fetchList(more ? nextPage : nil)
            guard owns(owner, token) else { return }
            let wasBaseline = settings.hasSynced
            var values: [Notice] = []
            for item in page.notices {
                try Task.checkCancellation(); guard owns(owner, token) else { return }
                if let old = library.notices.first(where: { $0.id == item.id }), old.hasBody, old.title == item.title, old.publishedAt == item.publishedAt { values.append(item); continue }
                // Background processing has a short OS budget. Metadata can be fetched again later.
                if background && saved >= 3 { values.append(item); continue }
                do { values.append(try await portal.fetchDetail(item)); saved += 1 }
                catch PortalError.authenticationRequired { throw PortalError.authenticationRequired }
                catch is CancellationError { throw CancellationError() }
                catch { values.append(item); failures += 1 }
            }
            guard owns(owner, token) else { return }
            inserted = library.merge(values, now: Date()); _ = library.enforceRetention(policy: settings.retention, now: Date())
            if failures == 0 { nextPage = page.next }
            settings.hasSynced = true
            status = library.capacityReached ? "本机阅读身份记录已满，已停止加入新的通知，已有状态保留。" : "已更新 \(values.count) 条 · 新增 \(inserted.count) 条" + (failures > 0 ? " · \(failures) 条正文待重试" : "")
            history.insert(RefreshRecord(background: background, saved: values.count, inserted: inserted.count, failed: failures, message: library.capacityReached ? "身份记录容量已满，新增通知未全部保存" : (failures == 0 ? "成功" : "正文部分未完成")), at: 0)
            history = Array(history.filter { Date().timeIntervalSince($0.date) < 14 * 86400 }.prefix(200))
            try persist(); lastRefreshSucceeded = failures == 0 && !library.capacityReached
            if wasBaseline && settings.notifications {
                let added = library.notices.filter { inserted.contains($0.id) }
                await ReaderNotifications.shared.publish(added, model: self)
            }
            enqueueSummaries(newIDs: inserted)
        } catch {
            guard owns(owner, token) else { return }
            status = error is CancellationError ? "刷新已暂停，已有内容保留。" : error.localizedDescription
            history.insert(RefreshRecord(background: background, saved: saved, inserted: inserted.count, failed: failures, message: status), at: 0)
            history = Array(history.prefix(200)); try? persist()
            // Never open login UI or repeatedly try passwords in a background task.
        }
    }
    func enqueueSummaries(newIDs: [String] = []) {
        guard !previewMode else { return }
        let candidates = newIDs.isEmpty ? Array(notices.prefix(10)) : library.notices.filter { newIDs.contains($0.id) }.prefix(20).map { $0 }
        for notice in candidates where notice.hasBody && !Recommendation.isBlocked(notice, preferences: settings.preferences) {
            ai.enqueue([notice], policies: policies.passages(for: notice), newNotices: !newIDs.isEmpty)
        }
    }
    func ensureBody(_ notice: Notice) async {
        guard !notice.hasBody, !previewMode else { return }; let owner = profileID, token = revision
        do {
            let detail = try await portal.fetchDetail(notice)
            guard owns(owner, token) else { return }; _ = library.merge([detail], now: Date()); try persist()
        } catch { if owns(owner, token) { status = error.localizedDescription } }
    }
    func read(_ id: String, _ value: Bool) { library.markRead(id: id, read: value, now: Date()); saveMutation() }
    func later(_ id: String, _ value: Bool) { library.setLater(id: id, later: value); saveMutation() }
    func tags(_ id: String, _ text: String) { library.setTags(id: id, tags: text.components(separatedBy: CharacterSet(charactersIn: ",，\n")).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }); saveMutation() }
    private func saveMutation() { do { try persist() } catch { status = "保存失败，阅读操作尚未持久保存。" }; Task { await ReaderNotifications.shared.reconcile(model: self) } }
    func feedback(_ notice: Notice, more: Bool) {
        for topic in Recommendation.topics(for: notice) {
            settings.preferences.feedback[topic] = min(4, max(-4, (settings.preferences.feedback[topic] ?? 0) + (more ? 1 : -1)))
        }; saveSettings()
    }
    func cleanCache() {
        _ = library.enforceRetention(policy: settings.retention, now: Date()); saveMutation(); status = "已按当前规则清理正文，阅读状态与标签保留。"
    }
    func addFeed(_ text: String) async {
        guard let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), PublicDownloader.allowed(url), library.subscriptions.count < 30 else { status = "请输入有效 HTTPS 订阅地址，最多 30 个。"; return }
        let owner = profileID, token = revision
        do {
            let data = try await PublicDownloader.data(url, maximum: 3 * 1_024 * 1_024)
            guard let xml = String(data: data, encoding: .utf8) else { throw PortalError.invalidResponse("订阅需使用 UTF-8") }
            let parsed = try FeedParser.parse(url: url, xml: xml)
            guard owns(owner, token) else { return }
            let subscription = Subscription(url: url, title: url.host ?? "订阅")
            if !library.subscriptions.contains(where: { $0.id == subscription.id }) { library.subscriptions.append(subscription) }
            _ = library.merge(parsed, now: Date()); _ = library.enforceRetention(policy: settings.retention, now: Date()); try persist(); status = "订阅已更新"
        } catch { if owns(owner, token) { status = error.localizedDescription } }
    }
    func removeFeed(_ id: String) { library.subscriptions.removeAll { $0.id == id }; saveMutation() }
    func logout() async {
        revision += 1; task?.cancel(); refreshing = false; selectedNotice = nil
        ReaderNotifications.shared.cancelAll(); await portal.logout()
        profileID = ""; ai.activate(profile: ""); library = LibraryState(); settings = ReaderSettings(); history = []; writable = false; nextPage = nil; showLogin = false
    }
    #if DEBUG
    private func loadDemo() {
        previewMode = true; profileID = StableHash.sha256("synthetic-ui-account"); writable = true; ready = true
        ai.activate(profile: profileID); policies.activate(profile: profileID)
        let one = Notice(id: "n:1001", title: "志愿服务报名通知", publishedAt: Date(), bodyText: "面向在校学生开放报名。请在规定时间内提交报名材料。本文是界面测试的合成内容，不是学校真实通知。", originalURL: URL(string: "https://example.invalid/notice/1001")!)
        let two = Notice(id: "n:1002", title: "校园图书馆开放时间安排", publishedAt: Date().addingTimeInterval(-86400), bodyText: "本条用于测试已读、稍后读与标签的独立状态。", originalURL: URL(string: "https://example.invalid/notice/1002")!, isRead: true, readAt: Date(), later: true, tags: ["生活"])
        _ = library.merge([one, two], now: Date()); library.markRead(id: two.id, read: true, now: Date()); library.setLater(id: two.id, later: true); library.setTags(id: two.id, tags: two.tags)
        settings.onboardingDone = true; status = "合成数据预览 · 不进行校园或 AI 网络请求"
    }
    #endif
}
