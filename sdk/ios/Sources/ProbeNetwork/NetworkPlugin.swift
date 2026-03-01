import ProbeCore
import Foundation

/// Probe plugin — captures URLSession network traffic on iOS.
///
/// - Note: iOS implementation is a placeholder. Contributions welcome.
///   For now, you can manually call `record(_:)` or use a custom URLProtocol.
public class NetworkPlugin: ProbePlugin {

    public let id = "network"
    public let displayName = "Network"

    private weak var host: (any ProbeHost)?
    private var buffer: [HttpTransaction] = []
    private let bufferSize: Int

    public init(bufferSize: Int = 1000) {
        self.bufferSize = bufferSize
    }

    public func onAttach(host: any ProbeHost) {
        self.host = host
    }

    public func onDetach() {
        host = nil
    }

    /// Returns the last `last` captured transactions.
    public func dump(last: Int = 100) -> [HttpTransaction] {
        let from = max(0, buffer.count - last)
        return Array(buffer[from...])
    }

    /// Record a transaction (call from URLProtocol subclass or manually).
    public func record(_ transaction: HttpTransaction) {
        buffer.append(transaction)
        if buffer.count > bufferSize { buffer.removeFirst() }
        host?.send(pluginId: id, payload: transaction.toPayload())
    }
}
