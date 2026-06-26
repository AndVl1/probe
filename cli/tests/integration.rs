/// Integration tests: full WebSocket pipeline
///
/// Each test binds a random port, spawns a lightweight server loop using
/// `devlens::server::handle_connection`, connects via tokio-tungstenite, and
/// asserts on the shared `EventBuffer`.
use std::sync::{Arc, Mutex};
use std::time::Duration;
use futures_util::{SinkExt, StreamExt};
use tokio::net::TcpListener;
use tokio_tungstenite::{connect_async, tungstenite::Message as WsMessage};

use devlens::{
    Args,
    buffer::EventBuffer,
    display::Displayer,
    filter::Filter,
    server::handle_connection,
};

/// A pretty-mode displayer (colored stdout). Tests that don't assert on stdout
/// pass this in to mirror the legacy code path; tests asserting stdout silence
/// pass `None`.
fn pretty_displayer() -> Option<Arc<Displayer>> {
    Some(Arc::new(Displayer::new(false, false, true)))
}

// ── JSON fixtures ─────────────────────────────────────────────────────────────

const HELLO_MSG: &str = r#"{"type":"hello","clientId":"test-001","appPackage":"com.test.app","deviceModel":"Test Device","androidVersion":"13","platform":"android"}"#;

const NETWORK_EVENT_MSG: &str = r#"{"type":"event","plugin":"network","timestamp":1700000000,"payload":{"id":"tx-001","timestamp":1700000000,"method":"GET","url":"https://api.example.com/users","requestHeaders":{},"requestSizeBytes":0,"responseCode":200,"responseHeaders":{},"responseSizeBytes":1024,"durationMs":150}}"#;

const NETWORK_EVENT_OTHER_URL: &str = r#"{"type":"event","plugin":"network","timestamp":1700000001,"payload":{"id":"tx-002","timestamp":1700000001,"method":"GET","url":"https://other.com/data","requestHeaders":{},"requestSizeBytes":0,"responseCode":200,"responseHeaders":{},"responseSizeBytes":512,"durationMs":50}}"#;

const DB_EVENT_MSG: &str = r#"{"type":"event","plugin":"database","timestamp":1700000002,"payload":{"query":"SELECT * FROM users"}}"#;

const MALFORMED_JSON: &str = r#"{not valid json at all}"#;

// ── Test server helpers ───────────────────────────────────────────────────────

fn make_args() -> Arc<Args> {
    Arc::new(Args {
        port: 0,
        no_color: true,
        ..Default::default()
    })
}

/// Spawns a server that accepts multiple concurrent connections using a pretty
/// displayer (legacy stdout path). Returns (port, shared EventBuffer).
async fn start_test_server() -> (u16, Arc<EventBuffer>) {
    start_test_server_full(
        Arc::new(Filter::new(None, None).unwrap()),
        None,
        pretty_displayer(),
    )
    .await
}

/// Like [`start_test_server`] but with an explicit displayer and save file.
/// Pass `None` for displayer to exercise the non-pretty (agent daemon) path.
async fn start_test_server_full(
    filter: Arc<Filter>,
    save_file: Option<Arc<Mutex<std::fs::File>>>,
    displayer: Option<Arc<Displayer>>,
) -> (u16, Arc<EventBuffer>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let buffer = Arc::new(EventBuffer::new(1000));
    let buffer_outer = Arc::clone(&buffer);

    tokio::spawn(async move {
        while let Ok((stream, _)) = listener.accept().await {
            let buf = Arc::clone(&buffer_outer);
            let disp = displayer.clone();
            let filt = Arc::clone(&filter);
            let save = save_file.clone();
            let args = make_args();
            tokio::spawn(async move {
                let _ = handle_connection(stream, args, disp, filt, buf, save).await;
            });
        }
    });

    (port, buffer)
}

async fn ws_connect(port: u16) -> tokio_tungstenite::WebSocketStream<
    tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
> {
    let url = format!("ws://127.0.0.1:{}", port);
    let (ws, _) = connect_async(&url).await.unwrap();
    ws
}

/// Send text, then send Close frame, then wait for the server to drain.
async fn send_and_close(
    ws: &mut tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >,
    messages: &[&str],
) {
    for msg in messages {
        ws.send(WsMessage::Text(msg.to_string())).await.unwrap();
    }
    let _ = ws.send(WsMessage::Close(None)).await;
    // Drain any remaining frames the server sends (e.g. the echo close frame)
    while let Some(Ok(_)) = ws.next().await {}
}

// ── Tests ─────────────────────────────────────────────────────────────────────

