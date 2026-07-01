//! End-to-end binary test: the full agent workflow against the real `devlens`
//! binary — `serve` → SDK WebSocket activity → `devlens dump` shows the
//! lifecycle+event NDJSON in order → a second `dump` is empty (cursor advanced)
//! → `shutdown`. This mirrors the emulator E2E workflow minus a physical device.

#![cfg(unix)]

use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use serde_json::json;
use tokio_tungstenite::{connect_async, tungstenite::Message};

const HELLO: &str = r#"{"type":"hello","clientId":"c-1","appPackage":"com.example","deviceModel":"Pixel","platform":"android"}"#;
const EVENT: &str = r#"{"type":"event","plugin":"network","timestamp":1,"payload":{"id":"tx-1","timestamp":1,"method":"GET","url":"https://api.example.com/people","requestHeaders":{},"requestSizeBytes":0,"responseCode":200,"responseHeaders":{},"responseSizeBytes":42,"durationMs":12}}"#;
const DB_EVENT: &str = r#"{"type":"event","plugin":"database","timestamp":2,"payload":{"query":"SELECT * FROM users"}}"#;

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
            panic!("could not read port from daemon.json");
        }
        std::thread::sleep(Duration::from_millis(30));
    }
}

#[tokio::test]
async fn e2e_serve_sdk_event_dump_shutdown() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("c.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port")
        .arg("0")
        .arg("--control-socket")
        .arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("spawn serve");

    // readiness
    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");

    // SDK activity: hello + one network event, then close.
    let port = read_port(&home);
    let url = format!("ws://127.0.0.1:{}", port);
    let connect_deadline = Instant::now() + Duration::from_secs(3);
    let mut ws = loop {
        match connect_async(&url).await {
            Ok((ws, _)) => break ws,
            Err(_) if Instant::now() < connect_deadline => {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            Err(e) => panic!("ws connect: {}", e),
        }
    };
    ws.send(Message::Text(HELLO.into())).await.unwrap();
    ws.send(Message::Text(EVENT.into())).await.unwrap();
    let _ = ws.send(Message::Close(None)).await;
    while let Some(Ok(_)) = ws.next().await {}
    tokio::time::sleep(Duration::from_millis(200)).await;

    // `devlens dump` → 4 NDJSON lines: connect, hello, event, disconnect.
    let dump = Command::new(bin)
        .arg("dump")
        .arg("--control-socket")
        .arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output()
        .expect("spawn dump");
    assert!(dump.status.success(), "dump should exit 0");
    assert!(
        dump.stderr.is_empty(),
        "dump stderr should be empty: {}",
        String::from_utf8_lossy(&dump.stderr)
    );
    let stdout = String::from_utf8_lossy(&dump.stdout);
    let lines: Vec<&str> = stdout.lines().filter(|l| !l.is_empty()).collect();
    assert_eq!(lines.len(), 4, "expected 4 NDJSON lines, got: {:?}", lines);

    let types: Vec<String> = lines
        .iter()
        .map(|l| {
            let v: serde_json::Value = serde_json::from_str(l).unwrap();
            v["type"].as_str().unwrap().to_string()
        })
        .collect();
    assert_eq!(
        types,
        vec!["connect", "hello", "event", "disconnect"]
            .into_iter()
            .map(String::from)
            .collect::<Vec<_>>(),
        "lifecycle must be interleaved with the event in chronological order"
    );

    // The network event carries the wire payload verbatim.
    let event_line: serde_json::Value = serde_json::from_str(lines[2]).unwrap();
    assert_eq!(event_line["plugin"], "network");
    assert_eq!(event_line["payload"]["url"], "https://api.example.com/people");

    // Second dump is empty (cursor advanced) — DoD3 disjoint/no-duplicates.
    let dump2 = Command::new(bin)
        .arg("dump")
        .arg("--control-socket")
        .arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output()
        .expect("spawn dump2");
    assert!(dump2.status.success());
    let stdout2 = String::from_utf8_lossy(&dump2.stdout);
    let lines2: Vec<&str> = stdout2.lines().filter(|l| !l.is_empty()).collect();
    assert!(lines2.is_empty(), "second dump must be empty, got: {:?}", lines2);

    // shutdown → daemon exits 0.
    let shut = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket")
        .arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status()
        .expect("spawn shutdown");
    assert!(shut.success());

    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match serve.try_wait().expect("try wait") {
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
}

// ─── DoD8: generalized buffering — a non-network event reaches `dump` ────────

/// A `{plugin:"database"}` event must reach `devlens dump` verbatim now that
/// server.rs buffers ALL plugin events (previously only `network` was buffered
/// and other plugins were dropped with an "unknown plugin" log).
#[tokio::test]
async fn e2e_database_event_reaches_dump() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("dbbuf.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");

    let port = read_port(&home);
    let url = format!("ws://127.0.0.1:{}", port);
    let connect_deadline = Instant::now() + Duration::from_secs(3);
    let mut ws = loop {
        match connect_async(&url).await {
            Ok((ws, _)) => break ws,
            Err(_) if Instant::now() < connect_deadline => {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            Err(e) => panic!("ws connect: {}", e),
        }
    };
    ws.send(Message::Text(HELLO.into())).await.unwrap();
    ws.send(Message::Text(DB_EVENT.into())).await.unwrap();
    let _ = ws.send(Message::Close(None)).await;
    while let Some(Ok(_)) = ws.next().await {}
    tokio::time::sleep(Duration::from_millis(200)).await;

    let dump = Command::new(bin)
        .arg("dump")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn dump");
    assert!(dump.status.success(), "dump should exit 0");
    let stdout = String::from_utf8_lossy(&dump.stdout);
    // The database event must be among the dumped lines, payload verbatim.
    let db_events: Vec<_> = stdout
        .lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .filter(|v| v["plugin"] == "database")
        .collect();
    assert!(!db_events.is_empty(), "database event should reach dump: {}", stdout);
    assert_eq!(db_events[0]["payload"]["query"], "SELECT * FROM users");

    let _ = serve.kill();
    let _ = serve.wait();
}

// ─── DoD9: db query round-trip with a mock SDK client ───────────────────────

/// Spawn a mock SDK on its own thread + runtime: connects, sends hello, then
/// loops reading frames and echoes a `queryResult` Event for every Query frame
/// it receives, carrying `result_body` as the `result`.
fn spawn_mock_sdk(port: u16, result_body: serde_json::Value) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("mock sdk runtime");
        rt.block_on(async move {
            let url = format!("ws://127.0.0.1:{}", port);
            let deadline = Instant::now() + Duration::from_secs(5);
            let mut ws = loop {
                match connect_async(&url).await {
                    Ok((ws, _)) => break ws,
                    Err(_) if Instant::now() < deadline => {
                        tokio::time::sleep(Duration::from_millis(50)).await;
                    }
                    Err(e) => panic!("mock sdk connect: {}", e),
                }
            };
            ws.send(Message::Text(HELLO.into())).await.unwrap();

            // Respond to inbound Query frames with a correlated queryResult.
            while let Some(Ok(msg)) = ws.next().await {
                let text = match msg {
                    Message::Text(t) => t,
                    Message::Close(_) => break,
                    _ => continue,
                };
                let v: serde_json::Value = match serde_json::from_str(&text) {
                    Ok(v) => v,
                    Err(_) => continue,
                };
                if v["type"] == "query" {
                    let req_id = v["requestId"].as_str().unwrap().to_string();
                    let resp = json!({
                        "type": "event",
                        "plugin": "database",
                        "timestamp": 1,
                        "payload": {
                            "op": "queryResult",
                            "requestId": req_id,
                            "ok": true,
                            "result": result_body,
                        }
                    });
                    if ws.send(Message::Text(resp.to_string())).await.is_err() {
                        break;
                    }
                }
            }
            let _ = ws.close(None).await;
        });
    })
}

