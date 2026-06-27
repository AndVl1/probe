//! Integration tests for the agent control plane: the Unix-domain-socket
//! server (`run_control_listener`) that answers start/dump/end/status/shutdown.
//!
//! These tests exercise the typed request/response protocol directly over a
//! real `UnixListener` bound to a temp path. The WS serve path is covered by
//! `integration.rs`; here we focus on cursor/session/gap semantics.

#![cfg(unix)]

use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::json;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::broadcast;

use devlens::buffer::EventBuffer;
use devlens::control::{
    ControlState, DaemonInfo, Request, Response, run_control_listener,
};

struct Harness {
    buffer: Arc<EventBuffer>,
    socket: std::path::PathBuf,
    _shutdown_tx: broadcast::Sender<()>,
    _dir: tempfile::TempDir,
}

/// Spin up a control listener on a temp socket. The default session is seeded
/// at the buffer's current high watermark (0 for an empty buffer), mirroring
/// how `run_server` initializes state.
async fn harness(capacity: usize) -> Harness {
    let dir = tempfile::tempdir().expect("tempdir");
    let socket = dir.path().join("ctrl.sock");
    let listener = UnixListener::bind(&socket).expect("bind");
    let buffer = Arc::new(EventBuffer::new(capacity));
    let state = Arc::new(Mutex::new(ControlState::with_default_cursor(
        buffer.high_watermark(),
    )));
    let info = Arc::new(DaemonInfo {
        pid: 1234,
        port: 8484,
        socket_path: socket.display().to_string(),
        started_at: 1_700_000_000_000,
    });
    let (shutdown_tx, _) = broadcast::channel::<()>(8);
    {
        let (buf, st, inf) = (Arc::clone(&buffer), Arc::clone(&state), Arc::clone(&info));
        let stx = shutdown_tx.clone();
        tokio::spawn(async move { run_control_listener(listener, buf, st, inf, stx).await; });
    }
    Harness {
        buffer,
        socket,
        _shutdown_tx: shutdown_tx,
        _dir: dir,
    }
}

/// Send one request, read the typed header, then read `count` body lines.
async fn request(socket: &std::path::Path, req: &Request) -> (Response, Vec<String>) {
    let mut stream = UnixStream::connect(socket).await.expect("connect");
    let line = serde_json::to_string(req).expect("serialize request");
    stream.write_all(line.as_bytes()).await.expect("write req");
    stream.write_all(b"\n").await.expect("write newline");
    stream.flush().await.expect("flush");

    let mut reader = BufReader::new(stream);
    let mut header = String::new();
    reader.read_line(&mut header).await.expect("read header");
    let resp: Response = serde_json::from_str(header.trim()).expect("parse header");
    let count = match &resp {
        Response::Ok { count, .. } => *count,
        _ => 0,
    };
    let mut body = Vec::new();
    for _ in 0..count {
        let mut l = String::new();
        reader.read_line(&mut l).await.expect("read body line");
        body.push(l.trim().to_string());
    }
    (resp, body)
}

// ── dump round-trip ───────────────────────────────────────────────────────────

#[tokio::test]
async fn dump_returns_all_buffered_entries_as_ndjson() {
    let h = harness(100).await;
    h.buffer.push(json!({"type":"connect","peer":"127.0.0.1:1"}));
    h.buffer.push(json!({"type":"event","plugin":"network","payload":{"id":"tx-1"}}));
    h.buffer.push(json!({"type":"event","plugin":"network","payload":{"id":"tx-2"}}));

    let (resp, body) = request(&h.socket, &Request::Dump { session: None }).await;
    match resp {
        Response::Ok { op, count, dropped } => {
            assert_eq!(op, "dump");
            assert_eq!(count, 3, "default cursor=0 → all three entries returned");
            assert_eq!(dropped, 0, "no eviction");
        }
        other => panic!("expected Ok, got {:?}", other),
    }

    // Each body line must be valid JSON with the expected `type` ordering.
    let types: Vec<String> = body
        .iter()
        .map(|l| {
            let v: serde_json::Value = serde_json::from_str(l).expect("body line is JSON");
            v["type"].as_str().unwrap().to_string()
        })
        .collect();
    assert_eq!(types, vec!["connect", "event", "event"]);
}

// ── DoD3: dump advances the cursor (disjoint, no duplicates) ────────────────

