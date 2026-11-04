import Foundation
import PDFKit
import CampusReaderCore

struct LocalPolicy: Codable, Identifiable, Sendable {
    var id: String
    var title: String
    var publisher: String
    var version: String
    var importedAt: Date
    var pages: [String]
}

@MainActor final class PolicyLibraryStore: ObservableObject {
    @Published private(set) var documents: [LocalPolicy] = []
    @Published private(set) var error: String?
    private var profile = ""
    private var writable = false
    private var searchCache: [String: [PolicyPassage]] = [:]
    var onChange: (() -> Void)?

    func activate(profile: String) {
        self.profile = profile; documents = []; searchCache = [:]; writable = false; error = nil
        do {
            documents = try AppFiles.read([LocalPolicy].self, from: fileURL()) ?? []
            writable = true
        } catch { self.error = "政策资料暂时无法读取，原文件已保留。" }
    }
    private func fileURL() throws -> URL { try AppFiles.directory(profile: profile).appendingPathComponent("policies.json") }
    func importDocument(_ url: URL, publisher: String, version: String) async throws {
        guard writable, documents.count < 20 else { throw PortalError.invalidResponse("政策库不可写或已达到 20 份上限") }
        let owner = profile
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let pages: [String] = try await Task.detached(priority: .utility) {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= 10 * 1_024 * 1_024 else { throw PortalError.invalidResponse("政策文件不能超过 10 MiB") }
            if url.pathExtension.lowercased() == "pdf" {
                guard let pdf = PDFDocument(url: url), !pdf.isEncrypted, pdf.pageCount <= 150 else { throw PortalError.invalidResponse("需要未加密、150 页以内的文字版 PDF") }
                var result: [String] = []; var total = 0
                for index in 0..<pdf.pageCount {
                    try Task.checkCancellation()
                    let text = pdf.page(at: index)?.string ?? ""; total += text.utf8.count
                    guard total <= 500_000 else { throw PortalError.invalidResponse("提取文字超过 500 KB，请缩小文件") }
                    result.append(text)
                }
                guard result.contains(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { throw PortalError.invalidResponse("扫描版 PDF 暂不支持，请导入文字版") }
                return result
            }
            guard url.pathExtension.lowercased() == "txt", size <= 500_000,
                  let text = String(data: try Data(contentsOf: url), encoding: .utf8), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw PortalError.invalidResponse("请使用 500 KB 以内的 UTF-8 TXT 或文字 PDF") }
            return [text]
        }.value
        guard owner == profile else { throw PortalError.cancelled }
        var next = documents
        next.append(LocalPolicy(id: UUID().uuidString, title: String(url.deletingPathExtension().lastPathComponent.prefix(120)), publisher: String(publisher.prefix(120)), version: String(version.prefix(80)), importedAt: Date(), pages: pages))
        try AppFiles.write(next, to: fileURL()); documents = next; searchCache = [:]; onChange?()
    }
    func remove(_ id: String) throws {
        guard writable else { throw PortalError.invalidResponse("资料库暂不可写") }
        let next = documents.filter { $0.id != id }; try AppFiles.write(next, to: fileURL()); documents = next; searchCache = [:]; onChange?()
    }
    func passages(for notice: Notice) async -> [PolicyPassage] {
        let key = notice.id + notice.bodyVersion, owner = profile, snapshot = documents
        if let result = searchCache[key] { return result }
        let result = await Task.detached(priority: .utility) { Self.search(notice, documents: snapshot) }.value
        guard owner == profile, snapshot.map(\.id) == documents.map(\.id), !Task.isCancelled else { return [] }
        if searchCache.count >= 30 { searchCache.removeAll() }
        searchCache[key] = result; return result
    }
    private nonisolated static func search(_ notice: Notice, documents: [LocalPolicy]) -> [PolicyPassage] {
        guard !documents.isEmpty else { return [] }
        let normalized = (notice.title + " " + String(notice.bodyText.prefix(2_000))).lowercased()
        let words = normalized.split(whereSeparator: { $0.isWhitespace || $0.isPunctuation }).map(String.init).filter { $0.count > 1 && $0.count < 30 }
        let chars = Array(normalized); var tokens = Array(Set(words)).sorted().prefix(24).map { $0 }
        if chars.count >= 2 { for i in 0..<(chars.count - 1) where tokens.count < 64 && !chars[i].isWhitespace && !chars[i + 1].isWhitespace {
            let token = String(chars[i...i + 1]); if !tokens.contains(token) { tokens.append(token) }
        } }
        var ranked: [(Int, PolicyPassage)] = []
        for (di, document) in documents.enumerated() {
            for (pi, page) in document.pages.enumerated() {
                if Task.isCancelled { return [] }
                let text = page.trimmingCharacters(in: .whitespacesAndNewlines); let chars = Array(text)
                for start in stride(from: 0, to: chars.count, by: 650) {
                    let part = String(chars[start..<min(start + 800, chars.count)])
                    let lower = part.lowercased()
                    let score = tokens.reduce(0) { $0 + (lower.contains($1) ? 1 : 0) }
                    if score > 1 {
                        let location = "第 \(pi + 1) 页，第 \(start / 650 + 1) 段；\(document.publisher) \(document.version)"
                        ranked.append((score, PolicyPassage(id: "P\(di + 1)_\(pi + 1)_\(start / 650 + 1)", documentTitle: document.title, location: location, text: part)))
                    }
                }
            }
        }
        return ranked.sorted { $0.0 > $1.0 }.prefix(5).map(\.1)
    }
}
