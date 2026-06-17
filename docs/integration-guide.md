# Probe Integration Guide

Step-by-step instructions for integrating Probe into your app and connecting to the Probe CLI.

## Table of Contents

- [CLI Setup](#cli-setup)
- [Android](#android)
- [iOS](#ios)
- [General Workflow](#general-workflow)

---

## CLI Setup

The Probe CLI is the server side — it listens for SDK connections and renders captured data in the terminal.

### Build from source

```bash
git clone https://github.com/AndVl1/probe
cd probe
cargo build --release
./target/release/probe
```

Requires [Rust toolchain](https://rustup.rs/) 1.70+.

### Homebrew (coming soon)

```bash
# brew install probe   ← formula not yet published; build from source for now
```

### Common flags

```
OPTIONS:
    -p, --port <PORT>          Port to listen on [default: 8484]
    -f, --filter <PATTERN>     Filter transactions by URL pattern (regex)
    -v, --verbose              Show request and response headers
    -b, --bodies               Show request and response bodies (truncated to 4KB)
        --min-size <BYTES>     Only show responses larger than N bytes
        --save <FILE>          Save all captured transactions to a JSONL file
        --no-color             Disable colored output
    -h, --help                 Print help
    -V, --version              Print version
```

**Examples:**

```bash
# Watch all traffic
probe

# Filter to a specific host
probe --filter "api\.example\.com"

# Full verbose output including bodies
probe --verbose --bodies

# Save session for later analysis
probe --save session.jsonl

# Monitor AI API calls only
probe --filter "openai\.com|anthropic\.com"

# Show only large responses (>10KB)
probe --min-size 10240
```

Start the CLI **before** launching your app. The SDK reconnects automatically when the CLI is running.

---

## Android

### 1. Add the SDK dependency

#### Maven Central (planned — not yet published)

Once published, add to your app's `build.gradle.kts`:

```kotlin
dependencies {
    debugImplementation("dev.probe:core:0.1.0")
    debugImplementation("dev.probe:plugin-network:0.1.0")
}
```

#### Local development — composite build

Until the library is on Maven Central, use a [composite build](https://docs.gradle.org/current/userguide/composite_builds.html) so Gradle resolves the modules directly from source:

```kotlin
// settings.gradle.kts (your app project)
includeBuild("../path/to/probe/sdk/android") {
    dependencySubstitution {
        substitute(module("dev.probe:core")).using(project(":core"))
        substitute(module("dev.probe:plugin-network")).using(project(":plugin-network"))
    }
}
```

Then declare the dependency as if it were published:

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation("dev.probe:core:0.1.0")
    debugImplementation("dev.probe:plugin-network:0.1.0")
}
```

Use `debugImplementation`, not `implementation`. Probe must never ship in release builds.

### 2. Initialize in Application.onCreate()

```kotlin
class MyApp : Application() {

    companion object {
        // Keep a reference so other parts of the app can call dump()
        val networkPlugin = NetworkPlugin()
    }

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            // Emulator connects to the host machine at 10.0.2.2 automatically.
            // Physical device needs `adb reverse tcp:8484 tcp:8484` first.
            val serverUrl = if (isEmulator()) "ws://10.0.2.2:8484" else "ws://localhost:8484"

            Probe.install(
                Probe.Builder(this)
                    .serverUrl(serverUrl)
                    .plugin(networkPlugin)
                    // .plugin(DatabasePlugin(db))   // future
                    // .plugin(PreferencesPlugin())  // future
                    .build()
            )
        }
    }

    private fun isEmulator(): Boolean =
        android.os.Build.PRODUCT.contains("sdk") ||
        android.os.Build.FINGERPRINT.startsWith("generic")
}
```

Always wrap the `Probe.install` call in `BuildConfig.DEBUG`. The `if` check ensures the Proguard/R8 shrinker can remove all Probe code from release builds even if `debugImplementation` is used.

### 3. Add the OkHttp interceptor

```kotlin
val client = OkHttpClient.Builder()
    // Add the Probe interceptor last so it captures the final request
    // (after all other interceptors have applied their modifications)
    .addInterceptor(MyApp.networkPlugin.interceptor())
    .build()
```

If you use Retrofit, pass the client to the builder:

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://api.example.com/")
    .client(client)
    .addConverterFactory(GsonConverterFactory.create())
    .build()
```

### 4. Dump recent transactions (no CLI required)

The `NetworkPlugin` maintains an in-memory ring buffer (default 1000 transactions). You can read from it at any time without the CLI running:

```kotlin
// Get the last 50 captured transactions
val recent: List<HttpTransaction> = MyApp.networkPlugin.dump(last = 50)

// Clear the buffer
MyApp.networkPlugin.clearBuffer()
```

This is useful for in-app debug screens or automated tests.

### 5. Port forwarding — physical device

```bash
# Run once after plugging in the device (or after reboot)
adb reverse tcp:8484 tcp:8484
```

Android Emulator does not need this — `ws://10.0.2.2:8484` routes to `localhost` on the host machine automatically.

For WiFi connections (no USB), point `serverUrl` directly at your machine's LAN IP:

```kotlin
.serverUrl("ws://192.168.1.100:8484")
```

---

## iOS

### Current limitations

The iOS SDK is a **stub**. The public API compiles and the data models are complete, but the WebSocket transport that sends data to the Probe CLI is not yet implemented. `Probe.install(_:)` is a no-op.

What works today:
- `NetworkPlugin.record(_:)` — manually populate the in-memory buffer
- `NetworkPlugin.dump(last:)` — read back captured transactions
- Full `HttpTransaction` model matching the wire protocol

What requires implementation before connecting to the CLI:
- WebSocket transport
- Automatic URLSession traffic capture (URLProtocol or swizzling)

### 1. Add the Swift Package

In Xcode: **File → Add Package Dependencies** → enter the repository URL, or add via local path for development.

```swift
// Package.swift
dependencies: [
    .package(path: "../path/to/probe/sdk/ios")
    // or, once published:
    // .package(url: "https://github.com/AndVl1/probe", from: "0.1.0")
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "ProbeNetwork", package: "Probe")
        ]
    )
]
```

### 2. Planned initialization (not functional yet)

```swift
// AppDelegate.swift
import ProbeCore
import ProbeNetwork

static let networkPlugin = NetworkPlugin()

func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
) -> Bool {
    #if DEBUG
    Probe.install(
        Probe.Builder()
            .serverUrl("ws://localhost:8484")
            .plugin(AppDelegate.networkPlugin)
            .build()
    )
    #endif
    return true
}
```

### 3. Manual transaction recording (available now)

You can integrate `NetworkPlugin` with a custom `URLProtocol` subclass today:

```swift
let tx = HttpTransaction(
    url: request.url?.absoluteString ?? "",
    method: request.httpMethod ?? "GET",
    requestHeaders: request.allHTTPHeaderFields ?? [:],
    statusCode: httpResponse?.statusCode,
    durationMs: elapsed,
    timestamp: Date().timeIntervalSince1970 * 1000
)
AppDelegate.networkPlugin.record(tx)
```

### 4. Port forwarding — physical device

The iOS equivalent of `adb reverse` for physical devices requires `idb` (Meta's iOS Development Bridge) or a manual TCP tunnel. On Simulator, `localhost` resolves to the host machine directly — no setup needed.

```bash
# iOS Simulator: no tunnel needed
# ws://localhost:8484 works directly

# Physical device: set up port forwarding via idb or instruments
# (exact steps depend on your toolchain)
```

---

## General Workflow

The recommended startup order:

1. Start the Probe CLI on your machine.
2. If using a physical device, set up port forwarding (`adb reverse` for Android).
3. Launch the app. The SDK connects automatically and sends a `hello` handshake.
4. Exercise the app — captured events appear in the terminal in real-time.
5. Use `--save session.jsonl` to persist a session for offline analysis.

The CLI prints a banner when it is ready and logs each SDK connection:

```
╔═══════════════════════════════════════════════════╗
║  Probe v0.1.0  •  Listening on :8484              ║
║  Waiting for plugin connection...                  ║
╚═══════════════════════════════════════════════════╝

[12:34:56] Connected: com.example.myapp (Pixel 8, Android 15)
[12:34:57] GET  https://api.example.com/users  200  124ms  1.2KB
```

---

See the [main README](../README.md) for the full architecture overview, WebSocket protocol specification, and platform support matrix.
