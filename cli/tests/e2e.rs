//! End-to-end binary test: the full agent workflow against the real `devlens`
//! binary — `serve` → SDK WebSocket activity → `devlens dump` shows the
//! lifecycle+event NDJSON in order → a second `dump` is empty (cursor advanced)
//! → `shutdown`. This mirrors the emulator E2E workflow minus a physical device.

#![cfg(unix)]

use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::{connect_async, tungstenite::Message};

const HELLO: &str = r#"{"type":"hello","clientId":"c-1","appPackage":"com.example","deviceModel":"Pixel","platform":"android"}"#;
const EVENT: &str = r#"{"type":"event","plugin":"network","timestamp":1,"payload":{"id":"tx-1","timestamp":1,"method":"GET","url":"https://api.example.com/people","requestHeaders":{},"requestSizeBytes":0,"responseCode":200,"responseHeaders":{},"responseSizeBytes":42,"durationMs":12}}"#;

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
