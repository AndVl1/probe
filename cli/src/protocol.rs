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
}
