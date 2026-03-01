# Probe

A plugin-based mobile app inspector — built for debugging AI agent HTTP requests and more.

Inspired by [Facebook Flipper](https://github.com/facebook/flipper), Probe gives you real-time visibility into your mobile app via a beautiful terminal UI.

```
╔═══════════════════════════════════════════════════╗
║  Probe v0.1.0  •  Listening on :8484              ║
║  Waiting for plugin connection...                  ║
╚═══════════════════════════════════════════════════╝

[12:34:56] 📱 Connected: dev.probe.sample (Nothing Phone 2, Android 15)
[12:34:57] ● GET    https://swapi.py4e.com/api/people/   200  342ms  2.1KB
[12:34:58] ● GET    https://swapi.py4e.com/api/films/    200  880ms  8.4KB
[12:34:59] ● GET    https://swapi.py4e.com/api/planets/  200  456ms  3.7KB
```

## Architecture

```
┌──────────────────────────────────────────┐   WebSocket    ┌───────────────────┐
│  Mobile App                              │ ─────────────► │  Probe CLI (Rust) │
│                                          │   JSON events  │                   │
│  Probe SDK                               │                │  ws://0.0.0.0:8484│
│   ├── NetworkPlugin (OkHttp interceptor) │                │                   │
│   ├── DatabasePlugin (future)            │                │  Filter, display  │
│   ├── PreferencesPlugin (future)         │                │  save to JSONL    │
│   └── LayoutPlugin (future)              │                └───────────────────┘
└──────────────────────────────────────────┘
```

- **Rust CLI** is a WebSocket **server** on port 8484
- **SDK** connects as WebSocket client and forwards plugin events as JSON
- Non-blocking: capture → queue → background sender thread
- Plugin-based: each capability (network, db, prefs, layout) is a separate plugin

## Repository Structure

```
probe/
├── cli/                         # Rust CLI (WebSocket server + terminal UI)
├── sdk/
│   ├── android/                 # Android SDK (Kotlin/Gradle multi-module)
│   │   ├── core/                # Probe, ProbePlugin, ProbeHost, WebSocketTransport
│   │   ├── plugin-network/      # OkHttp network interceptor
│   │   ├── plugin-db/           # SQLite/Room inspector (skeleton)
│   │   ├── plugin-prefs/        # SharedPreferences inspector (skeleton)
│   │   ├── plugin-layout/       # Layout inspector (skeleton)
│   │   └── sample/              # Star Wars API demo app
│   ├── ios/                     # iOS Swift Package (skeleton)
│   ├── flutter/                 # Flutter Dart package (skeleton)
│   └── aurora/                  # AuroraOS C++/Qt (skeleton)
└── README.md
```

## Quick Start

### 1. Start the CLI

```bash
# Build
cargo build --release

# Run (default port 8484)
./target/release/probe

# With options
./target/release/probe --port 8484 --filter "swapi" --verbose --bodies
```

### 2. Set up ADB tunnel (physical device)

```bash
adb reverse tcp:8484 tcp:8484
```

**Android Emulator**: No setup needed — uses `ws://10.0.2.2:8484` automatically.

### 3. Install & run the sample app

```bash
cd sdk/android
./gradlew :sample:installDebug
```

Open the app, tap through the People / Films / Planets tabs — requests appear in the CLI.

## CLI Options

```
USAGE:
    probe [OPTIONS]

OPTIONS:
    -p, --port <PORT>          Port to listen on [default: 8484]
    -f, --filter <PATTERN>     Filter by URL pattern (regex)
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
# Watch AI API calls in real-time
probe --filter "openai\.com|anthropic\.com|claude"

# Save traffic to file for later analysis
probe --save session.jsonl

# Full verbose with bodies
probe --verbose --bodies --filter "api\."

# Show only large responses (>10KB)
probe --min-size 10240
```

## Android Integration

### 1. Add the SDK (local)

```kotlin
// settings.gradle.kts
includeBuild("../path/to/probe/sdk/android") {
    dependencySubstitution {
        substitute(module("dev.probe:plugin-network")).using(project(":plugin-network"))
    }
}
```

### 2. Initialize in Application.onCreate()

```kotlin
class MyApp : Application() {

    companion object {
        val networkPlugin = NetworkPlugin()
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            val serverUrl = if (isEmulator()) "ws://10.0.2.2:8484" else "ws://localhost:8484"
            Probe.install(
                Probe.Builder(this)
                    .serverUrl(serverUrl)
                    .plugin(networkPlugin)
                    // .plugin(DatabasePlugin(db))
                    .build()
            )
        }
    }
}
```

### 3. Add to OkHttpClient

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(MyApp.networkPlugin.interceptor())  // add LAST
    .build()
```

### 4. Dump recent requests (in-memory, no CLI needed)

```kotlin
val recent: List<HttpTransaction> = MyApp.networkPlugin.dump(last = 50)
```

## Plugin System

Each `ProbePlugin` gets attached to the `ProbeHost` (transport) and can send arbitrary JSON payloads to the CLI.

```kotlin
class DatabasePlugin(private val db: SupportSQLiteDatabase) : ProbePlugin {
    override val id = "database"
    override val displayName = "Database"

    override fun onAttach(host: ProbeHost) { /* start observing db */ }
    override fun onDetach() { /* stop */ }
}
```

## WebSocket Protocol

### Hello (on connect)
```json
{
  "type": "hello",
  "clientId": "uuid",
  "appPackage": "dev.myapp",
  "deviceModel": "Nothing Phone 2",
  "androidVersion": "15"
}
```

### Event (per plugin event)
```json
{
  "type": "event",
  "plugin": "network",
  "timestamp": 1234567890000,
  "payload": {
    "id": "uuid",
    "method": "GET",
    "url": "https://api.example.com/endpoint",
    "statusCode": 200,
    "durationMs": 342,
    "requestSizeBytes": 0,
    "responseSizeBytes": 2048
  }
}
```

## Platform Support

| Platform | Status | Notes |
|----------|--------|-------|
| Android  | ✅ Ready | `sdk/android/` — OkHttp interceptor, WebSocket transport |
| iOS      | 🚧 Skeleton | `sdk/ios/` — Swift Package, contributions welcome |
| Flutter  | 🚧 Skeleton | `sdk/flutter/` — Dart package, contributions welcome |
| AuroraOS | 📋 Planned | `sdk/aurora/` — C++/Qt, see README |

## Sample App

`sdk/android/sample/` — standalone publishable Android app using [Star Wars API](https://swapi.py4e.com/):

- **People tab** — Characters list (`GET /api/people/`)
- **Films tab** — All movies sorted by episode (`GET /api/films/`)
- **Planets tab** — Planets list (`GET /api/planets/`)

```bash
# Build release APK
cd sdk/android && ./gradlew :sample:assembleRelease
```

## License

MIT
