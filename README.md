# NetSniff

A network traffic sniffer for Android apps — built for debugging AI agent HTTP requests.

Inspired by [Facebook Flipper](https://github.com/facebook/flipper), NetSniff gives you real-time visibility into every HTTP request your Android app makes, displayed in a beautiful terminal UI.

```
╔═══════════════════════════════════════════════════╗
║  NetSniff v0.1.0  •  Listening on :8484           ║
║  Waiting for Android plugin connection...         ║
╚═══════════════════════════════════════════════════╝

[12:34:56] 📱 Connected: com.netsniff.sample (sdk_gphone64_x86_64, Android 14)
[12:34:57] ● GET    https://swapi.dev/api/people/   200  342ms  2.1KB
[12:34:58] ● GET    https://swapi.dev/api/films/    200  123ms  8.4KB
[12:34:59] ● GET    https://swapi.dev/api/planets/  200  456ms  3.7KB
```

## Architecture

```
┌─────────────────────────────────┐     WebSocket      ┌──────────────────────┐
│  Android App                    │  ──────────────►   │  NetSniff CLI (Rust) │
│                                 │     JSON msgs       │                      │
│  OkHttpClient                   │                     │  ws://0.0.0.0:8484   │
│      └─ NetSniffInterceptor     │                     │                      │
│           └─ WebSocketTransport │                     │  Filters, displays   │
└─────────────────────────────────┘                     └──────────────────────┘
```

- **Rust CLI** acts as a WebSocket **server** on port 8484
- **Android Plugin** is an OkHttp **interceptor** that sends captured requests as JSON via WebSocket
- The interceptor is non-blocking: captures → queues → background thread sends
- Designed for extensibility: implement `NetSniffTransport` to add gRPC, file logging, etc.

## Components

| Component | Language | Purpose |
|-----------|----------|---------|
| `cli/` | Rust | Terminal UI, WebSocket server, filtering |
| `android/plugin/` | Kotlin | OkHttp interceptor + WebSocket transport |
| `android/sample/` | Kotlin + Compose | Demo app using Star Wars API |

## Quick Start

### 1. Start the CLI

```bash
# Build
cargo build --release

# Run (default port 8484)
./target/release/netsniff

# With options
./target/release/netsniff --port 8484 --filter "swapi.dev" --verbose --bodies
```

### 2. Set up ADB connection

**USB-connected device** (recommended):
```bash
adb reverse tcp:8484 tcp:8484
```

**Android Emulator**: No setup needed — uses `10.0.2.2` automatically.

**WiFi device**: Update `serverUrl` in `SampleApplication.kt` to your machine's LAN IP.

### 3. Install & run the sample app

```bash
cd android
./gradlew :sample:installDebug
```

Open the app, tap through the tabs — you'll see requests appearing in the CLI.

## CLI Options

```
USAGE:
    netsniff [OPTIONS]

OPTIONS:
    -p, --port <PORT>          Port to listen on [default: 8484]
    -f, --filter <PATTERN>     Filter by URL pattern (regex)
    -v, --verbose              Show request and response headers
    -b, --bodies               Show request and response bodies
        --min-size <BYTES>     Only show responses larger than N bytes
        --no-color             Disable colored output
    -h, --help                 Print help
    -V, --version              Print version
```

**Examples:**
```bash
# Only show API calls to a specific domain
netsniff --filter "api\.example\.com"

# Show only large responses (>10KB)
netsniff --min-size 10240

# Full verbose mode with bodies
netsniff --verbose --bodies

# Filter AI API calls
netsniff --filter "openai\.com|anthropic\.com|claude"
```

## Using the Plugin in Your App

### 1. Add the dependency

The plugin is in `android/plugin/`. To use locally:

```kotlin
// settings.gradle.kts
includeBuild("../path/to/netsniff/android") {
    dependencySubstitution {
        substitute(module("com.netsniff:plugin")).using(project(":plugin"))
    }
}
```

Or publish to local Maven (see Publishing section).

### 2. Initialize in Application.onCreate()

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            NetSniff.install(
                NetSniff.Builder(this)
                    .serverUrl("ws://10.0.2.2:8484") // or localhost with adb reverse
                    .build()
            )
        }
    }
}
```

### 3. Add to OkHttpClient

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(NetSniff.interceptor()) // add LAST for accurate captures
    .build()
```

That's it. Every request through this client is now captured.

## Extensible Transport

Implement `NetSniffTransport` to add custom transports:

```kotlin
class FileTransport(val file: File) : NetSniffTransport {
    override fun connect() { /* open file */ }
    override fun send(transaction: HttpTransaction) { /* append JSON line */ }
    override fun disconnect() { /* close file */ }
    override val isConnected = true
}

// Use it:
NetSniff.install(
    NetSniff.Builder(this)
        .transport(FileTransport(File(filesDir, "traffic.jsonl")))
        .build()
)
```

## Message Protocol

All messages are JSON sent over WebSocket from plugin → CLI.

### Hello (on connect)
```json
{
  "type": "hello",
  "clientId": "uuid",
  "appPackage": "com.example.app",
  "deviceModel": "Pixel 7",
  "androidVersion": "14"
}
```

### Transaction (per HTTP request)
```json
{
  "type": "transaction",
  "id": "uuid",
  "timestamp": 1234567890000,
  "method": "GET",
  "url": "https://api.example.com/endpoint",
  "requestHeaders": {},
  "requestBody": null,
  "requestSizeBytes": 0,
  "responseCode": 200,
  "responseMessage": "OK",
  "responseHeaders": {"Content-Type": "application/json"},
  "responseBody": "{...}",
  "responseSizeBytes": 1024,
  "durationMs": 342,
  "appId": "com.example.app",
  "error": null
}
```

## Sample App

The `android/sample/` module demonstrates the plugin with the [Star Wars API](https://swapi.dev/):

- **People tab** — Characters list (`GET /api/people/`)
- **Films tab** — All movies sorted by episode (`GET /api/films/`)
- **Planets tab** — Planets list (`GET /api/planets/`)
- Refresh button triggers new requests on demand

The sample is a standalone publishable Android app. To publish:
```bash
cd android
./gradlew :sample:bundleRelease  # creates .aab for Play Store
./gradlew :sample:assembleRelease  # creates .apk
```

## Future Plans

- [ ] GUI/browser consumer (WebSocket → React frontend)
- [ ] iOS support (URLSession instrumentation)
- [ ] Mock response capability (intercept + return custom response)
- [ ] Request replay
- [ ] Export to HAR format
- [ ] gRPC transport support

## License

MIT
