use std::collections::HashMap;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Message {
    Hello(HelloMessage),
    /// Legacy variant kept for backward compatibility with older SDKs
    Transaction(HttpTransaction),
    /// Current SDK wire format: {"type":"event","plugin":"network","timestamp":...,"payload":{...}}
    Event(EventMessage),
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloMessage {
    pub client_id: String,
    pub app_package: String,
    pub device_model: String,
    pub android_version: Option<String>,
    pub os_version: Option<String>,
    pub platform: Option<String>,
}

/// Envelope sent by the current Android SDK for all plugin events.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventMessage {
    pub plugin: String,
    pub timestamp: i64,
    pub payload: serde_json::Value,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HttpTransaction {
    pub id: String,
    pub timestamp: i64,
    pub method: String,
    pub url: String,
    pub request_headers: HashMap<String, String>,
    pub request_body: Option<String>,
    pub request_size_bytes: i64,
    pub response_code: Option<i32>,
    pub response_message: Option<String>,
    pub response_headers: HashMap<String, String>,
    pub response_body: Option<String>,
    pub response_size_bytes: Option<i64>,
    pub duration_ms: Option<i64>,
    /// Optional: not all SDKs send this field.
    pub app_id: Option<String>,
    pub error: Option<String>,
}

/// CLI-internal lifecycle envelopes.
///
/// These are NOT wire `Message` variants — the WebSocket protocol is unchanged.
/// They are synthetic NDJSON entries the CLI pushes into its `EventBuffer` so an
/// agent (via `devlens dump`) can see connection boundaries interleaved with
/// events in chronological order: `{type:connect}` → `{type:hello}` →
/// `{type:event}` → `{type:disconnect}`. A `{type:gap}` envelope is emitted by
/// the control plane when entries were lost to buffer eviction between dumps.
pub mod lifecycle {
    use std::time::{SystemTime, UNIX_EPOCH};

    /// Wall-clock time in milliseconds since UNIX_EPOCH (0 on clock error).
    ///
    /// Matches the millisecond resolution used by SDK event timestamps.
    pub fn now_millis() -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0)
    }

    /// A WebSocket client completed the TCP+WS handshake.
    pub fn connect(peer: &str, timestamp: i64) -> serde_json::Value {
        serde_json::json!({
            "type": "connect",
            "peer": peer,
            "timestamp": timestamp,
        })
    }

    /// Pass-through of the SDK hello message — no field loss.
    ///
    /// The raw wire value is preserved verbatim; `timestamp` is injected only
    /// when the raw message did not already carry one (the current Android hello
    /// does not include a top-level timestamp).
    pub fn hello(mut raw: serde_json::Value, timestamp: i64) -> serde_json::Value {
        if raw.get("timestamp").is_none() {
            raw["timestamp"] = serde_json::json!(timestamp);
        }
        raw
    }

    /// A WebSocket client went away. `app_package`/`client_id` are `None` when
    /// the connection closed before sending a hello.
    pub fn disconnect(
        app_package: Option<&str>,
        client_id: Option<&str>,
        timestamp: i64,
    ) -> serde_json::Value {
        serde_json::json!({
            "type": "disconnect",
            "appPackage": app_package,
            "clientId": client_id,
            "timestamp": timestamp,
        })
    }

    /// `dropped` entries were evicted between the client's last cursor and the
    /// oldest surviving buffer entry. Emitted as the first body line of a dump
    /// that encountered loss.
    pub fn gap(dropped: u64, timestamp: i64) -> serde_json::Value {
        serde_json::json!({
            "type": "gap",
            "dropped": dropped,
            "timestamp": timestamp,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Message::Hello ────────────────────────────────────────────────────────

    #[test]
    fn deserialize_hello_message() {
        let json = r#"{
            "type": "hello",
            "clientId": "client-001",
            "appPackage": "com.example.app",
            "deviceModel": "Pixel 6",
            "androidVersion": "13",
            "platform": "android"
        }"#;
        let msg: Message = serde_json::from_str(json).expect("should deserialize");
        match msg {
            Message::Hello(h) => {
                assert_eq!(h.client_id, "client-001");
                assert_eq!(h.app_package, "com.example.app");
                assert_eq!(h.device_model, "Pixel 6");
                assert_eq!(h.android_version, Some("13".to_string()));
                assert_eq!(h.platform, Some("android".to_string()));
            }
            other => panic!("expected Hello, got {:?}", other),
        }
    }

    // ── Message::Event ────────────────────────────────────────────────────────

    #[test]
    fn deserialize_event_message_with_network_plugin() {
        let json = r#"{
            "type": "event",
            "plugin": "network",
            "timestamp": 1700000000,
            "payload": {"id": "tx-1", "url": "https://example.com"}
        }"#;
        let msg: Message = serde_json::from_str(json).expect("should deserialize");
        match msg {
            Message::Event(e) => {
                assert_eq!(e.plugin, "network");
                assert_eq!(e.timestamp, 1700000000);
                assert_eq!(e.payload["url"], "https://example.com");
            }
            other => panic!("expected Event, got {:?}", other),
        }
    }

    // ── Message::Transaction (legacy) ─────────────────────────────────────────

    #[test]
    fn deserialize_legacy_transaction_message() {
        let json = r#"{
            "type": "transaction",
            "id": "tx-legacy",
            "timestamp": 1700000001,
            "method": "GET",
            "url": "https://legacy.example.com/api",
            "requestHeaders": {},
            "requestSizeBytes": 0,
            "responseHeaders": {},
            "responseCode": 200
        }"#;
        let msg: Message = serde_json::from_str(json).expect("should deserialize");
        match msg {
            Message::Transaction(tx) => {
                assert_eq!(tx.id, "tx-legacy");
                assert_eq!(tx.method, "GET");
                assert_eq!(tx.url, "https://legacy.example.com/api");
                assert_eq!(tx.response_code, Some(200));
            }
            other => panic!("expected Transaction, got {:?}", other),
        }
    }

    // ── Unknown type → Err ────────────────────────────────────────────────────

    #[test]
    fn deserialize_unknown_type_returns_error() {
        let json = r#"{"type": "unknown", "data": "test"}"#;
        let result = serde_json::from_str::<Message>(json);
        assert!(result.is_err(), "unknown type should fail to deserialize");
    }

    // ── Malformed JSON → Err ──────────────────────────────────────────────────

    #[test]
    fn deserialize_malformed_json_returns_error() {
        let json = r#"{invalid json}"#;
        let result = serde_json::from_str::<Message>(json);
        assert!(result.is_err(), "malformed JSON should fail to deserialize");
    }

    // ── Event with missing payload → Err ──────────────────────────────────────

    #[test]
    fn deserialize_event_missing_payload_returns_error() {
        let json = r#"{"type": "event", "plugin": "network", "timestamp": 1700000000}"#;
        let result = serde_json::from_str::<Message>(json);
        assert!(result.is_err(), "missing payload field should fail");
    }

    // ── HttpTransaction app_id: null → None ───────────────────────────────────

    #[test]
    fn deserialize_http_transaction_app_id_null() {
        let json = r#"{
            "id": "tx-1",
            "timestamp": 1700000000,
            "method": "GET",
            "url": "https://example.com",
            "requestHeaders": {},
            "requestSizeBytes": 0,
            "responseHeaders": {},
            "appId": null
        }"#;
        let tx: HttpTransaction = serde_json::from_str(json).expect("should deserialize");
        assert_eq!(tx.app_id, None);
    }

    // ── HttpTransaction without app_id field → None ───────────────────────────

    #[test]
    fn deserialize_http_transaction_no_app_id_field() {
        let json = r#"{
            "id": "tx-2",
            "timestamp": 1700000000,
            "method": "POST",
            "url": "https://example.com/data",
            "requestHeaders": {},
            "requestSizeBytes": 64,
            "responseHeaders": {}
        }"#;
        let tx: HttpTransaction = serde_json::from_str(json).expect("should deserialize");
        assert_eq!(tx.app_id, None);
    }

    // ── EventMessage round-trip ───────────────────────────────────────────────

    #[test]
    fn event_message_serialize_deserialize_roundtrip() {
        let original = EventMessage {
            plugin: "network".to_string(),
            timestamp: 1700000042,
            payload: serde_json::json!({"key": "value", "num": 42}),
        };

        let serialized = serde_json::to_string(&original).expect("serialize");
        let restored: EventMessage = serde_json::from_str(&serialized).expect("deserialize");

        assert_eq!(restored.plugin, original.plugin);
        assert_eq!(restored.timestamp, original.timestamp);
        assert_eq!(restored.payload, original.payload);
    }

    // ── lifecycle envelopes ────────────────────────────────────────────────────

    #[test]
    fn lifecycle_connect_shape() {
        let v = lifecycle::connect("127.0.0.1:1234", 7);
        assert_eq!(v["type"], "connect");
        assert_eq!(v["peer"], "127.0.0.1:1234");
        assert_eq!(v["timestamp"], 7);
    }

    #[test]
    fn lifecycle_hello_passes_through_without_field_loss() {
        let raw = serde_json::json!({
            "type": "hello",
            "clientId": "c-1",
            "appPackage": "com.x",
            "deviceModel": "Pixel",
            "platform": "android"
        });
        let v = lifecycle::hello(raw, 99);
        // original fields preserved
        assert_eq!(v["type"], "hello");
        assert_eq!(v["clientId"], "c-1");
        assert_eq!(v["appPackage"], "com.x");
        assert_eq!(v["deviceModel"], "Pixel");
        assert_eq!(v["platform"], "android");
        // timestamp injected because raw lacked one
        assert_eq!(v["timestamp"], 99);
    }

    #[test]
    fn lifecycle_hello_preserves_existing_timestamp() {
        let raw = serde_json::json!({"type": "hello", "timestamp": 5});
        let v = lifecycle::hello(raw, 99);
        assert_eq!(v["timestamp"], 5, "existing timestamp must not be overwritten");
    }

    #[test]
    fn lifecycle_disconnect_with_known_client() {
        let v = lifecycle::disconnect(Some("com.x"), Some("c-1"), 3);
        assert_eq!(v["type"], "disconnect");
        assert_eq!(v["appPackage"], "com.x");
        assert_eq!(v["clientId"], "c-1");
        assert_eq!(v["timestamp"], 3);
    }

    #[test]
    fn lifecycle_disconnect_without_hello() {
        let v = lifecycle::disconnect(None, None, 3);
        assert_eq!(v["type"], "disconnect");
        assert!(v["appPackage"].is_null());
        assert!(v["clientId"].is_null());
    }

    #[test]
    fn lifecycle_gap_shape() {
        let v = lifecycle::gap(42, 123);
        assert_eq!(v["type"], "gap");
        assert_eq!(v["dropped"], 42);
        assert_eq!(v["timestamp"], 123);
    }

    #[test]
    fn lifecycle_now_millis_is_positive() {
        // Sanity: the clock is past the epoch.
        assert!(lifecycle::now_millis() > 1_000_000_000_000);
    }
}
