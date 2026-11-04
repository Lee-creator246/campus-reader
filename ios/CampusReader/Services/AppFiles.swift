import Foundation
import CampusReaderCore

enum AppFiles {
    static let maximumFileBytes = 128 * 1_024 * 1_024
    static func directory(profile: String) throws -> URL {
        guard profile.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw CocoaError(.fileWriteInvalidFileName) }
        var base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("CampusReader", isDirectory: true).appendingPathComponent(profile, isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues(); values.isExcludedFromBackup = true; try base.setResourceValues(values)
        return base
    }
    static func read<T: Decodable>(_ type: T.Type, from url: URL, maximum: Int = maximumFileBytes) throws -> T? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= maximum else { throw CocoaError(.fileReadTooLarge) }
        return try JSONDecoder().decode(type, from: Data(contentsOf: url))
    }
    static func write<T: Encodable>(_ value: T, to url: URL) throws {
        let data = try JSONEncoder().encode(value)
        guard data.count <= maximumFileBytes else { throw CocoaError(.fileWriteOutOfSpace) }
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var file = url; var values = URLResourceValues(); values.isExcludedFromBackup = true; try file.setResourceValues(values)
    }
}