#[tokio::test]
async fn dump_advances_cursor_disjoint_batches() {
    let h = harness(100).await;
    h.buffer.push(json!({"type":"event","payload":{"n":1}}));
    h.buffer.push(json!({"type":"event","payload":{"n":2}}));

    let (resp1, body1) = request(&h.socket, &Request::Dump { session: None }).await;
    assert_eq!(body1.len(), 2);

    // more events land between dumps
    h.buffer.push(json!({"type":"event","payload":{"n":3}}));
    h.buffer.push(json!({"type":"event","payload":{"n":4}}));

    let (resp2, body2) = request(&h.socket, &Request::Dump { session: None }).await;
    assert_eq!(body2.len(), 2, "second dump must be disjoint from the first");

    fn ids(body: &[String]) -> Vec<i64> {
        body.iter()
            .map(|l| {
                let v: serde_json::Value =
                    serde_json::from_str(l).expect("body line is JSON");
                v["payload"]["n"].as_i64().expect("n field")
            })
            .collect()
    }
    assert_eq!(ids(&body1), vec![1, 2]);
    assert_eq!(ids(&body2), vec![3, 4]);

    // Silence unused respN warnings (they were asserted via body counts).
    let _ = (resp1, resp2);
}

// ── DoD4: dump without prior start works ────────────────────────────────────

#[tokio::test]
async fn dump_without_start_returns_events_since_start() {
    let h = harness(100).await;
    h.buffer.push(json!({"type":"event","payload":{"id":"a"}}));
    h.buffer.push(json!({"type":"event","payload":{"id":"b"}}));

    let (resp, body) = request(&h.socket, &Request::Dump { session: None }).await;
    match resp {
        Response::Ok { count, .. } => assert_eq!(count, 2),
        other => panic!("expected Ok, got {:?}", other),
    }
    assert_eq!(body.len(), 2);
}

// ── DoD6: gap marker on overflow ─────────────────────────────────────────────

#[tokio::test]
async fn dump_emits_gap_envelope_when_buffer_overflowed() {
    let h = harness(3).await;
    // default cursor=0 (buffer empty at setup). Push 5 → seq 1,2 evicted.
    for i in 1..=5 {
        h.buffer.push(json!({"type":"event","payload":{"n":i}}));
    }

    let (resp, body) = request(&h.socket, &Request::Dump { session: None }).await;
    match resp {
        Response::Ok { op, count, dropped } => {
            assert_eq!(op, "dump");
            assert_eq!(dropped, 2, "seqs 1 and 2 were lost to eviction");
            // body = [gap, event-3, event-4, event-5]
            assert_eq!(count, 4);
        }
        other => panic!("expected Ok, got {:?}", other),
    }

    // First body line is the gap envelope; remaining are the surviving events.
    let gap: serde_json::Value = serde_json::from_str(&body[0]).unwrap();
    assert_eq!(gap["type"], "gap");
    assert_eq!(gap["dropped"], 2);

    let survivors: Vec<i64> = body[1..]
        .iter()
        .map(|l| serde_json::from_str::<serde_json::Value>(l).unwrap()["payload"]["n"].as_i64().unwrap())
        .collect();
    assert_eq!(survivors, vec![3, 4, 5]);
}

// ── start resets cursor ──────────────────────────────────────────────────────

#[tokio::test]
async fn start_repositions_cursor_to_high_watermark() {
    let h = harness(100).await;
    h.buffer.push(json!({"type":"event","payload":{"n":1}}));
    h.buffer.push(json!({"type":"event","payload":{"n":2}}));

    // Start a named session: its cursor is positioned at the current high
    // watermark (2) → subsequent dump returns only events pushed after this.
    let (resp, _) = request(
        &h.socket,
        &Request::Start { session: Some("recording".into()), reset: false },
    )
    .await;
    assert!(matches!(resp, Response::Ok { .. }));

    h.buffer.push(json!({"type":"event","payload":{"n":3}}));
    // Unrelated push that the named session must NOT see pre-start... actually
    // seq3 is the only post-start event; verify the session skips seq1,2.
    let (resp, body) = request(
        &h.socket,
        &Request::Dump { session: Some("recording".into()) },
    )
    .await;
    match resp {
        Response::Ok { count, .. } => assert_eq!(count, 1, "only the post-start event"),
        other => panic!("expected Ok, got {:?}", other),
    }
    let v: serde_json::Value = serde_json::from_str(&body[0]).unwrap();
    assert_eq!(v["payload"]["n"], 3);
}

