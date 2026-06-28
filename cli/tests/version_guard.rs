//! Version-guard integration tests (DoD d14 / d15, runtime confirmation of
//! d17 / d18 flagged by review).
//!
//! Spawns the real `devlens serve` binary, drives an SDK WebSocket client at it
//! with controlled `hello.minCliVersion` values, and captures the daemon's
//! stdout AND stderr separately to prove the guard wiring in
//! [`devlens::server`] (the `Stale -> eprintln!(format_warning)` block +
//! `hello.is_none()` once-per-connection gate):
//!   - a stale `minCliVersion` (strictly greater than the running CLI version)
//!     emits exactly the [`format_warning`] line on stderr,
//!   - stdout is never polluted by the warning (NDJSON-only daemon invariant),
//!   - absent / older `minCliVersion` stays silent on the guard path,
//!   - the warning fires at most once per connection (the `hello.is_none()`
//!     gate), even across two stale hellos with different client ids, and
//!   - after a stale-hello warning the connection is NOT broken — a follow-up
//!     event still reaches `devlens dump` (WARN & CONTINUE).
//!
//! The pure comparison logic in `cli/src/version.rs` is covered by unit tests;
//! this file covers the I/O wiring that the unit suite cannot reach.
//!
//! # Harness
//!
//! Mirrors `cli/tests/pretty_gate.rs` (subprocess + piped stdout). The only
//! deviation is that stderr is ALSO piped (`pretty_gate` nulls it) because the
//! guard writes exclusively to stderr — that is the whole point of these tests.
//! The exact expected warning string is built with the public
//! [`devlens::version::format_warning`] + [`devlens::version::CLI_VERSION`] so
//! the assertion stays in sync with the implementation without duplication.

#![cfg(unix)]

use std::io::Read;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::{connect_async, tungstenite::Message};

use devlens::version::{format_warning, CLI_VERSION};

// ── wire fixtures ─────────────────────────────────────────────────────────────

/// Old-SDK hello with NO `minCliVersion` field. Guard must stay silent
/// (additive field → None → VersionCheck::Ok).
const HELLO_NO_MIN: &str = r#"{"type":"hello","clientId":"c-no-min","appPackage":"com.example","deviceModel":"Pixel","platform":"android"}"#;

/// Hello advertising a minimum any released CLI satisfies (`0.0.1` < current).
/// Guard must stay silent (newer/equal CLI → VersionCheck::Ok).
const HELLO_MIN_OLD: &str = r#"{"type":"hello","clientId":"c-min-old","appPackage":"com.example","deviceModel":"Pixel","platform":"android","minCliVersion":"0.0.1"}"#;

/// Hello advertising a minimum no released CLI can satisfy — guarantees Stale
/// regardless of the exact `env!("CARGO_PKG_VERSION")`.
const HELLO_MIN_STALE: &str = r#"{"type":"hello","clientId":"c-min-stale","appPackage":"com.example","deviceModel":"Pixel","platform":"android","minCliVersion":"99.0.0"}"#;

/// A network event sent AFTER a stale hello, used to prove WARN & CONTINUE —
/// the connection survives the warning and the event still reaches the buffer
/// (and thus `devlens dump`).
const FOLLOWUP_EVENT: &str = r#"{"type":"event","plugin":"network","timestamp":7,"payload":{"id":"tx-vg","timestamp":7,"method":"GET","url":"https://version-guard.example.com/after","requestHeaders":{},"requestSizeBytes":0,"responseCode":204,"responseHeaders":{},"responseSizeBytes":0,"durationMs":5}}"#;

/// Distinctive prefix of the guard's warning line. Stable across CLI versions
/// and unique to the guard (the daemon's startup line is `devlens: control
/// socket listening …`, which never contains `WARNING CLI version`), so this is
/// a reliable signature for presence / absence / occurrence-count checks
/// without coupling to the exact versions embedded in the body.
const WARNING_SIGNATURE: &str = "devlens: WARNING CLI version";

// ── harness ───────────────────────────────────────────────────────────────────

/// Read the daemon's actual bound port from `$HOME/.devlens/daemon.json`,
/// retrying briefly while the file is written. Mirrors `e2e.rs::read_port` and
/// `pretty_gate.rs::read_port_from_daemon_json`.
fn read_port(home: &std::path::Path) -> u16 {
    let path = home.join(".devlens").join("daemon.json");
    let deadline = Instant::now() + Duration::from_secs(3);
    loop {
        if let Ok(text) = std::fs::read_to_string(&path) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
                if let Some(p) = v["port"].as_u64() {
                    return p as u16;
                }
            }
        }
        if Instant::now() > deadline {
            panic!("could not read port from {}", path.display());
        }
        std::thread::sleep(Duration::from_millis(30));
    }
}

/// Owned daemon handle: keeps the temp HOME alive for the daemon's lifetime and
/// hands off its stdout/stderr pipes on shutdown. The daemon is spawned with
/// BOTH pipes captured (unlike `pretty_gate`, which nulls stderr) — that is the
/// only way to assert the guard's stderr output from outside the process.
struct Daemon {
    child: std::process::Child,
    home: std::path::PathBuf,
    sock: std::path::PathBuf,
    bin: &'static str,
    // Keep the temp dir alive for the daemon's lifetime; dropped with Self.
    _dir: tempfile::TempDir,
}

