/// A Probe plugin provides a specific inspection capability (network, db, prefs, …).
public protocol ProbePlugin: AnyObject {
    var id: String { get }
    var displayName: String { get }

    func onAttach(host: any ProbeHost)
    func onDetach()
}

/// The host provides transport access to plugins.
public protocol ProbeHost: AnyObject {
    var isConnected: Bool { get }
    func send(pluginId: String, payload: [String: Any?])
}