/// Variant of [`spawn_mock_sdk`] for the timeout path (H1): reads the Query
/// frame to prove the daemon forwarded it, then NEVER sends a queryResult, so
/// the dispatcher's timeout fires.
fn spawn_silent_mock_sdk(port: u16) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("mock sdk runtime");
        rt.block_on(async move {
            let url = format!("ws://127.0.0.1:{}", port);
            let deadline = Instant::now() + Duration::from_secs(10);
            let mut ws = loop {
                match connect_async(&url).await {
                    Ok((ws, _)) => break ws,
                    Err(_) if Instant::now() < deadline => {
                        tokio::time::sleep(Duration::from_millis(50)).await;
                    }
                    Err(e) => panic!("mock sdk connect: {}", e),
                }
            };
            ws.send(Message::Text(HELLO.into())).await.unwrap();

            // Read the Query frame, then stay pointedly silent. Loop until the
            // daemon tears down the WS (timeout path closes the connection).
            while let Some(Ok(msg)) = ws.next().await {
                match msg {
                    Message::Text(t) => {
                        // Confirm the frame is a Query so a regression that
                        // forwards the wrong frame type surfaces here.
                        let v: serde_json::Value = match serde_json::from_str(&t) {
                            Ok(v) => v,
                            Err(_) => continue,
                        };
                        if v["type"] == "query" {
                            // Intentionally swallow: no queryResult reply.
                        }
                    }
                    Message::Close(_) => break,
                    _ => continue,
                }
            }
            let _ = ws.close(None).await;
        });
    })
}