impl Daemon {
    /// Spawn `devlens serve --port 0` with piped stdout + stderr, wait for the
    /// control socket (readiness), and return the handle.
    fn spawn(sock_name: &str) -> Self {
        let dir = tempfile::tempdir().expect("tempdir");
        let home = dir.path().to_path_buf();
        let sock = home.join(sock_name);
        let bin = env!("CARGO_BIN_EXE_devlens");

        let mut cmd = Command::new(bin);
        cmd.arg("serve")
            .arg("--port")
            .arg("0")
            .arg("--control-socket")
            .arg(&sock)
            .arg("--no-color")
            .env("HOME", &home)
            .env_remove("XDG_RUNTIME_DIR")
            .env_remove("DEVLENS_SOCK")
            .env_remove("DEVLENS_PRETTY")
            // Determinism: silence any tracing subscriber default so stderr
            // contains only the daemon's explicit eprintln! output.
            .env_remove("RUST_LOG")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        let child = cmd.spawn().expect("spawn devlens serve");

        // Wait for the control socket (daemon readiness).
        let deadline = Instant::now() + Duration::from_secs(5);
        while !sock.exists() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(30));
        }
        assert!(sock.exists(), "daemon control socket never appeared");

        Self {
            child,
            home,
            sock,
            bin,
            _dir: dir,
        }
    }

    /// The daemon's actual bound WS port (read from daemon.json).
    fn port(&self) -> u16 {
        read_port(&self.home)
    }

    /// Run `devlens dump` against this daemon and return its stdout (NDJSON).
    /// Used to prove a follow-up event was buffered (WARN & CONTINUE).
    fn dump(&self) -> String {
        let out = Command::new(self.bin)
            .arg("dump")
            .arg("--control-socket")
            .arg(&self.sock)
            .env("HOME", &self.home)
            .env_remove("XDG_RUNTIME_DIR")
            .env_remove("DEVLENS_SOCK")
            .output()
            .expect("spawn dump");
        assert!(out.status.success(), "dump should exit 0");
        String::from_utf8_lossy(&out.stdout).into_owned()
    }

    /// Shut the daemon down, wait for it to exit, then read everything it wrote
    /// to stdout and stderr. The pipes hit EOF when the daemon exits, so this
    /// returns the daemon's complete lifetime output.
    fn shutdown_and_collect(mut self) -> (String, String) {
        let shut = Command::new(self.bin)
            .arg("shutdown")
            .arg("--control-socket")
            .arg(&self.sock)
            .env("HOME", &self.home)
            .env_remove("XDG_RUNTIME_DIR")
            .env_remove("DEVLENS_SOCK")
            .status()
            .expect("spawn shutdown");
        assert!(shut.success(), "shutdown subcommand failed");

        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            match self.child.try_wait().expect("try_wait") {
                Some(_) => break,
                None if Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(30));
                }
                None => {
                    let _ = self.child.kill();
                    panic!("daemon did not exit after shutdown");
                }
            }
        }

        let mut stdout = String::new();
        self.child
            .stdout
            .take()
            .expect("piped stdout")
            .read_to_string(&mut stdout)
            .expect("read daemon stdout");
        let mut stderr = String::new();
        self.child
            .stderr
            .take()
            .expect("piped stderr")
            .read_to_string(&mut stderr)
            .expect("read daemon stderr");
        (stdout, stderr)
    }
}

