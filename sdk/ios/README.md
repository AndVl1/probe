# DevLens iOS SDK

Part of the [DevLens](../../README.md) multi-platform app inspector — a plugin-based debugging tool that streams real-time data from your mobile app to a terminal UI on your development machine.

## Current Status

**Stub implementation.** The Swift Package defines the public protocol surface (`ProbePlugin`, `ProbeHost`), data models (`HttpTransaction`), and plugin scaffolding (`NetworkPlugin`) — but the WebSocket transport that connects the SDK to the DevLens CLI is not yet implemented.

What is available today:
- `Probe.Builder` / `Probe.install(_:)` API surface (compiles and type-checks, no-op at runtime)
- `ProbePlugin` and `ProbeHost` protocols
- `NetworkPlugin` with in-memory ring buffer and manual `record(_:)` entry point
- `HttpTransaction` data model matching the wire protocol

What is not yet implemented:
- WebSocket transport (`Probe.install` is a no-op)
- Automatic URLSession traffic capture (no swizzling or URLProtocol provided)

Contributions welcome — see [Contributing a new SDK](../../README.md#contributing-a-new-sdk) in the main README.

## Installation

### Swift Package Manager — local path

```swift
// Package.swift
dependencies: [
    .package(path: "../path/to/probe/sdk/ios")
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "ProbeNetwork", package: "DevLens")
        ]
    )
]
```

Or add via Xcode: **File → Add Package Dependencies → Add Local**.

### Swift Package Manager — remote URL (once published)

```swift
// Package.swift
dependencies: [
    .package(
        url: "https://github.com/AndVl1/probe",
        from: "0.1.0"
    )
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "ProbeNetwork", package: "DevLens")
        ]
    )
]
```

The package is not yet listed on the Swift Package Index. Build from source in the meantime.

## API Overview

### Probe (ProbeCore)

```swift
// Entry point — call once in AppDelegate.application(_:didFinishLaunchingWithOptions:)
// or in your @main App.init()
public class Probe {
    public static func install(_ probe: Probe)  // no-op stub
    public static func uninstall()              // no-op stub

    public class Builder {
        public init()
        public func serverUrl(_ url: String) -> Builder
        public func plugin(_ plugin: any ProbePlugin) -> Builder
        public func build() -> Probe
    }
}
```

### ProbePlugin / ProbeHost (ProbeCore)

```swift
public protocol ProbePlugin: AnyObject {
    var id: String { get }
    var displayName: String { get }
    func onAttach(host: any ProbeHost)
    func onDetach()
}

public protocol ProbeHost: AnyObject {
    var isConnected: Bool { get }
    func send(pluginId: String, payload: [String: Any?])
}
```

### NetworkPlugin (ProbeNetwork)

```swift
public class NetworkPlugin: ProbePlugin {
    // bufferSize: max number of transactions kept in memory (default 1000)
    public init(bufferSize: Int = 1000)

    // Returns the last `last` captured transactions from the in-memory buffer
    public func dump(last: Int = 100) -> [HttpTransaction]

    // Record a transaction manually (call from a URLProtocol subclass or test)
    public func record(_ transaction: HttpTransaction)
}
```

### HttpTransaction (ProbeNetwork)

```swift
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
    public let timestamp: Double  // milliseconds since epoch
}
```

## Planned Usage

Once the transport is implemented, the integration will look like this:

```swift
// AppDelegate.swift
import ProbeCore
import ProbeNetwork

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    static let networkPlugin = NetworkPlugin()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        #if DEBUG
        Probe.install(
            Probe.Builder()
                .serverUrl("ws://localhost:8484")  // use: idb companion --udid ... + tunnel
                .plugin(AppDelegate.networkPlugin)
                .build()
        )
        #endif
        return true
    }
}
```

Traffic capture will require a `URLProtocol` subclass or URLSession delegate — these are not yet provided by the SDK. For now, you can call `NetworkPlugin.record(_:)` manually to populate the in-memory buffer.

## Port Forwarding (iOS)

The iOS equivalent of `adb reverse` uses `idb` (Meta's iOS Development Bridge) or Xcode's `simctl`:

```bash
# Simulator — no tunnel needed, use localhost directly
# ws://localhost:8484 works out of the box

# Physical device — requires idb or similar
idb connect <udid>
idb companion --udid <udid>
# then set up a TCP tunnel on port 8484
```

---

[Back to main README](../../README.md)
