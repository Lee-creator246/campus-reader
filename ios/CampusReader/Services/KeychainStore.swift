import Foundation
import Security

enum KeychainStore {
    private static let service = "org.campusreader.ios.credentials"
    struct Failure: LocalizedError {
        let status: OSStatus
        var errorDescription: String? {
            switch status {
            case errSecInteractionNotAllowed:
                return "钥匙串暂不可访问，请解锁设备后重试。"
            case errSecMissingEntitlement:
                return "当前签名缺少钥匙串访问权限，请检查个人签名和描述文件。"
            default:
                return "钥匙串操作失败（系统状态码 \(status)）。"
            }
        }
    }
    static func read(_ key: String) throws -> Data? {
        var query = identity(key)
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw Failure(status: status) }
        return data
    }
    static func write(_ data: Data, key: String) throws {
        let values: [String: Any] = [kSecValueData as String: data,
                                     kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(identity(key) as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            var item = identity(key)
            values.forEach { item[$0.key] = $0.value }
            let added = SecItemAdd(item as CFDictionary, nil)
            guard added == errSecSuccess else { throw Failure(status: added) }
        } else if status != errSecSuccess { throw Failure(status: status) }
    }
    static func delete(_ key: String) throws {
        let status = SecItemDelete(identity(key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure(status: status) }
    }
    private static func identity(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: key, kSecAttrSynchronizable as String: false]
    }
}
