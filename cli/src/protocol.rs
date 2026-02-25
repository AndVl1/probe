use std::collections::HashMap;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Message {
    Hello(HelloMessage),
    Transaction(HttpTransaction),
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloMessage {
    pub client_id: String,
    pub app_package: String,
    pub device_model: String,
    pub android_version: String,
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
    pub app_id: String,
    pub error: Option<String>,
}