/// `devlens db list-databases` → the daemon forwards the Query to the connected
/// mock SDK, which echoes a `queryResult`; the agent receives the result as one
/// NDJSON line on stdout and the command exits 0.
#[tokio::test]
async fn e2e_db_list_databases_round_trip() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("dbq.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");

    let port = read_port(&home);
    let result_body = json!({
        "databases": [
            {"name": "app.db", "path": "/data/data/com.example/databases/app.db",
             "sizeBytes": 16384, "encrypted": false, "skipped": false}
        ]
    });
    let mock = spawn_mock_sdk(port, result_body);
    // Let the mock SDK connect and send hello before issuing the query.
    tokio::time::sleep(Duration::from_millis(400)).await;

    let out = Command::new(bin)
        .arg("db").arg("list-databases")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn db list-databases");

    assert!(
        out.status.success(),
        "db list-databases should exit 0 (got {:?}); stderr: {}",
        out.status.code(),
        String::from_utf8_lossy(&out.stderr)
    );
    let stdout = String::from_utf8_lossy(&out.stdout);
    let lines: Vec<&str> = stdout.lines().filter(|l| !l.is_empty()).collect();
    assert_eq!(lines.len(), 1, "exactly one NDJSON body line, got: {:?}", lines);
    let body: serde_json::Value = serde_json::from_str(lines[0]).expect("body is JSON");
    assert_eq!(body["databases"][0]["name"], "app.db");
    assert_eq!(body["databases"][0]["sizeBytes"], 16384);

    // shutdown
    let _ = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status();
    let _ = serve.wait();
    // mock sdk thread exits when the daemon closes its WS.
    let _ = mock.join();
}

/// `devlens db list-databases` with NO SDK client connected → exit code 5
/// (NO_CLIENT), and the body identifies the failure mode.
#[tokio::test]
async fn e2e_db_no_client_exits_5() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("dbnc.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");
    // No WS client connects. Use a short query timeout so the no-client path
    // fires immediately (NoClient is raised before any timeout wait).
    let out = Command::new(bin)
        .arg("db").arg("list-databases")
        .arg("--control-socket").arg(&sock)
        .arg("--query-timeout-ms").arg("500")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn db list-databases");

    assert_eq!(
        out.status.code(),
        Some(5),
        "no-client db query must exit 5 (NO_CLIENT); stdout={} stderr={}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr),
    );
    // The single NDJSON body line carries {"code":"no_client"}.
    let stdout = String::from_utf8_lossy(&out.stdout);
    let body: serde_json::Value =
        serde_json::from_str(stdout.trim()).expect("body is JSON");
    assert_eq!(body["code"], "no_client");

    let _ = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status();
    let _ = serve.wait();
}

// ─── H1: silent SDK → timeout exits 6 with body {"code":"timeout"} ──────────