/// Connect, send each message in order, send Close, then drain server frames.
/// Retries the connect briefly to absorb the bind→accept race (mirrors
/// `pretty_gate.rs::drive_sdk_activity`).
async fn drive_sdk(port: u16, messages: &[&str]) {
    let url = format!("ws://127.0.0.1:{}", port);
    let deadline = Instant::now() + Duration::from_secs(3);
    let mut ws = loop {
        match connect_async(&url).await {
            Ok((ws, _)) => break ws,
            Err(_) if Instant::now() < deadline => {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            Err(e) => panic!("ws connect failed: {}", e),
        }
    };
    for m in messages {
        ws.send(Message::Text((*m).into())).await.unwrap();
    }
    let _ = ws.send(Message::Close(None)).await;
    while let Some(Ok(_)) = ws.next().await {}
}

// ── d14 / d18: stale minCliVersion warns on stderr, stdout stays pure, and the
//    connection survives (WARN & CONTINUE). ────────────────────────────────────

#[tokio::test]
async fn stale_min_cli_version_warns_on_stderr_and_keeps_stdout_pure() {
    let daemon = Daemon::spawn("vg-stale.sock");
    let port = daemon.port();
    drive_sdk(port, &[HELLO_MIN_STALE, FOLLOWUP_EVENT]).await;
    // Let the daemon buffer the follow-up event before dumping.
    tokio::time::sleep(Duration::from_millis(250)).await;

    // WARN & CONTINUE: the follow-up event reached the buffer despite the
    // stale-hello warning — the guard never tore the connection down. The
    // network event is queryResult-free and plugin="network", so it is buffered
    // (not routed as a query response).
    let dump = daemon.dump();
    let followup_reached = dump
        .lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .any(|v| {
            v["type"] == "event"
                && v["plugin"] == "network"
                && v["payload"]["url"] == "https://version-guard.example.com/after"
        });
    assert!(
        followup_reached,
        "follow-up event must reach dump after stale-hello warning (WARN & CONTINUE): {dump}"
    );

    let (stdout, stderr) = daemon.shutdown_and_collect();

    // The exact warning line, built via the same public API the daemon uses, is
    // on stderr — byte-exact match (subsumes the cli/min/brew/em-dash checks).
    let expected_warning = format_warning(CLI_VERSION, "99.0.0");
    assert!(
        stderr.contains(&expected_warning),
        "stderr must contain the exact guard warning line; stderr={:?}",
        stderr,
    );
    // Explicit version presence (acceptance: CLI version + 99.0.0 on stderr).
    assert!(
        stderr.contains(CLI_VERSION),
        "stderr must mention the running CLI version {CLI_VERSION}; stderr={:?}",
        stderr,
    );
    assert!(
        stderr.contains("99.0.0"),
        "stderr must mention the SDK minCliVersion 99.0.0; stderr={:?}",
        stderr,
    );
    assert!(
        stderr.contains("Upgrade: brew upgrade devlens"),
        "stderr must include the brew upgrade hint; stderr={:?}",
        stderr,
    );

    // stdout purity: the warning never leaks onto stdout. The default daemon
    // writes nothing to stdout anyway (NDJSON comes only from `devlens dump`),
    // so the warning's absence is the guard-specific guarantee under test.
    assert!(
        !stdout.contains(WARNING_SIGNATURE),
        "stdout must not contain the guard warning; stdout={:?}",
        stdout,
    );
    assert!(
        !stdout.contains(&expected_warning),
        "stdout must not contain the exact warning line; stdout={:?}",
        stdout,
    );
}

// ── d15: silent when minCliVersion is absent (old SDK) ────────────────────────

#[tokio::test]
async fn absent_min_cli_version_emits_no_warning() {
    let daemon = Daemon::spawn("vg-absent.sock");
    let port = daemon.port();
    drive_sdk(port, &[HELLO_NO_MIN]).await;
    tokio::time::sleep(Duration::from_millis(150)).await;

    let (stdout, stderr) = daemon.shutdown_and_collect();

    assert!(
        !stderr.contains(WARNING_SIGNATURE),
        "absent minCliVersion → guard must stay silent; stderr={:?}",
        stderr,
    );
    assert!(
        !stdout.contains(WARNING_SIGNATURE),
        "guard warning must never reach stdout; stdout={:?}",
        stdout,
    );
}

// ── d15: silent when minCliVersion ≤ CLI version (newer/equal → Ok) ───────────

#[tokio::test]
async fn older_min_cli_version_emits_no_warning() {
    let daemon = Daemon::spawn("vg-ok.sock");
    let port = daemon.port();
    drive_sdk(port, &[HELLO_MIN_OLD]).await;
    tokio::time::sleep(Duration::from_millis(150)).await;

    let (stdout, stderr) = daemon.shutdown_and_collect();

    assert!(
        !stderr.contains(WARNING_SIGNATURE),
        "minCliVersion 0.0.1 (≤ CLI {CLI_VERSION}) → guard must stay silent; stderr={:?}",
        stderr,
    );
    assert!(
        !stdout.contains(WARNING_SIGNATURE),
        "guard warning must never reach stdout; stdout={:?}",
        stdout,
    );
}

// ── d14 once-per-connection gate: hello.is_none() — a re-hello must not re-warn

#[tokio::test]
async fn stale_warning_fires_at_most_once_per_connection() {
    let daemon = Daemon::spawn("vg-once.sock");
    let port = daemon.port();
    // Two stale hellos on the SAME connection with DIFFERENT client ids — proves
    // the once-per-connection gate is the local `hello.is_none()` flag, not
    // clientId-based deduplication. Only the first hello may warn.
    let h1 = serde_json::json!({
        "type": "hello",
        "clientId": "c-once-1",
        "appPackage": "com.example",
        "deviceModel": "Pixel",
        "platform": "android",
        "minCliVersion": "99.0.0",
    })
    .to_string();
    let h2 = serde_json::json!({
        "type": "hello",
        "clientId": "c-once-2",
        "appPackage": "com.example",
        "deviceModel": "Pixel",
        "platform": "android",
        "minCliVersion": "99.0.0",
    })
    .to_string();
    drive_sdk(port, &[&h1, &h2]).await;
    tokio::time::sleep(Duration::from_millis(150)).await;

    let (_stdout, stderr) = daemon.shutdown_and_collect();

    let occurrences = stderr.matches(WARNING_SIGNATURE).count();
    assert_eq!(
        occurrences, 1,
        "stale warning must fire exactly once per connection even on a re-hello \
         with a different clientId; got {occurrences} occurrences in stderr={stderr:?}",
    );
}