#[tokio::test]
async fn start_reset_on_default_repositions_cursor() {
    // The default session always exists (seeded at daemon start). `start
    // --reset` on it must reposition the cursor rather than error out.
    let h = harness(100).await;
    h.buffer.push(json!({"type":"event","payload":{"n":1}}));
    h.buffer.push(json!({"type":"event","payload":{"n":2}}));

    let (resp, _) = request(&h.socket, &Request::Start { session: None, reset: true }).await;
    assert!(matches!(resp, Response::Ok { .. }));

    h.buffer.push(json!({"type":"event","payload":{"n":3}}));
    let (resp, body) = request(&h.socket, &Request::Dump { session: None }).await;
    match resp {
        Response::Ok { count, .. } => assert_eq!(count, 1, "only post-reset event"),
        other => panic!("expected Ok, got {:?}", other),
    }
    let v: serde_json::Value = serde_json::from_str(&body[0]).unwrap();
    assert_eq!(v["payload"]["n"], 3);
}

// ── named-session errors ─────────────────────────────────────────────────────

#[tokio::test]
async fn dump_named_session_not_found_returns_error_code_2() {
    let h = harness(100).await;
    let (resp, _body) = request(
        &h.socket,
        &Request::Dump { session: Some("nope".into()) },
    )
    .await;
    match resp {
        Response::Error { op, code, .. } => {
            assert_eq!(op, "dump");
            assert_eq!(code, devlens::control::exit::NOT_FOUND);
        }
        other => panic!("expected Error, got {:?}", other),
    }
}

#[tokio::test]
async fn start_existing_session_without_reset_returns_error_code_3() {
    let h = harness(100).await;

    let (first, _) = request(
        &h.socket,
        &Request::Start { session: Some("s1".into()), reset: false },
    )
    .await;
    assert!(matches!(first, Response::Ok { .. }));

    let (again, _) = request(
        &h.socket,
        &Request::Start { session: Some("s1".into()), reset: false },
    )
    .await;
    match again {
        Response::Error { code, .. } => assert_eq!(code, devlens::control::exit::EXISTS),
        other => panic!("expected Error, got {:?}", other),
    }

    // --reset repositions even if the session exists.
    let (reset, _) = request(
        &h.socket,
        &Request::Start { session: Some("s1".into()), reset: true },
    )
    .await;
    assert!(matches!(reset, Response::Ok { .. }));
}

// ── status ───────────────────────────────────────────────────────────────────

#[tokio::test]
async fn status_reports_daemon_info_and_sessions() {
    let h = harness(100).await;
    let (resp, _body) = request(&h.socket, &Request::Status).await;
    match resp {
        Response::Status { pid, port, socket_path, sessions, .. } => {
            assert_eq!(pid, 1234);
            assert_eq!(port, 8484);
            assert!(socket_path.contains("ctrl.sock"));
            assert!(sessions.contains_key("default"), "default session seeded");
        }
        other => panic!("expected Status, got {:?}", other),
    }
}

// ── shutdown ─────────────────────────────────────────────────────────────────

#[tokio::test]
async fn shutdown_brings_down_the_control_listener() {
    let h = harness(100).await;
    let (resp, _) = request(&h.socket, &Request::Shutdown).await;
    assert!(matches!(resp, Response::Ok { .. }));

    // Give the listener task a moment to observe the broadcast and drop the
    // UnixListener, after which new connects must fail.
    tokio::time::sleep(Duration::from_millis(150)).await;
    let reconnect = UnixStream::connect(&h.socket).await;
    assert!(
        reconnect.is_err(),
        "listener should be gone after shutdown; connect must fail"
    );
}

// ── control_client wrapper exit codes ────────────────────────────────────────

#[tokio::test]
async fn control_client_run_command_translates_exit_codes() {
    use devlens::control_client::run_command;

    let h = harness(100).await;

    // not-found named session → exit code 2
    let code = run_command(
        &h.socket,
        Request::Dump { session: Some("missing".into()) },
    )
    .await
    .expect("client ok");
    assert_eq!(code, devlens::control::exit::NOT_FOUND);

    // existing session w/o reset → exit code 3
    run_command(&h.socket, Request::Start { session: Some("dup".into()), reset: false })
        .await
        .expect("client ok");
    let code = run_command(
        &h.socket,
        Request::Start { session: Some("dup".into()), reset: false },
    )
    .await
    .expect("client ok");
    assert_eq!(code, devlens::control::exit::EXISTS);

    // successful dump → exit code 0
    h.buffer.push(json!({"type":"event","payload":{"n":1}}));
    let code = run_command(&h.socket, Request::Dump { session: None })
        .await
        .expect("client ok");
    assert_eq!(code, devlens::control::exit::OK);
}

// ── end-to-end smoke: real `devlens` binary ─────────────────────────────────
//
// Spawns the actual binary as `devlens serve`, then exercises `status` and
// `shutdown` against it. HOME is redirected to a temp dir so the test never
// touches the user's real ~/.devlens/daemon.json.

