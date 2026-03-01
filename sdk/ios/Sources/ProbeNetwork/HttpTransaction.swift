import Foundation

/// Represents a captured HTTP request/response pair.
public struct HttpTransaction {
    public let id: String
    public let url: String
    public let method: String
    public let requestHeaders: [String: String]
    public let requestBody: String?
    public let statusCode: Int?
    public let responseHeaders: [String: String]
    public let responseBody: String?
    public let durationMs: Double
    public let requestSize: Int
    public let responseSize: Int
    public let timestamp: Double

    public init(
        id: String = UUID().uuidString,
        url: String,
        method: String,
        requestHeaders: [String: String] = [:],
        requestBody: String? = nil,
        statusCode: Int? = nil,
        responseHeaders: [String: String] = [:],
        responseBody: String? = nil,
        durationMs: Double = 0,
        requestSize: Int = 0,
        responseSize: Int = 0,
        timestamp: Double = Date().timeIntervalSince1970 * 1000
    ) {
        self.id = id; self.url = url; self.method = method
        self.requestHeaders = requestHeaders; self.requestBody = requestBody
        self.statusCode = statusCode; self.responseHeaders = responseHeaders
        self.responseBody = responseBody; self.durationMs = durationMs
        self.requestSize = requestSize; self.responseSize = responseSize
        self.timestamp = timestamp
    }

    func toPayload() -> [String: Any?] {
        [
            "id": id, "url": url, "method": method,
            "requestSize": requestSize, "responseSize": responseSize,
            "statusCode": statusCode, "durationMs": durationMs,
            "timestamp": timestamp,
        ]
    }
}
