import Foundation
import UIKit
import CampusReaderCore

@MainActor enum MediaStore {
    static func data(url: URL, model: AppModel, maximum: Int = 8 * 1_024 * 1_024) async throws -> Data {
        let owner = model.profileID
        let folder = try AppFiles.directory(profile: owner).appendingPathComponent("media", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent(StableHash.sha256(url.absoluteString))
        if let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= maximum, let cached = try? Data(contentsOf: file) { return cached }
        let result: Data
        if url.host?.lowercased() == "my.bupt.edu.cn" { result = try await model.portal.resource(url, maxBytes: maximum) }
        else { result = try await PublicDownloader.data(url, maximum: maximum) }
        guard owner == model.profileID else { throw PortalError.cancelled }
        try result.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])
        var total = 0
        for item in files.sorted(by: { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) > ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }) {
            total += (try? item.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if total > 40 * 1_024 * 1_024 { try? FileManager.default.removeItem(at: item) }
        }
        return result
    }
    static func export(_ attachment: NoticeAttachment, model: AppModel) async throws -> URL {
        let owner = model.profileID
        let bytes = try await data(url: attachment.url, model: model, maximum: 20 * 1_024 * 1_024)
        guard owner == model.profileID else { throw PortalError.cancelled }
        let directory = try AppFiles.directory(profile: owner).appendingPathComponent("exports", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for old in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) { try? FileManager.default.removeItem(at: old) }
        var name = attachment.name.components(separatedBy: CharacterSet(charactersIn: "/\\:\0")).joined(separator: "_")
        name = String(name.prefix(120)); if name.isEmpty || name == "." || name == ".." { name = "attachment" }
        let file = directory.appendingPathComponent(name)
        try bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); return file
    }
}