#[tokio::test]
async fn smoke_serve_status_shutdown_via_binary() {
    use std::process::{Command, Stdio};
    use std::time::Instant;

    let dir = tempfile::tempdir().expect("tempdir");
    let sock = dir.path().join("serve.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port")
        .arg("0")
        .arg("--control-socket")
        .arg(&sock)
        .arg("--no-color")
        .env("HOME", dir.path())
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("spawn devlens serve");

    // Wait for the control socket to appear (daemon readiness).
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if sock.exists() {
            break;
        }
        if Instant::now() > deadline {
            let _ = serve.kill();
            panic!("daemon did not open its control socket in time");
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    // Extra yield so the control listener task is guaranteed to be running.
    tokio::time::sleep(Duration::from_millis(100)).await;

    // devlens status → exit 0, mentions daemon/pid.
    let status_out = Command::new(bin)
        .arg("status")
        .arg("--control-socket")
        .arg(&sock)
        .env("HOME", dir.path())
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output()
        .expect("spawn devlens status");
    assert!(
        status_out.status.success(),
        "status should exit 0 (stderr: {})",
        String::from_utf8_lossy(&status_out.stderr)
    );
    let stdout = String::from_utf8_lossy(&status_out.stdout);
    assert!(stdout.contains("devlens daemon"), "status stdout: {}", stdout);
    assert!(stdout.contains("pid:"), "status stdout: {}", stdout);

    // devlens shutdown → exit 0.
    let shut = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket")
        .arg(&sock)
        .env("HOME", dir.path())
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output()
        .expect("spawn devlens shutdown");
    assert!(
        shut.status.success(),
        "shutdown should exit 0 (stderr: {})",
        String::from_utf8_lossy(&shut.stderr)
    );

    // The serve process must terminate on its own after shutdown.
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match serve.try_wait().expect("try_wait on serve") {
            Some(_exit_status) => return,
            None => {
                if Instant::now() > deadline {
                    let _ = serve.kill();
                    panic!("daemon did not exit after shutdown");
                }
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        }
    }
}

// ── bind() tiebreak: second serve on a busy socket exits code 4 ───────────────
//
// When the control socket is already held, a second `devlens serve` must exit
// with code 4 (RUNNING = "daemon already running"), never 1. The live-peer
// branch (liveness probe connect succeeds) was already correct; this test
// pins the bind() loser branch, which previously wrapped AddrInUse in anyhow
// and leaked out as exit 1.
//
// The genuine two-serve failure is a TOCTOU window (peer binds between our
// liveness probe and our bind) that a deterministic test cannot reliably win,
// so we inject the loser state directly: a directory at the socket path makes
// the liveness probe's connect fail (ENOTSOCK) and its remove_file recovery
// a no-op (unlink on a directory returns EISDIR, ignored via `let _ =`), so
// the path survives to bind(), which returns AddrInUse — the exact arm the
// fix targets.

#[tokio::test]
async fn second_serve_on_busy_control_socket_exits_running() {
    use std::io::Read;
    use std::process::{Command, Stdio};
    use std::time::Instant;

    let dir = tempfile::tempdir().expect("tempdir");
    let sock = dir.path().join("busy.sock");
    std::fs::create_dir(&sock).expect("place a dir at the socket path to force bind AddrInUse");

    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port")
        .arg("0")
        .arg("--control-socket")
        .arg(&sock)
        .arg("--no-color")
        .env("HOME", dir.path())
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn devlens serve");

    // bind() failure fires synchronously during control-plane setup, so the
    // process should exit promptly. Poll with a deadline so a regression
    // (e.g. the daemon hanging instead of exiting) fails the test fast.
    let deadline = Instant::now() + Duration::from_secs(5);
    let status = loop {
        match serve.try_wait().expect("try_wait on serve") {
            Some(s) => break s,
            None => {
                if Instant::now() > deadline {
                    let _ = serve.kill();
                    panic!("serve did not exit within 5s; expected prompt exit 4");
                }
                tokio::time::sleep(Duration::from_millis(25)).await;
            }
        }
    };

    let mut stderr = String::new();
    if let Some(mut s) = serve.stderr.take() {
        let _ = s.read_to_string(&mut stderr);
    }

    assert_eq!(
        status.code(),
        Some(devlens::control::exit::RUNNING as i32),
        "bind() loser must exit code 4 (RUNNING), got {:?}; stderr={}",
        status.code(),
        stderr,
    );
}
