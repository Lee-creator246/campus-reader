import Foundation

/// Reject duplicate keys, trailing content and excessive depth before Foundation decoding.
public enum AIJSON {
    public static func object(_ data: Data, maxBytes: Int = 65536) throws -> [String: Any] {
        guard !data.isEmpty, data.count <= maxBytes else { throw AIError.tooLarge }
        var scanner = Scanner(bytes: Array(data)); try scanner.value(depth: 0); scanner.space()
        guard scanner.index == scanner.bytes.count,
              let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw AIError.invalidJSON }
        return result
    }
    private struct Scanner {
        let bytes: [UInt8]; var index = 0
        mutating func space() { while index < bytes.count && [9,10,13,32].contains(bytes[index]) { index += 1 } }
        mutating func take(_ byte: UInt8) -> Bool { space(); if index < bytes.count && bytes[index] == byte { index += 1; return true }; return false }
        mutating func string() throws -> String {
            space(); let start = index; guard take(34) else { throw AIError.invalidJSON }
            var escaped = false
            while index < bytes.count {
                let byte = bytes[index]; index += 1
                if escaped { escaped = false; continue }
                if byte == 92 { escaped = true; continue }
                if byte == 34 {
                    guard let text = try JSONSerialization.jsonObject(with: Data(bytes[start..<index]), options: .fragmentsAllowed) as? String else { throw AIError.invalidJSON }
                    return text
                }
                if byte < 32 { throw AIError.invalidJSON }
            }
            throw AIError.invalidJSON
        }
        mutating func value(depth: Int) throws {
            guard depth < 18 else { throw AIError.invalidJSON }; space()
            guard index < bytes.count else { throw AIError.invalidJSON }
            if bytes[index] == 34 { _ = try string(); return }
            if take(123) {
                if take(125) { return }; var seen = Set<String>()
                repeat {
                    let key = try string(); guard seen.insert(key).inserted, take(58) else { throw AIError.invalidJSON }
                    try value(depth: depth + 1)
                    if take(125) { return }; guard take(44) else { throw AIError.invalidJSON }
                } while true
            }
            if take(91) {
                if take(93) { return }
                repeat { try value(depth: depth + 1); if take(93) { return }; guard take(44) else { throw AIError.invalidJSON } } while true
            }
            let start = index
            while index < bytes.count && ![9,10,13,32,44,93,125].contains(bytes[index]) { index += 1 }
            guard start < index else { throw AIError.invalidJSON }
            _ = try JSONSerialization.jsonObject(with: Data(bytes[start..<index]), options: .fragmentsAllowed)
        }
    }
}
