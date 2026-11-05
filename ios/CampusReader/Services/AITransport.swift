import Foundation
import CampusReaderCore

/// A separate ephemeral session: campus cookies, credentials and redirect targets never enter it.
final class AITransport: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let configuration: URLSessionConfiguration
    init(configuration: URLSessionConfiguration) {
        self.configuration = configuration
        super.init()
    }
    private let lock = NSLock()
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var continuation: CheckedContinuation<(Data, Int), Error>?
    private var bytes = Data()
    private var status = 0
    private var cancelled = false
    func post(endpoint: URL, key: String, body: Data) async throws -> (Data, Int) {
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if cancelled { lock.unlock(); continuation.resume(throwing: AIError.cancelled); return }
                self.continuation = continuation
                let config = configuration
                config.httpCookieStorage = nil; config.httpShouldSetCookies = false; config.urlCredentialStorage = nil
                config.urlCache = nil; config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 45
                let session = URLSession(configuration: config, delegate: self, delegateQueue: nil); self.session = session
                var request = URLRequest(url: endpoint); request.httpMethod = "POST"; request.httpBody = body
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
                request.setValue("application/json", forHTTPHeaderField: "Accept")
                let task = session.dataTask(with: request); self.task = task
                lock.unlock(); task.resume()
            }
        }, onCancel: { self.cancel() })
    }
    func cancel() { lock.lock(); cancelled = true; let active = task; lock.unlock(); active?.cancel(); finish(.failure(AIError.cancelled)) }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil); finish(.failure(AIError.rejected))
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let response = response as? HTTPURLResponse else { completionHandler(.cancel); finish(.failure(AIError.transport)); return }
        if response.expectedContentLength > 65536 { completionHandler(.cancel); finish(.failure(AIError.tooLarge)); return }
        lock.lock(); status = response.statusCode; lock.unlock(); completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        if bytes.count + data.count > 65536 { lock.unlock(); dataTask.cancel(); finish(.failure(AIError.tooLarge)); return }
        bytes.append(data); lock.unlock()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let result = bytes; let code = status; let wasCancelled = cancelled; lock.unlock()
        if wasCancelled { finish(.failure(AIError.cancelled)) }
        else if error != nil { finish(.failure(AIError.transport)) }
        else { finish(.success((result, code))) }
    }
    private func finish(_ result: Result<(Data, Int), Error>) {
        lock.lock(); let continuation = self.continuation; self.continuation = nil
        let session = self.session; self.session = nil; self.task = nil; lock.unlock()
        continuation?.resume(with: result); session?.invalidateAndCancel()
    }
}
