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
