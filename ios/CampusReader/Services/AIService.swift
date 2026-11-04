import Foundation
import SwiftUI
import UIKit
import CampusReaderCore

@MainActor final class AIService: ObservableObject {
    @Published private(set) var configuration = SummaryConfiguration.default
    @Published private(set) var status = "AI 初始关闭；配置并授权后可自动摘要。"
    @Published private(set) var isBusy = false
    @Published private(set) var cacheRevision = 0
    private struct Entry: Codable { var summary: CitedSummary; var binding: String }
    private struct Queued: Codable, Equatable { var id: String; var version: String }
    private struct State: Codable {
        var configuration = SummaryConfiguration.default
        var ledger = AIQuotaLedger()
        var cache: [String: Entry] = [:]
        var queue: [Queued] = []
    }
    private var state = State()
    private var profile = ""
    private var generation = 0
    private var storageOK = true
    private var activeNotice: Notice?
    private var transport: AITransport?
    private var pumpTask: Task<Void, Never>?
    private var currentNotices: [String: Notice] = [:]
    private var passages: [String: [PolicyPassage]] = [:]
    private var fileURL: URL? {
        guard !profile.isEmpty else { return nil }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("AISummaries", isDirectory: true).appendingPathComponent(AIHash.text(profile) + ".json")
    }
    func activate(profile: String) {
        if profile == self.profile && storageOK { return }
        invalidate(); self.profile = profile; state = State(); currentNotices = [:]; passages = [:]; storageOK = true
        if let fileURL, FileManager.default.fileExists(atPath: fileURL.path) {
            do {
                let values = try fileURL.resourceValues(forKeys: [.fileSizeKey]); guard (values.fileSize ?? 0) < 8 * 1024 * 1024 else { throw AIError.tooLarge }
                state = try JSONDecoder().decode(State.self, from: Data(contentsOf: fileURL))
                guard state.cache.count <= 300, state.queue.count <= 60 else { throw AIError.storage }
                _ = try state.configuration.validated()
            } catch { state = State(); storageOK = false; status = "摘要存储暂不可读，已停止发送；解锁后重新打开。" }
        }
        configuration = state.configuration; cacheRevision += 1
        if storageOK { status = configuration.automatic ? "自动摘要已开启，等待可用正文。" : "可在 AI 设置中开启自动摘要。" }
    }
    func configure(_ next: SummaryConfiguration, key: String?) throws {
        guard storageOK, !profile.isEmpty else { throw AIError.storage }
        let next = try next.validated(); let cleaned = key?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !cleaned.contains("\n"), !cleaned.contains("\r"), cleaned.count <= 1024 else { throw AIError.configuration }
        let keyName = credentialKey(next)
        if next.enabled {
            if cleaned.isEmpty {
                guard next.endpoint == configuration.endpoint, try KeychainStore.read(keyName) != nil else { throw AIError.missingKey }
            } else { try KeychainStore.write(Data(cleaned.utf8), key: keyName) }
        } else if !cleaned.isEmpty { try KeychainStore.write(Data(cleaned.utf8), key: keyName) }
        invalidate(); state.configuration = next; state.queue = []; passages = [:]
        try save(); configuration = next; cacheRevision += 1
        status = next.enabled ? (next.automatic ? "自动摘要已开启；请求次数在本机计数。" : "已启用手动摘要。") : "AI 已关闭，已有有效摘要仍可查看。"
    }
    func cached(_ notice: Notice) -> CitedSummary? {
        guard let entry = state.cache[notice.id], entry.binding == configuration.binding,
              entry.summary.noticeID == notice.id, entry.summary.bodyVersion == notice.bodyVersion,
              !notice.bodyText.isEmpty else { return nil }
        return entry.summary
    }
    func enqueue(_ notices: [Notice], policies: [PolicyPassage] = [], newNotices: Bool = false) {
        guard storageOK, !profile.isEmpty, configuration.enabled, configuration.automatic else { return }
        var changed = false
        for notice in notices.prefix(newNotices ? 20 : 10) {
            if let activeNotice, activeNotice.id == notice.id, activeNotice.bodyVersion != notice.bodyVersion { invalidate() }
            currentNotices[notice.id] = notice; passages[notice.id] = policies
            guard !notice.bodyText.isEmpty, cached(notice) == nil, state.ledger.permitsAutomatic(identity(notice)) else { continue }
            if let previous = state.queue.firstIndex(where: { $0.id == notice.id }), state.queue[previous].version != notice.bodyVersion { state.queue.remove(at: previous); changed = true }
            if !state.queue.contains(where: { $0.id == notice.id }), state.queue.count < 60 {
                state.queue.append(Queued(id: notice.id, version: notice.bodyVersion)); changed = true
            }
        }
        // Bodies are only held in memory for the bounded queue, never copied to its disk records.
        let keep = Set(state.queue.map(\.id)); currentNotices = currentNotices.filter { keep.contains($0.key) }; passages = passages.filter { keep.contains($0.key) }
        if changed { do { try save() } catch { status = AIError.storage.localizedDescription; return } }
        kick()
    }
    func summarize(_ notice: Notice, policies: [PolicyPassage] = [], manual: Bool = true) async throws -> CitedSummary {
        guard storageOK, !profile.isEmpty else { throw AIError.storage }
        guard configuration.enabled else { throw AIError.disabled }
        guard UIApplication.shared.applicationState == .active else { throw AIError.cancelled }
        guard !isBusy else { throw AIError.busy }
        let config = try configuration.validated()
        guard let keyData = try KeychainStore.read(credentialKey(config)), let key = String(data: keyData, encoding: .utf8), !key.isEmpty else { throw AIError.missingKey }
        let source = try AISourceSnapshot(notice: notice, policies: config.sharePolicies ? policies : [])
        let body = try AIResponseEnvelope.request(configuration: config, source: source)
        var ledger = state.ledger
        try ledger.reserve(identity: identity(notice), limit: config.dailyLimit, automatic: !manual)
        state.ledger = ledger; try save() // Fail closed: no network until the charged attempt is durable.
        let owner = profile, revision = generation
        currentNotices[notice.id] = notice; activeNotice = notice
        isBusy = true; status = "正在生成并核对原文依据…"
        let request = AITransport(); transport = request
        // Finish only an already-authorized foreground request; this is not a background scheduler.
        let continuationTask = UIApplication.shared.beginBackgroundTask(withName: "FinishSummary") { request.cancel() }
        defer {
            if continuationTask != .invalid { UIApplication.shared.endBackgroundTask(continuationTask) }
            if generation == revision {
                isBusy = false; transport = nil; activeNotice = nil
                if !state.queue.contains(where: { $0.id == notice.id }) { currentNotices.removeValue(forKey: notice.id); passages.removeValue(forKey: notice.id) }
            }
        }
        do {
            let (bytes, code) = try await request.post(endpoint: URL(string: config.endpoint)!, key: key, body: body)
            try Task.checkCancellation()
            guard owner == profile, revision == generation,
                  currentNotices[notice.id] == nil || currentNotices[notice.id]?.bodyVersion == notice.bodyVersion else { throw AIError.cancelled }
            let content = try AIResponseEnvelope.content(data: bytes, status: code)
            let summary = try AISummaryParser.parse(content, source: source)
            state.cache[notice.id] = Entry(summary: summary, binding: config.binding)
            if state.cache.count > 300, let oldest = state.cache.min(by: { $0.value.summary.generatedAt < $1.value.summary.generatedAt })?.key { state.cache.removeValue(forKey: oldest) }
            state.queue.removeAll { $0.id == notice.id && $0.version == notice.bodyVersion }
            try save(); cacheRevision += 1; status = "摘要已保存；请通过数字引用核对重要条件。"
            return summary
        } catch {
            if revision == generation { status = (error as? LocalizedError)?.errorDescription ?? AIError.transport.localizedDescription }
            if error is CancellationError { throw AIError.cancelled }; throw error
        }
    }
    func clearCache() {
        invalidate(); state.cache = [:]; state.queue = []; currentNotices = [:]; passages = [:]
        do { try save(); cacheRevision += 1; status = "摘要已清除；尝试记录与今日次数保留，避免自动重复计费。" }
        catch { status = AIError.storage.localizedDescription }
    }
    func clearCredentials() {
        do {
            try KeychainStore.delete(credentialKey(configuration)); invalidate()
            state.configuration.enabled = false; state.configuration.automatic = false; state.queue = []
            try save(); configuration = state.configuration; status = "已删除当前服务的密钥并关闭 AI。"
        } catch { status = AIError.storage.localizedDescription }
    }
    func policiesChanged() {
        invalidate(); passages = [:]
        for key in Array(state.cache.keys) { state.cache[key]?.summary.policyLinks = [] }
        do { try save(); cacheRevision += 1; status = "政策关联已清除，通知摘要仍保留；可手动重新生成政策参考。" }
        catch { status = AIError.storage.localizedDescription }
    }
    /// Call when filtering, deleting or replacing bodies; blocked notices must leave an active queue.
    func reconcile(_ allowedNotices: [Notice]) {
        let allowed = Dictionary(allowedNotices.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        let invalidated = currentNotices.contains { id, notice in allowed[id]?.bodyVersion != notice.bodyVersion } || activeNotice.map { allowed[$0.id]?.bodyVersion != $0.bodyVersion } == true
        if invalidated { invalidate() }
        currentNotices = currentNotices.filter { allowed[$0.key]?.bodyVersion == $0.value.bodyVersion }
        state.queue.removeAll { allowed[$0.id]?.bodyVersion != $0.version }
        state.cache = state.cache.filter { allowed[$0.key]?.bodyVersion == $0.value.summary.bodyVersion }
        do { try save(); cacheRevision += 1 } catch { status = AIError.storage.localizedDescription }
    }
    private func kick() {
        guard UIApplication.shared.applicationState == .active, pumpTask == nil, !isBusy, configuration.enabled, configuration.automatic else { return }
        let token = generation
        pumpTask = Task { [weak self] in
            guard let self else { return }
            var quotaReached = false
            for _ in 0..<3 {
                guard !Task.isCancelled, token == self.generation, UIApplication.shared.applicationState == .active,
                      let queued = self.state.queue.first(where: { self.currentNotices[$0.id]?.bodyVersion == $0.version }),
                      let notice = self.currentNotices[queued.id] else { break }
                do { _ = try await self.summarize(notice, policies: self.passages[notice.id] ?? [], manual: false) }
                catch AIError.quota { quotaReached = true; break }
                catch { if token == self.generation { self.state.queue.removeAll { $0 == queued }; try? self.save() } }
            }
            guard token == self.generation else { return }
            self.pumpTask = nil
            if !quotaReached, !Task.isCancelled, UIApplication.shared.applicationState == .active,
               self.state.queue.contains(where: { self.currentNotices[$0.id]?.bodyVersion == $0.version }) { self.kick() }
        }
    }
    private func identity(_ notice: Notice) -> String { AIHash.text(configuration.binding + notice.id + notice.bodyVersion) }
    private func credentialKey(_ configuration: SummaryConfiguration) -> String { "ai-key-" + AIHash.text(profile + "|" + configuration.endpoint) }
    private func invalidate() { generation += 1; pumpTask?.cancel(); pumpTask = nil; transport?.cancel(); transport = nil; activeNotice = nil; isBusy = false }
    private func save() throws {
        guard storageOK, let fileURL else { throw AIError.storage }
        var directory = fileURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues(); values.isExcludedFromBackup = true; try directory.setResourceValues(values)
        var data = try JSONEncoder().encode(state)
        // Reference context is intentionally retained for verification, so entry count alone
        // cannot bound bytes. Evict only old summaries, never billing/attempt tombstones.
        if data.count > 6 * 1024 * 1024 {
            let oldest = state.cache.sorted { $0.value.summary.generatedAt < $1.value.summary.generatedAt }.map(\.key)
            for key in oldest where state.cache.count > 1 {
                state.cache.removeValue(forKey: key)
                data = try JSONEncoder().encode(state)
                if data.count <= 6 * 1024 * 1024 { break }
            }
        }
        guard data.count < 8 * 1024 * 1024 else { throw AIError.tooLarge }
        try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