/// `devlens db list-databases --query-timeout-ms 500` against a connected but
/// silent SDK must exit 6 (TIMEOUT) and print exactly one NDJSON body line
/// `{"code":"timeout"}`. Also asserts the per-invocation timeout is honored
/// (well under the 10s daemon default) — M3 contract verification.
#[tokio::test]
async fn e2e_db_timeout_exits_6() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("dbto.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");

    let port = read_port(&home);
    let mock = spawn_silent_mock_sdk(port);
    // Let the mock SDK connect and send hello before issuing the query.
    tokio::time::sleep(Duration::from_millis(400)).await;

    let started = Instant::now();
    let out = Command::new(bin)
        .arg("db").arg("list-databases")
        .arg("--control-socket").arg(&sock)
        .arg("--query-timeout-ms").arg("500")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn db list-databases");
    let elapsed = started.elapsed();

    assert_eq!(
        out.status.code(),
        Some(6),
        "silent SDK must time out to exit 6 (TIMEOUT); stdout={} stderr={}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr),
    );
    let stdout = String::from_utf8_lossy(&out.stdout);
    let lines: Vec<&str> = stdout.lines().filter(|l| !l.is_empty()).collect();
    assert_eq!(lines.len(), 1, "exactly one NDJSON body line, got: {:?}", lines);
    let body: serde_json::Value = serde_json::from_str(lines[0]).expect("body is JSON");
    assert_eq!(body["code"], "timeout");

    // M3 verification: the per-invocation 500ms timeout actually shortened
    // the wait. The daemon-side default is 10s, so anything below ~3s proves
    // the override took effect (allow generous slack for CI scheduler jitter).
    assert!(
        elapsed < Duration::from_secs(3),
        "per-invocation --query-timeout-ms=500 should fire well under 10s \
         daemon default; elapsed={:?}",
        elapsed,
    );

    let _ = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status();
    let _ = serve.wait();
    let _ = mock.join();
}

// ─── T5: plugin error (ok:false) → exit 7 with the error code on stdout ─────

/// A mock SDK that answers the Query with `{ok:false, error:{code, message}}`
/// must surface as exit code 7 (PLUGIN_ERROR), with the error object on stdout
/// so the agent can read `code` and `message`. Exercises the full control plane
/// → control_client exit-code mapping for the plugin-error branch.
fn spawn_plugin_error_mock_sdk(
    port: u16,
    error_code: &'static str,
    error_message: &'static str,
) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("mock sdk runtime");
        rt.block_on(async move {
            let url = format!("ws://127.0.0.1:{}", port);
            let deadline = Instant::now() + Duration::from_secs(5);
            let mut ws = loop {
                match connect_async(&url).await {
                    Ok((ws, _)) => break ws,
                    Err(_) if Instant::now() < deadline => {
                        tokio::time::sleep(Duration::from_millis(50)).await;
                    }
                    Err(e) => panic!("mock sdk connect: {}", e),
                }
            };
            ws.send(Message::Text(HELLO.into())).await.unwrap();

            while let Some(Ok(msg)) = ws.next().await {
                let text = match msg {
                    Message::Text(t) => t,
                    Message::Close(_) => break,
                    _ => continue,
                };
                let v: serde_json::Value = match serde_json::from_str(&text) {
                    Ok(v) => v,
                    Err(_) => continue,
                };
                if v["type"] == "query" {
                    let req_id = v["requestId"].as_str().unwrap().to_string();
                    let resp = json!({
                        "type": "event",
                        "plugin": "database",
                        "timestamp": 1,
                        "payload": {
                            "op": "queryResult",
                            "requestId": req_id,
                            "ok": false,
                            "error": {
                                "code": error_code,
                                "message": error_message,
                            }
                        }
                    });
                    if ws.send(Message::Text(resp.to_string())).await.is_err() {
                        break;
                    }
                }
            }
            let _ = ws.close(None).await;
        });
    })
}

#[tokio::test]
async fn e2e_db_plugin_error_exits_7() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("dbe.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");

    let port = read_port(&home);
    let mock = spawn_plugin_error_mock_sdk(port, "table_not_found", "no such table: widgets");
    tokio::time::sleep(Duration::from_millis(400)).await;

    let out = Command::new(bin)
        .arg("db").arg("inspect")
        .arg("--database").arg("app.db")
        .arg("--table").arg("widgets")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn db inspect");

    assert_eq!(
        out.status.code(),
        Some(7),
        "plugin error must surface as exit 7 (PLUGIN_ERROR); stdout={} stderr={}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr),
    );
    let stdout = String::from_utf8_lossy(&out.stdout);
    let lines: Vec<&str> = stdout.lines().filter(|l| !l.is_empty()).collect();
    assert_eq!(lines.len(), 1, "exactly one NDJSON body line, got: {:?}", lines);
    let body: serde_json::Value = serde_json::from_str(lines[0]).expect("body is JSON");
    // The control plane forwards the SDK's `error` object verbatim.
    assert_eq!(body["code"], "table_not_found", "body={}", body);
    assert_eq!(body["message"], "no such table: widgets");

    let _ = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status();
    let _ = serve.wait();
    let _ = mock.join();
}

