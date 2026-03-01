/// Probe — plugin-based mobile app inspector for iOS.
///
/// ## Setup (AppDelegate / @main)
/// ```swift
/// let networkPlugin = NetworkPlugin()
///
/// Probe.install(
///     Probe.Builder()
///         .serverUrl("ws://localhost:8484")   // use: idb reverse tcp:8484 tcp:8484
///         .plugin(networkPlugin)
///         .build()
/// )
/// ```
///
/// - Note: iOS implementation is a placeholder. Contributions welcome.
public class Probe {

    public static func install(_ probe: Probe) {
        // TODO: implement
    }

    public static func uninstall() {
        // TODO: implement
    }

    public class Builder {
        private var serverUrl: String = "ws://localhost:8484"
        private var plugins: [any ProbePlugin] = []

        public init() {}

        public func serverUrl(_ url: String) -> Builder {
            serverUrl = url; return self
        }

        public func plugin(_ plugin: any ProbePlugin) -> Builder {
            plugins.append(plugin); return self
        }

        public func build() -> Probe {
            return Probe()
        }
    }
}
