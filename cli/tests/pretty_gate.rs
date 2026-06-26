//! Pretty-gate integration tests (DoD1/7/8/10).
//!
//! Spawns the real `devlens` binary, drives SDK WebSocket activity at it, and
//! inspects the daemon's stdout to prove:
//!   - default mode (no `--pretty`, no `DEVLENS_PRETTY`) writes nothing to
//!     stdout while events flow (agent daemon is stream-silent), and
//!   - `--pretty` / `DEVLENS_PRETTY=1` reproduce the legacy colored output.
//!
//! HOME is redirected to a temp dir so the test never touches the user's real
//! `~/.devlens/daemon.json`.

#![cfg(unix)]

use std::io::Read;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::{connect_async, tungstenite::Message};

const HELLO: &str = r#"{"type":"hello","clientId":"c-1","appPackage":"com.example","deviceModel":"Pixel","platform":"android"}"#;
const EVENT: &str = r#"{"type":"event","plugin":"network","timestamp":1,"payload":{"id":"tx-1","timestamp":1,"method":"GET","url":"https://api.example.com/users","requestHeaders":{},"requestSizeBytes":0,"responseCode":200,"responseHeaders":{},"responseSizeBytes":42,"durationMs":12}}"#;

/// Connect to the daemon, send hello + a network event, then close. Retries the
/// connect briefly to absorb the race between bind and the test client.
async fn drive_sdk_activity(port: u16) {
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
    ws.send(Message::Text(HELLO.into())).await.unwrap();
    ws.send(Message::Text(EVENT.into())).await.unwrap();
    let _ = ws.send(Message::Close(None)).await;
    while let Some(Ok(_)) = ws.next().await {}
}

/// Read the daemon's actual bound port from `$HOME/.devlens/daemon.json`,
/// retrying briefly while the file is written.
fn read_port_from_daemon_json(home: &std::path::Path) -> u16 {
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

/// Spawn `devlens serve` (with `--port 0`), wait for readiness, drive SDK
/// activity, shut it down, and return everything the daemon wrote to stdout
/// during its lifetime. Uses `--port 0` + daemon.json to avoid free-port races
/// between parallel tests.
async fn run_daemon_and_collect_stdout(
    extra_args: &[&str],
    extra_env: &[(&str, &str)],
) -> String {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("d.sock");
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
        .args(extra_args);
    for (k, v) in extra_env {
        cmd.env(k, v);
    }
    cmd.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());

    let mut serve = cmd.spawn().expect("spawn devlens serve");

    // Wait for the control socket (daemon readiness).
    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "daemon control socket never appeared");

    // Discover the actual WS port from daemon.json, then drive activity.
    let port = read_port_from_daemon_json(&home);
    drive_sdk_activity(port).await;
    tokio::time::sleep(Duration::from_millis(300)).await;

    // Shut the daemon down so its stdout pipe sees EOF and we can read it all.
    let shut = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket")
        .arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status()
        .expect("spawn devlens shutdown");
    assert!(shut.success(), "shutdown subcommand failed");

    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match serve.try_wait().expect("try_wait") {
            Some(_) => break,
            None if Instant::now() < deadline => {
                tokio::time::sleep(Duration::from_millis(30)).await;
            }
            None => {
                let _ = serve.kill();
                panic!("daemon did not exit after shutdown");
            }
        }
    }

    let mut buf = String::new();
    serve
        .stdout
        .take()
        .expect("piped stdout")
        .read_to_string(&mut buf)
        .expect("read daemon stdout");
    buf
}

// ── DoD1 / DoD8: bare daemon is stream-silent on stdout ─────────────────────

#[tokio::test]
async fn default_mode_stdout_is_silent_during_sdk_activity() {
    let stdout = run_daemon_and_collect_stdout(&[], &[]).await;
    assert!(
        stdout.is_empty(),
        "default daemon stdout must be empty (got: {:?})",
        stdout
    );
}

// ── DoD7: --pretty reproduces the legacy colored/human output ───────────────

#[tokio::test]
async fn pretty_flag_emits_human_output_to_stdout() {
    let stdout = run_daemon_and_collect_stdout(&["--pretty"], &[]).await;
    assert!(
        stdout.contains("Connected"),
        "pretty mode should print the connect line (got: {:?})",
        stdout
    );
    assert!(
        stdout.contains("api.example.com/users"),
        "pretty mode should print the transaction URL (got: {:?})",
        stdout
    );
}

// ── DoD10: DEVLENS_PRETTY=1 behaves like --pretty ───────────────────────────

#[tokio::test]
async fn pretty_env_equals_one_acts_like_pretty_flag() {
    let stdout = run_daemon_and_collect_stdout(&[], &[("DEVLENS_PRETTY", "1")]).await;
    assert!(
        stdout.contains("Connected"),
        "DEVLENS_PRETTY=1 should print the connect line (got: {:?})",
        stdout
    );
    assert!(
        stdout.contains("api.example.com/users"),
        "DEVLENS_PRETTY=1 should print the transaction URL (got: {:?})",
        stdout
    );
}