// ─── Network mock subcommands (DoD-1, DoD-2, DoD-3, DoD-4) ────────────────────
//
// The `devlens mock ...` subcommands ride the exact same control plane as `db`
// (Request::Query → Response::QueryResult); only the plugin ("network") and
// method names differ. The queryResult router ignores the echo's `plugin` field,
// so we reuse spawn_mock_sdk (which echoes a fixed result body for any Query).

/// `devlens mock add` → the daemon forwards a `{type:query,plugin:"network",
/// method:"setMock"}` frame to the connected SDK; the SDK's queryResult ack is
/// printed as one NDJSON line on stdout and the command exits 0. (DoD-1)
#[tokio::test]
async fn e2e_mock_add_round_trip() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("mockadd.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");

    let port = read_port(&home);
    // SDK ack shape for setMock: {"id":"rule-<uuid>","action":"set","activeCount":N}.
    let ack = json!({
        "id": "rule-test-1",
        "action": "set",
        "activeCount": 1
    });
    let mock = spawn_mock_sdk(port, ack);
    tokio::time::sleep(Duration::from_millis(400)).await;

    let out = Command::new(bin)
        .arg("mock").arg("add")
        .arg("--method").arg("GET")
        .arg("--url").arg("people/")
        .arg("--status").arg("200")
        .arg("--body").arg("{\"x\":1}")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn mock add");

    assert!(
        out.status.success(),
        "mock add should exit 0 (got {:?}); stderr: {}",
        out.status.code(),
        String::from_utf8_lossy(&out.stderr)
    );
    let stdout = String::from_utf8_lossy(&out.stdout);
    let lines: Vec<&str> = stdout.lines().filter(|l| !l.is_empty()).collect();
    assert_eq!(lines.len(), 1, "exactly one NDJSON body line, got: {:?}", lines);
    let body: serde_json::Value = serde_json::from_str(lines[0]).expect("body is JSON");
    assert_eq!(body["id"], "rule-test-1");
    assert_eq!(body["action"], "set");
    assert_eq!(body["activeCount"], 1);

    let _ = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status();
    let _ = serve.wait();
    let _ = mock.join();
}

/// `devlens mock list` → prints the current rule set as exactly one NDJSON line
/// on stdout and exits 0. (DoD-2)
#[tokio::test]
async fn e2e_mock_list_prints_ndjson() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("mocklist.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");

    let port = read_port(&home);
    // listMocks ack shape: {"rules":[<MockRule>...],"count":N}.
    let ack = json!({
        "rules": [
            {"id": "rule-1", "method": "GET", "url": "/people/", "status": 200}
        ],
        "count": 1
    });
    let mock = spawn_mock_sdk(port, ack);
    tokio::time::sleep(Duration::from_millis(400)).await;

    let out = Command::new(bin)
        .arg("mock").arg("list")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn mock list");

    assert!(
        out.status.success(),
        "mock list should exit 0 (got {:?}); stderr: {}",
        out.status.code(),
        String::from_utf8_lossy(&out.stderr)
    );
    let stdout = String::from_utf8_lossy(&out.stdout);
    let lines: Vec<&str> = stdout.lines().filter(|l| !l.is_empty()).collect();
    assert_eq!(lines.len(), 1, "exactly one NDJSON body line, got: {:?}", lines);
    let body: serde_json::Value = serde_json::from_str(lines[0]).expect("body is JSON");
    assert_eq!(body["count"], 1);
    assert_eq!(body["rules"][0]["id"], "rule-1");
    assert_eq!(body["rules"][0]["url"], "/people/");

    let _ = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status();
    let _ = serve.wait();
    let _ = mock.join();
}

/// `devlens mock clear` exits 0 on the SDK's ack (idempotent — always ok). This
/// mirrors DoD-3 for the clear subcommand; remove behaves identically (same
/// control path, proven by the unit tests for its Request mapping).
#[tokio::test]
async fn e2e_mock_clear_round_trip_exits_0() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("mockclear.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");

    let port = read_port(&home);
    // clearMocks ack shape: {"cleared":N,"activeCount":0}.
    let ack = json!({ "cleared": 2, "activeCount": 0 });
    let mock = spawn_mock_sdk(port, ack);
    tokio::time::sleep(Duration::from_millis(400)).await;

    let out = Command::new(bin)
        .arg("mock").arg("clear")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn mock clear");

    assert!(
        out.status.success(),
        "mock clear should exit 0 (got {:?}); stderr: {}",
        out.status.code(),
        String::from_utf8_lossy(&out.stderr)
    );
    let stdout = String::from_utf8_lossy(&out.stdout);
    let body: serde_json::Value = serde_json::from_str(stdout.trim()).expect("body is JSON");
    assert_eq!(body["cleared"], 2);
    assert_eq!(body["activeCount"], 0);

    let _ = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status();
    let _ = serve.wait();
    let _ = mock.join();
}

