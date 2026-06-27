# CLAUDE.md — DevLens

DevLens is a Flipper-inspired multi-platform debugging SDK with a Rust CLI server.
SDKs connect via WebSocket to the CLI and stream plugin events (network requests, DB queries, etc.) for real-time terminal display.

## Architecture

```
SDK (client) ──WebSocket JSON──► CLI server (Rust, port 8484)
```

- **DevLens CLI** (`cli/`) — Rust/tokio WebSocket server. By default runs as a **silent agent daemon**: buffers SDK events and exposes them on demand via `devlens dump` (NDJSON over a Unix control socket). `--pretty` opts into the legacy interactive colored stream.
- **Android SDK** (`sdk/android/`) — Kotlin, Gradle multi-module. The only production-ready SDK.
- **iOS SDK** (`sdk/ios/`) — Swift Package. Stub: protocol definitions + `HttpTransaction` data model only. No WebSocket transport implemented.
- **Flutter SDK** (`sdk/flutter/`) — Deferred. Do not touch.
- **Aurora SDK** (`sdk/aurora/`) — Deferred. Do not touch.

### CLI modes (agent-friendly)

Default (`devlens`, or `devlens serve`) = **silent agent daemon**: the WS server buffers all events in a ring buffer (10k) and writes NOTHING to stdout (logs → stderr). An agent pulls events on demand via ephemeral subcommands over a Unix control socket (`~/.devlens/control.sock`, or `--control-socket` / `DEVLENS_SOCK`):

- `devlens start [--session N]` — begin/reset a capture session (cursor → now).
- `devlens dump [--session N]` — print buffered events since start/last-dump as NDJSON and advance the cursor. Works without `start` (default session from daemon start). Emits a `{type:gap,dropped:N}` line if the buffer overflowed between dumps.
- `devlens end [--session N]`, `devlens status`, `devlens shutdown`.

Each NDJSON line is a full event envelope plus CLI-synthesized lifecycle envelopes (`connect` / `hello` / `disconnect` / `gap`) in chronological order. `devlens --pretty` (or `DEVLENS_PRETTY=1`) restores the legacy continuous colored stream for humans. `--save FILE` (JSONL) works in both modes.

**Invariants:** default-mode stdout is NDJSON-only — never log to stdout on the daemon path (use `tracing`/`eprintln!`). The WS protocol and plugin IDs are unchanged; non-interactive is purely CLI-side rendering.

## Build Commands

### Rust CLI

```bash
cargo build                       # debug
cargo build --release             # release → target/release/devlens
cargo run -- --port 8484          # dev run with options
cargo test                        # unit tests
```

### Android SDK

```bash
cd sdk/android
./gradlew :core:assembleRelease
./gradlew :plugin-network:assembleRelease
./gradlew :sample:installDebug    # install Star Wars demo app
./gradlew assembleRelease         # build all modules
```

### iOS SDK

```bash
cd sdk/ios
swift build
swift test
```

## Android Module Graph

```
:sample
  ├── :plugin-network  (OkHttp interceptor — fully implemented)
  ├── :plugin-db       (stub — ProbePlugin interface only)
  ├── :plugin-prefs    (stub — ProbePlugin interface only)
  └── :plugin-layout   (stub — ProbePlugin interface only)

All plugins:
  └── api(:core)       (Probe, ProbePlugin, ProbeHost, WebSocketTransport)
```

Core external deps: OkHttp 5.4.0, Gson 2.11.0. No other external deps in SDK modules.

## WebSocket Protocol

All messages are UTF-8 JSON. The **push flow is unidirectional (SDK → CLI)**: `hello` on connect and `event` envelopes per plugin event. **Query/response is bidirectional** for query-capable plugins: the CLI sends a `{type:query}` request (`devlens db …`) over the WS write half and the SDK answers via a normal `event` envelope whose `payload.op == "queryResult"`, correlated by `requestId`. Push-only plugins (e.g. `network`) are unaffected.

### Hello (sent once on connect)

```json
{
  "type": "hello",
  "clientId": "<uuid>",
  "appPackage": "dev.myapp",
  "platform": "android",
  "deviceModel": "Pixel 9",
  "androidVersion": "15",
  "osVersion": "15"
}
```

### Event (sent per plugin event)

```json
{
  "type": "event",
  "plugin": "network",
  "timestamp": 1234567890000,
  "payload": {
    "id": "<uuid>",
    "method": "GET",
    "url": "https://api.example.com/endpoint",
    "statusCode": 200,
    "durationMs": 342,
    "requestSizeBytes": 0,
    "responseSizeBytes": 2048
  }
}
```

`plugin` must match `ProbePlugin.id`. Payload schema is plugin-defined and opaque to the transport.

## Key Architecture Invariants

1. **CLI = server, SDKs = clients.** Never invert this.
2. **`send()` is non-blocking.** `WebSocketTransport.send()` enqueues to `LinkedBlockingQueue(500)`. A dedicated daemon thread drains it. Do not add blocking waits on top.
3. **Queue is bounded (500).** When full, the oldest item is dropped (not the new one). Do not increase without measuring memory impact.
4. **Auto-reconnect at 3s.** Transport retries indefinitely. No user action needed.
5. **Plugin IDs are protocol-stable.** `ProbePlugin.id` appears in every event message. Changing it after publishing is a breaking change.
6. **Debug-only.** Guard `Probe.install()` with `BuildConfig.DEBUG` (Android) or `#if DEBUG` (iOS). Never ship DevLens in release builds.
7. **Query/response is bidirectional; push is not.** A query-capable plugin overrides the default `onQuery(QueryRequest)` (additive — push plugins ignore it). The CLI `devlens db …` subcommands send `{type:query}` and await a correlated `queryResult` event (single active debug client assumed in v1). Query responses are routed to the requester and are **not** buffered into `devlens dump` (only genuine push events are).

## Conventions

### Android
- Kotlin **2.4.0**, AGP **9.2.0**, Gradle **9.5.1**
- `minSdk 24`, `compileSdk 36`, `targetSdk 35`, JVM target **17**
- Group: `tech.devlens`; artifact IDs match module names (`core`, `plugin-network`, etc.)

### iOS
- Swift 6.0+, no external dependencies
- Platforms: iOS 14+, macOS 12+
- Products: `ProbeCore`, `ProbeNetwork`

### Rust CLI
- Edition 2021
- Use `anyhow` for errors, `tracing` + `tracing-subscriber` for logs (not `println!`)
- Binary name: `devlens`

## What NOT To Do

- Do NOT block in `ProbePlugin.onAttach()` / `onDetach()` — called on the main/connection thread.
- Do NOT call `ProbeHost.send()` with blocking pre-processing — it's already async, but don't hold locks around it.
- Do NOT run DevLens in release builds — strip it entirely with a `BuildConfig.DEBUG` guard, not just disable the connection.
- Do NOT add external runtime dependencies to `:core` or `:plugin-network` beyond OkHttp and Gson. Keep the SDK footprint minimal.
- Do NOT change published `ProbePlugin.id` values — breaking protocol change.
- Do NOT hardcode port 8484 anywhere in SDK code — always read from builder config.
- Do NOT modify `sdk/flutter/` or `sdk/aurora/` — both are deferred with no active development.
- Do NOT write to the iOS SDK transport layer — it is intentionally a stub awaiting a contributor.