/// Connecting and sending a hello should not disconnect or error.
/// Lifecycle envelopes (connect/hello/disconnect) ARE buffered.
#[tokio::test]
async fn test_hello_handshake() {
    let (port, buffer) = start_test_server().await;
    let mut ws = ws_connect(port).await;

    send_and_close(&mut ws, &[HELLO_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    // hello alone: connect + hello + disconnect = 3 lifecycle envelopes
    assert_eq!(buffer.len(), 3, "hello session yields connect+hello+disconnect");
}

/// hello + network event → buffer contains connect/hello/event/disconnect.
#[tokio::test]
async fn test_network_event_pipeline() {
    let (port, buffer) = start_test_server().await;
    let mut ws = ws_connect(port).await;

    send_and_close(&mut ws, &[HELLO_MSG, NETWORK_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    // connect + hello + event + disconnect = 4
    assert_eq!(buffer.len(), 4);

    // The event envelope must be present among the buffered entries.
    let dump = buffer.dump_since(0);
    let event = dump
        .entries
        .iter()
        .find(|v| v["type"] == "event")
        .expect("network event must be buffered");
    assert_eq!(event["plugin"], "network");
}

/// Garbage input must not crash the server; subsequent valid messages still
/// reach the buffer.
#[tokio::test]
async fn test_malformed_json_no_crash() {
    let (port, buffer) = start_test_server().await;
    let mut ws = ws_connect(port).await;

    send_and_close(&mut ws, &[MALFORMED_JSON, NETWORK_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    // No hello here: connect + event + disconnect = 3 (malformed ignored).
    // The event entry is what matters; lifecycle is additive.
    let dump = buffer.dump_since(0);
    assert_eq!(
        dump.entries.iter().filter(|v| v["type"] == "event").count(),
        1,
        "valid message after bad JSON should be buffered"
    );
}

/// Events for unknown plugins must NOT end up in the buffer (only lifecycle).
#[tokio::test]
async fn test_unknown_plugin_event_not_buffered() {
    let (port, buffer) = start_test_server().await;
    let mut ws = ws_connect(port).await;

    send_and_close(&mut ws, &[HELLO_MSG, DB_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    let dump = buffer.dump_since(0);
    let has_db = dump.entries.iter().any(|v| v["plugin"] == "database");
    assert!(!has_db, "database plugin events must not be buffered");
    // Only lifecycle envelopes remain: connect + hello + disconnect = 3.
    let lifecycle: Vec<&str> = dump
        .entries
        .iter()
        .map(|v| v["type"].as_str().unwrap_or(""))
        .collect();
    assert_eq!(lifecycle, vec!["connect", "hello", "disconnect"]);
}

/// Two clients connect at the same time; each sends one event.
/// Each connection contributes connect+hello+event+disconnect = 4 → total 8,
/// with no data races / tearing.
#[tokio::test]
async fn test_multiple_concurrent_clients() {
    let (port, buffer) = start_test_server().await;

    let (mut ws1, mut ws2) = tokio::join!(ws_connect(port), ws_connect(port));

    tokio::join!(
        send_and_close(&mut ws1, &[HELLO_MSG, NETWORK_EVENT_MSG]),
        send_and_close(&mut ws2, &[HELLO_MSG, NETWORK_EVENT_MSG])
    );

    tokio::time::sleep(Duration::from_millis(200)).await;

    assert_eq!(
        buffer.len(),
        8,
        "each client contributes connect+hello+event+disconnect"
    );
    // Exactly two network events across both connections.
    let dump = buffer.dump_since(0);
    assert_eq!(
        dump.entries.iter().filter(|v| v["type"] == "event").count(),
        2
    );
}

/// Lifecycle envelopes (connect/hello/disconnect) are buffered in chronological
/// order interleaved with events, with contiguous sequence numbers.
/// hello + event + close => [connect, hello, event, disconnect], seq 1..=4.
#[tokio::test]
async fn test_lifecycle_envelopes_in_order() {
    let (port, buffer) = start_test_server().await;
    let mut ws = ws_connect(port).await;

    send_and_close(&mut ws, &[HELLO_MSG, NETWORK_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(150)).await;

    let dump = buffer.dump_since(0);
    assert_eq!(dump.entries.len(), 4);
    assert_eq!(dump.high_watermark, 4, "seq numbers are contiguous 1..=4");

    let types: Vec<&str> = dump
        .entries
        .iter()
        .map(|v| v["type"].as_str().unwrap())
        .collect();
    assert_eq!(
        types,
        vec!["connect", "hello", "event", "disconnect"],
        "lifecycle must be interleaved with events in chronological order"
    );

    // hello envelope is a pass-through: original SDK fields preserved.
    let hello_entry = &dump.entries[1];
    assert_eq!(hello_entry["type"], "hello");
    assert_eq!(hello_entry["clientId"], "test-001");
    assert_eq!(hello_entry["appPackage"], "com.test.app");
    assert_eq!(hello_entry["deviceModel"], "Test Device");
    assert!(
        hello_entry.get("timestamp").is_some(),
        "hello envelope carries a timestamp"
    );

    // disconnect envelope carries appPackage + clientId from the hello.
    let disconnect_entry = &dump.entries[3];
    assert_eq!(disconnect_entry["type"], "disconnect");
    assert_eq!(disconnect_entry["appPackage"], "com.test.app");
    assert_eq!(disconnect_entry["clientId"], "test-001");
}

/// Events saved to a JSONL file must produce one line per event with valid JSON.
#[tokio::test]
async fn test_save_to_file() {
    let tmp = tempfile::NamedTempFile::new().unwrap();
    let tmp_path = tmp.path().to_path_buf();

    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&tmp_path)
        .unwrap();
    let save_file = Some(Arc::new(Mutex::new(file)));

    let (port, _buffer) = start_test_server_full(
        Arc::new(Filter::new(None, None).unwrap()),
        save_file,
        pretty_displayer(),
    )
    .await;

    let mut ws = ws_connect(port).await;
    send_and_close(&mut ws, &[HELLO_MSG, NETWORK_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    let content = std::fs::read_to_string(&tmp_path).unwrap();
    let lines: Vec<&str> = content.lines().filter(|l| !l.is_empty()).collect();
    // Only the network event is written to the save file (lifecycle envelopes
    // are buffer-only and never reach --save output).
    assert_eq!(lines.len(), 1, "one network event should produce one JSONL line");

    let parsed: serde_json::Value = serde_json::from_str(lines[0])
        .expect("saved line must be valid JSON");
    assert_eq!(parsed["type"], "event");
    assert_eq!(parsed["plugin"], "network");

    // tmp kept alive until here so the path remains valid for read_to_string
    drop(tmp);
}

/// `--save` works in the non-pretty (agent daemon) mode too: lifecycle
/// envelopes are buffer-only, so only the network event reaches the save
/// file. (DoD13: --save works in both modes.)
#[tokio::test]
async fn test_save_to_file_in_non_pretty_mode() {
    let tmp = tempfile::NamedTempFile::new().unwrap();
    let tmp_path = tmp.path().to_path_buf();

    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&tmp_path)
        .unwrap();
    let save_file = Some(Arc::new(Mutex::new(file)));

    // displayer = None simulates the default agent daemon (no --pretty).
    let (port, _buffer) = start_test_server_full(
        Arc::new(Filter::new(None, None).unwrap()),
        save_file,
        None,
    )
    .await;

    let mut ws = ws_connect(port).await;
    send_and_close(&mut ws, &[HELLO_MSG, NETWORK_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    let content = std::fs::read_to_string(&tmp_path).unwrap();
    let lines: Vec<&str> = content.lines().filter(|l| !l.is_empty()).collect();
    assert_eq!(lines.len(), 1, "only the network event is saved");
    let parsed: serde_json::Value =
        serde_json::from_str(lines[0]).expect("saved line must be valid JSON");
    assert_eq!(parsed["type"], "event");
    assert_eq!(parsed["plugin"], "network");

    drop(tmp);
}

/// Server with a URL filter configured still processes all messages and stores
/// ALL events in the buffer (filter affects display only, not storage).
/// This verifies the pipeline works end-to-end with a filter configured.
#[tokio::test]
async fn test_filter_url_regex_pipeline() {
    let filter = Arc::new(Filter::new(Some(r"api\.example"), None).unwrap());
    let (port, buffer) = start_test_server_full(filter, None, pretty_displayer()).await;

    let mut ws = ws_connect(port).await;
    // Send one matching URL and one non-matching URL
    send_and_close(&mut ws, &[HELLO_MSG, NETWORK_EVENT_MSG, NETWORK_EVENT_OTHER_URL]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    // The buffer stores ALL network events regardless of filter;
    // filter only controls what is displayed to stdout. Lifecycle envelopes
    // (connect/hello/disconnect) are also present; count only event entries.
    let dump = buffer.dump_since(0);
    assert_eq!(
        dump.entries.iter().filter(|v| v["type"] == "event").count(),
        2,
        "both events must be buffered; filter does not affect storage"
    );
}