/// `devlens mock add` with NO SDK connected → exit code 5 (NO_CLIENT) and the
/// body identifies the failure mode. (DoD-4)
#[tokio::test]
async fn e2e_mock_add_no_client_exits_5() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("mocknc.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");
    // No WS client connects.
    let out = Command::new(bin)
        .arg("mock").arg("add")
        .arg("--method").arg("GET")
        .arg("--url").arg("people/")
        .arg("--status").arg("200")
        .arg("--control-socket").arg(&sock)
        .arg("--query-timeout-ms").arg("500")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn mock add");

    assert_eq!(
        out.status.code(),
        Some(5),
        "no-client mock add must exit 5 (NO_CLIENT); stdout={} stderr={}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr),
    );
    let stdout = String::from_utf8_lossy(&out.stdout);
    let body: serde_json::Value =
        serde_json::from_str(stdout.trim()).expect("body is JSON");
    assert_eq!(body["code"], "no_client");

    let _ = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status();
    let _ = serve.wait();
}

// ─── R3: mock remove round-trip (dispatched frame shape) ───────────────────
//
// `mock remove <id>` rides the same control path as `mock clear`, but the
// dispatched Query frame must carry method:"removeMock" and params.id. This
// captures the forwarded frame to assert both, complementing the unit-level
// map_command test with a full binary round-trip.

/// Variant of [`spawn_mock_sdk`] that also captures the first Query frame the
/// daemon forwards, so the caller can assert on the dispatched `method` and
/// `params` (not just the ack shape). Returns the join handle and the receiver
/// of a one-shot channel holding the captured frame once it arrives.
fn spawn_capturing_mock_sdk(
    port: u16,
    result_body: serde_json::Value,
) -> (
    std::thread::JoinHandle<()>,
    std::sync::mpsc::Receiver<serde_json::Value>,
) {
    let (tx, rx) = std::sync::mpsc::sync_channel(1);
    let handle = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("mock sdk runtime");
        rt.block_on(async move {
            let url = format!("ws://127.0.0.1:{}", port);
            let deadline = Instant::now() + Duration::from_secs(5);
            let mut ws = loop {
                match connect_async(&url).await {
                    Ok((ws, _)) => break ws,
                    Err(_) if Instant::now() < deadline => {
                        tokio::time::sleep(Duration::from_millis(50)).await;
                    }
                    Err(e) => panic!("mock sdk connect: {}", e),
                }
            };
            ws.send(Message::Text(HELLO.into())).await.unwrap();

            while let Some(Ok(msg)) = ws.next().await {
                let text = match msg {
                    Message::Text(t) => t,
                    Message::Close(_) => break,
                    _ => continue,
                };
                let v: serde_json::Value = match serde_json::from_str(&text) {
                    Ok(v) => v,
                    Err(_) => continue,
                };
                if v["type"] == "query" {
                    // Best-effort capture of the dispatched Query frame so the
                    // caller can assert method/params. try_send keeps the SDK
                    // thread non-blocking if the channel is already drained.
                    let _ = tx.try_send(v.clone());
                    let req_id = v["requestId"].as_str().unwrap().to_string();
                    let resp = json!({
                        "type": "event",
                        "plugin": "network",
                        "timestamp": 1,
                        "payload": {
                            "op": "queryResult",
                            "requestId": req_id,
                            "ok": true,
                            "result": result_body,
                        }
                    });
                    if ws.send(Message::Text(resp.to_string())).await.is_err() {
                        break;
                    }
                }
            }
            let _ = ws.close(None).await;
        });
    });
    (handle, rx)
}

