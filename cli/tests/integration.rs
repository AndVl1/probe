/// Integration tests: full WebSocket pipeline
///
/// Each test binds a random port, spawns a lightweight server loop using
/// `probe::server::handle_connection`, connects via tokio-tungstenite, and
/// asserts on the shared `TransactionBuffer`.
use std::sync::{Arc, Mutex};
use std::time::Duration;
use futures_util::{SinkExt, StreamExt};
use tokio::net::TcpListener;
use tokio_tungstenite::{connect_async, tungstenite::Message as WsMessage};

use probe::{
    Args,
    display::Displayer,
    filter::Filter,
    server::{TransactionBuffer, handle_connection},
};

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
        filter: None,
        verbose: false,
        bodies: false,
        min_size: None,
        save: None,
        no_color: true,
    })
}

/// Spawns a server that accepts multiple concurrent connections.
/// Returns (port, shared TransactionBuffer).
async fn start_test_server() -> (u16, Arc<TransactionBuffer>) {
    start_test_server_full(
        Arc::new(Filter::new(None, None).unwrap()),
        None,
    )
    .await
}

async fn start_test_server_full(
    filter: Arc<Filter>,
    save_file: Option<Arc<Mutex<std::fs::File>>>,
) -> (u16, Arc<TransactionBuffer>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let buffer = Arc::new(TransactionBuffer::new(1000));
    let buffer_outer = Arc::clone(&buffer);

    tokio::spawn(async move {
        loop {
            match listener.accept().await {
                Ok((stream, _)) => {
                    let buf = Arc::clone(&buffer_outer);
                    let disp = Arc::new(Displayer::new(false, false, true));
                    let filt = Arc::clone(&filter);
                    let save = save_file.clone();
                    let args = make_args();
                    tokio::spawn(async move {
                        let _ = handle_connection(stream, args, disp, filt, buf, save).await;
                    });
                }
                Err(_) => break,
            }
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
#[tokio::test]
async fn test_hello_handshake() {
    let (port, buffer) = start_test_server().await;
    let mut ws = ws_connect(port).await;

    send_and_close(&mut ws, &[HELLO_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    // Hello messages are NOT buffered (only events/transactions are)
    assert_eq!(buffer.len(), 0, "hello alone must not add to buffer");
}

/// hello + network event → buffer should contain exactly 1 item.
#[tokio::test]
async fn test_network_event_pipeline() {
    let (port, buffer) = start_test_server().await;
    let mut ws = ws_connect(port).await;

    send_and_close(&mut ws, &[HELLO_MSG, NETWORK_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    assert_eq!(buffer.len(), 1);

    // Verify the stored JSON is the full event envelope
    let dump = buffer.dump_last(1);
    let parsed: serde_json::Value = serde_json::from_str(&dump).unwrap();
    assert_eq!(parsed["type"], "event");
    assert_eq!(parsed["plugin"], "network");
}

/// Garbage input must not crash the server; subsequent valid messages still
/// reach the buffer.
#[tokio::test]
async fn test_malformed_json_no_crash() {
    let (port, buffer) = start_test_server().await;
    let mut ws = ws_connect(port).await;

    send_and_close(&mut ws, &[MALFORMED_JSON, NETWORK_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    // Server must survive the bad message; the valid one must land in buffer
    assert_eq!(buffer.len(), 1, "valid message after bad JSON should be buffered");
}

/// Events for unknown plugins must NOT end up in the buffer.
#[tokio::test]
async fn test_unknown_plugin_event_not_buffered() {
    let (port, buffer) = start_test_server().await;
    let mut ws = ws_connect(port).await;

    send_and_close(&mut ws, &[HELLO_MSG, DB_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    assert_eq!(buffer.len(), 0, "database plugin events must not be buffered");
}

/// Two clients connect at the same time; each sends one event.
/// Buffer should have exactly 2 items — no data races.
#[tokio::test]
async fn test_multiple_concurrent_clients() {
    let (port, buffer) = start_test_server().await;

    let (mut ws1, mut ws2) = tokio::join!(ws_connect(port), ws_connect(port));

    tokio::join!(
        send_and_close(&mut ws1, &[HELLO_MSG, NETWORK_EVENT_MSG]),
        send_and_close(&mut ws2, &[HELLO_MSG, NETWORK_EVENT_MSG])
    );

    tokio::time::sleep(Duration::from_millis(200)).await;

    assert_eq!(buffer.len(), 2, "each client should contribute one buffered event");
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
    )
    .await;

    let mut ws = ws_connect(port).await;
    send_and_close(&mut ws, &[HELLO_MSG, NETWORK_EVENT_MSG]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    let content = std::fs::read_to_string(&tmp_path).unwrap();
    let lines: Vec<&str> = content.lines().filter(|l| !l.is_empty()).collect();
    assert_eq!(lines.len(), 1, "one network event should produce one JSONL line");

    let parsed: serde_json::Value = serde_json::from_str(lines[0])
        .expect("saved line must be valid JSON");
    assert_eq!(parsed["type"], "event");
    assert_eq!(parsed["plugin"], "network");

    // tmp kept alive until here so the path remains valid for read_to_string
    drop(tmp);
}

/// Server with a URL filter configured still processes all messages and stores
/// ALL events in the buffer (filter affects display only, not storage).
/// This verifies the pipeline works end-to-end with a filter configured.
#[tokio::test]
async fn test_filter_url_regex_pipeline() {
    let filter = Arc::new(Filter::new(Some(r"api\.example"), None).unwrap());
    let (port, buffer) = start_test_server_full(filter, None).await;

    let mut ws = ws_connect(port).await;
    // Send one matching URL and one non-matching URL
    send_and_close(&mut ws, &[HELLO_MSG, NETWORK_EVENT_MSG, NETWORK_EVENT_OTHER_URL]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    // The buffer stores ALL network events regardless of filter;
    // filter only controls what is displayed to stdout.
    assert_eq!(
        buffer.len(),
        2,
        "both events must be buffered; filter does not affect storage"
    );
}