/// `devlens mock remove <id>` → the daemon forwards a `{type:query,
/// plugin:"network", method:"removeMock"}` frame whose `params.id` carries the
/// caller's id; the SDK's queryResult ack prints as one NDJSON line and the
/// command exits 0. Mirrors `e2e_mock_clear_round_trip_exits_0` (DoD-3) but
/// also asserts the dispatched frame shape (R3).
#[tokio::test]
async fn e2e_mock_remove_round_trip() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("mockrm.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let mut serve = Command::new(bin)
        .arg("serve")
        .arg("--port").arg("0")
        .arg("--control-socket").arg(&sock)
        .arg("--no-color")
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .env_remove("DEVLENS_PRETTY")
        .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().expect("spawn serve");

    let deadline = Instant::now() + Duration::from_secs(5);
    while !sock.exists() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert!(sock.exists(), "control socket should appear");

    let port = read_port(&home);
    // removeMock ack shape: {"removed":true,"id":"<id>","activeCount":N}.
    let ack = json!({ "removed": true, "id": "rule-test-1", "activeCount": 0 });
    let (mock, captured_rx) = spawn_capturing_mock_sdk(port, ack);
    tokio::time::sleep(Duration::from_millis(400)).await;

    let out = Command::new(bin)
        .arg("mock").arg("remove").arg("rule-test-1")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn mock remove");

    assert!(
        out.status.success(),
        "mock remove should exit 0 (got {:?}); stderr: {}",
        out.status.code(),
        String::from_utf8_lossy(&out.stderr)
    );
    let stdout = String::from_utf8_lossy(&out.stdout);
    let body: serde_json::Value = serde_json::from_str(stdout.trim()).expect("body is JSON");
    assert_eq!(body["removed"], true);
    assert_eq!(body["id"], "rule-test-1");

    // R3: verify the dispatched Query frame targeted removeMock with the id.
    let frame = captured_rx
        .recv_timeout(Duration::from_secs(5))
        .expect("a Query frame should have been dispatched to the SDK");
    assert_eq!(frame["method"], "removeMock", "dispatched frame: {}", frame);
    assert_eq!(frame["params"]["id"], "rule-test-1", "dispatched frame: {}", frame);

    let _ = Command::new(bin)
        .arg("shutdown")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .status();
    let _ = serve.wait();
    let _ = mock.join();
}

// ─── R1/R4: exit-2 validation paths (MockSpecError + clap conflict) ──────────
//
// Validation short-circuits synchronously inside the binary before any daemon
// interaction: MockSpecError fires inside map_command (run before
// control_client::run_command), and the --error/--status conflict is rejected
// by clap during Args::parse. So these tests invoke `devlens mock add ...`
// against a control-socket path with no running daemon and assert exit 2.

/// `devlens mock add --url '   '` → the url trims to empty, so MockSpecError
/// fires: exit 2, a stderr line mentioning `url`, and no NDJSON ack on stdout
/// (proving the dispatch never happened). Covers R1.
#[test]
fn e2e_mock_add_blank_url_exits_2() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("val-blank.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let out = Command::new(bin)
        .arg("mock").arg("add")
        .arg("--url").arg("   ")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn mock add");

    assert_eq!(
        out.status.code(),
        Some(2),
        "blank url must exit 2 (VALIDATION); stdout={} stderr={}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr),
    );
    let stderr = String::from_utf8_lossy(&out.stderr);
    assert!(
        stderr.to_lowercase().contains("url"),
        "stderr should mention the url argument; got: {:?}",
        stderr,
    );
    let stdout = String::from_utf8_lossy(&out.stdout);
    assert!(
        stdout.trim().is_empty(),
        "stdout must carry no NDJSON ack line (dispatch never happened); got: {:?}",
        stdout,
    );
}

/// `devlens mock add --url x --error e --status 200` → clap rejects the
/// `--error`/`--status` combination (conflicts_with_all) during Args::parse
/// and exits 2 before any of our code runs. Covers R4.
#[test]
fn e2e_mock_add_error_status_conflict_exits_2() {
    let dir = tempfile::tempdir().expect("tempdir");
    let home = dir.path().to_path_buf();
    let sock = home.join("val-conflict.sock");
    let bin = env!("CARGO_BIN_EXE_devlens");

    let out = Command::new(bin)
        .arg("mock").arg("add")
        .arg("--url").arg("x")
        .arg("--error").arg("e")
        .arg("--status").arg("200")
        .arg("--control-socket").arg(&sock)
        .env("HOME", &home)
        .env_remove("XDG_RUNTIME_DIR")
        .env_remove("DEVLENS_SOCK")
        .output().expect("spawn mock add");

    assert_eq!(
        out.status.code(),
        Some(2),
        "--error + --status conflict must exit 2 (clap); stdout={} stderr={}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr),
    );
}
