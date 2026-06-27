//! Bidirectional query dispatch: routes CLI→SDK [`Query`] frames to the active
//! SDK client and correlates the SDK's `queryResult` [`Event`] payloads back to
//! the waiting caller by `requestId`.
//!
//! [`Query`]: crate::protocol::Message::Query
//! [`Event`]: crate::protocol::Message::Event
//!
//! # Concurrency / lock discipline
//!
//! Two `std::sync::Mutex`es guard the registry state (mirroring the
//! `state -> buffer` pattern in [`crate::control`]):
//!
//! - `client`  — the single active SDK connection (`Option<(conn_id, sender)>`).
//! - `pending` — outstanding queries awaiting a correlated response.
//!
//! Neither mutex is ever held across an `.await`. [`ClientRegistry::dispatch`]
//! clones the sender out of `client` and drops the guard before any await; the
//! oneshot plants into `pending` and is removed before awaiting the response.
//! This keeps the synchronous mutexes off the async runtime's critical path.
//!
//! # v1 single-active-client assumption
//!
//! The latest SDK to send `hello` wins. A stale disconnect (older `conn_id`)
//! does not clobber a newer registration. Multi-client routing is deferred.

use std::collections::HashMap;
use std::fmt;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use serde_json::Value;
use tokio::sync::{mpsc, oneshot};
use tokio_tungstenite::tungstenite::Message as WsMessage;

/// Errors raised by the query dispatch path. Mapped to exit codes by the
/// control plane (see [`crate::control::exit`]).
#[derive(Debug)]
pub enum QueryError {
    /// No SDK client is currently connected.
    NoClient,
    /// The SDK did not respond within the configured timeout.
    Timeout,
    /// The SDK client disconnected while the query was in flight (the writer
    /// channel closed before a response arrived).
    ClientGone,
}

impl fmt::Display for QueryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            QueryError::NoClient => f.write_str("no SDK client connected"),
            QueryError::Timeout => f.write_str("SDK did not respond within the query timeout"),
            QueryError::ClientGone => f.write_str("SDK client disconnected before responding"),
        }
    }
}

impl std::error::Error for QueryError {}

/// Registry of the active SDK client plus outstanding query responses.
///
/// Pure-async and injectable: unit tests construct a [`ClientRegistry`], feed
/// it a mocked `mpsc::Sender`, and drive [`Self::dispatch`] / [`Self::route_response`]
/// directly — no real WebSocket is opened.
pub struct ClientRegistry {
    /// The currently-connected SDK client. Latest `hello` overwrites; a stale
    /// disconnect (mismatched `conn_id`) leaves it untouched.
    client: Mutex<Option<(u64, mpsc::Sender<WsMessage>)>>,
    /// Outstanding queries awaiting a correlated `queryResult`, keyed by
    /// `requestId`. Removed on first response (late duplicates are no-ops).
    pending: Mutex<HashMap<String, oneshot::Sender<Value>>>,
    /// Monotonic counter producing `q-N` requestIds (no uuid dependency).
    counter: AtomicU64,
}

impl fmt::Debug for ClientRegistry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ClientRegistry")
            .field("client_registered", &self.client.lock().map(|o| o.is_some()).unwrap_or(false))
            .field(
                "pending",
                &self.pending.lock().map(|p| p.len()).unwrap_or(0),
            )
            .field("counter", &self.counter.load(Ordering::Relaxed))
            .finish()
    }
}

impl Default for ClientRegistry {
    fn default() -> Self {
        Self::new()
    }
}

impl ClientRegistry {
    /// Create an empty registry (no active client, no pending queries).
    pub fn new() -> Self {
        Self {
            client: Mutex::new(None),
            pending: Mutex::new(HashMap::new()),
            counter: AtomicU64::new(0),
        }
    }

    /// Register the active SDK client. Overwrites any prior registration: the
    /// latest `hello` wins per the v1 single-active-client contract.
    pub fn register(&self, conn_id: u64, sender: mpsc::Sender<WsMessage>) {
        let mut guard = self.client.lock().unwrap_or_else(|e| e.into_inner());
        *guard = Some((conn_id, sender));
    }

    /// Unregister the active client, but only if `conn_id` still matches the
    /// current registration. A newer connection's `hello` must not be clobbered
    /// by a stale disconnect from an older connection.
    pub fn unregister(&self, conn_id: u64) {
        let mut guard = self.client.lock().unwrap_or_else(|e| e.into_inner());
        if let Some((current, _)) = guard.as_ref() {
            if *current == conn_id {
                *guard = None;
            }
        }
    }

    /// `true` if a client is currently registered.
    #[cfg(test)]
    pub fn has_client(&self) -> bool {
        self.client.lock().unwrap_or_else(|e| e.into_inner()).is_some()
    }

    /// Send a Query to the active client and await its correlated `queryResult`.
    ///
    /// Returns `(request_id, payload)` on success, where `payload` is the full
    /// SDK response object (`{op:"queryResult", requestId, ok, result|error}`).
    /// The caller inspects `payload.ok` to distinguish success from plugin error.
    ///
    /// Lock discipline: both mutexes are released before any `.await`. The
    /// pending slot is planted before the Query is sent so a blazing-fast reply
    /// cannot miss the waiter, and is cleaned up on every error path.
    pub async fn dispatch(
        &self,
        plugin: &str,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<(String, Value), QueryError> {
        let request_id = format!(
            "q-{}",
            self.counter.fetch_add(1, Ordering::Relaxed) + 1
        );

        // Build the outbound Query frame as a Value, then stringify via Display
        // (infallible for JSON values — no panic risk from to_string's Result).
        let frame = serde_json::json!({
            "type": "query",
            "requestId": request_id,
            "plugin": plugin,
            "method": method,
            "params": params,
        });
        let query_json = frame.to_string();

        // Plant the response slot BEFORE sending so a fast reply cannot race.
        let (resp_tx, resp_rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().unwrap_or_else(|e| e.into_inner());
            pending.insert(request_id.clone(), resp_tx);
        }

        // Clone the sender under the lock, then drop the guard before awaiting.
        let sender = {
            let guard = self.client.lock().unwrap_or_else(|e| e.into_inner());
            guard.as_ref().map(|(_, tx)| tx.clone())
        };
        let sender = sender.ok_or_else(|| {
            // No client — reclaim the pending slot.
            let mut pending = self.pending.lock().unwrap_or_else(|e| e.into_inner());
            pending.remove(&request_id);
            QueryError::NoClient
        })?;

        // Send the Query frame. clone() succeeded means a client was registered,
        // but it may have disconnected between the clone and the send (channel
        // closed) — surface as ClientGone and reclaim the pending slot.
        if sender.send(WsMessage::Text(query_json)).await.is_err() {
            let mut pending = self.pending.lock().unwrap_or_else(|e| e.into_inner());
            pending.remove(&request_id);
            return Err(QueryError::ClientGone);
        }

        // Await the correlated response with a timeout. A late/absent reply is
        // reclaimed so a duplicate or unsolicited queryResult becomes a no-op.
        match tokio::time::timeout(timeout, resp_rx).await {
            Ok(Ok(payload)) => Ok((request_id, payload)),
            Ok(Err(_)) => {
                // oneshot sender dropped without sending — client gone mid-flight.
                let mut pending = self.pending.lock().unwrap_or_else(|e| e.into_inner());
                pending.remove(&request_id);
                Err(QueryError::ClientGone)
            }
            Err(_) => {
                let mut pending = self.pending.lock().unwrap_or_else(|e| e.into_inner());
                pending.remove(&request_id);
                Err(QueryError::Timeout)
            }
        }
    }

    /// Route an inbound `queryResult` payload to its waiting dispatcher.
    ///
    /// If no waiter exists (late, duplicate, or unsolicited response), this is
    /// a silent no-op — the caller has already buffered/skipped per the
    /// queryResult path and nothing else needs to happen.
    pub fn route_response(&self, request_id: &str, payload: Value) {
        let mut pending = self.pending.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(tx) = pending.remove(request_id) {
            // oneshot::send is non-blocking; a dropped receiver means the
            // dispatcher already timed out — the slot was already reclaimed.
            let _ = tx.send(payload);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use tokio::time::Duration;

    // ── register / unregister ─────────────────────────────────────────────────

    #[tokio::test]
    async fn register_sets_active_client() {
        let reg = ClientRegistry::new();
        assert!(!reg.has_client());
        let (_tx, _rx) = mpsc::channel::<WsMessage>(4);
        // mpsc::Sender clones are what register stores; reuse a fresh channel.
        let (tx, _rx) = mpsc::channel::<WsMessage>(4);
        reg.register(1, tx);
        assert!(reg.has_client());
    }

    #[tokio::test]
    async fn unregister_only_matches_conn_id() {
        let reg = ClientRegistry::new();
        let (tx, _rx) = mpsc::channel::<WsMessage>(4);
        reg.register(1, tx);
        // Stale disconnect from an older connection must not clobber the newer
        // registration.
        reg.unregister(0);
        assert!(reg.has_client(), "mismatched conn_id unregister is a no-op");
        // The matching conn_id clears it.
        reg.unregister(1);
        assert!(!reg.has_client());
    }

    #[tokio::test]
    async fn register_overwrites_latest_hello_wins() {
        let reg = ClientRegistry::new();
        let (tx1, _rx1) = mpsc::channel::<WsMessage>(4);
        let (tx2, _rx2) = mpsc::channel::<WsMessage>(4);
        reg.register(1, tx1);
        reg.register(2, tx2);
        // The newer conn_id (2) is now active; a stale disconnect of conn 1
        // must NOT clear the registration.
        reg.unregister(1);
        assert!(reg.has_client(), "newer hello (conn 2) survives stale disconnect of conn 1");
        reg.unregister(2);
        assert!(!reg.has_client());
    }

    // ── dispatch: NoClient ─────────────────────────────────────────────────────

    #[tokio::test]
    async fn dispatch_without_client_returns_no_client() {
        let reg = ClientRegistry::new();
        let err = reg
            .dispatch("database", "listDatabases", serde_json::json!({}), Duration::from_millis(50))
            .await
            .expect_err("no client");
        assert!(matches!(err, QueryError::NoClient), "got {:?}", err);
    }

    // ── dispatch: Timeout ──────────────────────────────────────────────────────

    #[tokio::test]
    async fn dispatch_times_out_when_sdk_silent() {
        let reg = ClientRegistry::new();
        // Register a client whose channel we never read and never respond on.
        let (tx, _rx) = mpsc::channel::<WsMessage>(4);
        reg.register(1, tx);

        let start = tokio::time::Instant::now();
        let err = reg
            .dispatch("database", "listDatabases", serde_json::json!({}), Duration::from_millis(100))
            .await
            .expect_err("timeout");
        assert!(matches!(err, QueryError::Timeout), "got {:?}", err);
        assert!(
            start.elapsed() >= Duration::from_millis(90),
            "timeout honored (elapsed {:?})",
            start.elapsed()
        );
    }

    // ── dispatch: happy path + requestId correlation ───────────────────────────

    #[tokio::test]
    async fn dispatch_happy_path_route_response_delivers_payload() {
        let reg = Arc::new(ClientRegistry::new());
        let (tx, mut rx) = mpsc::channel::<WsMessage>(4);
        reg.register(1, tx);

        let reg_for_sdk = Arc::clone(&reg);
        let sdk = tokio::spawn(async move {
            // Read the Query frame the dispatcher sends.
            let frame = rx.recv().await.expect("query frame");
            let text = match frame {
                WsMessage::Text(t) => t,
                other => panic!("expected Text, got {:?}", other),
            };
            let v: serde_json::Value = serde_json::from_str(&text).unwrap();
            let req_id = v["requestId"].as_str().unwrap().to_string();

            // Simulate the SDK's queryResult Event payload (as the CLI server
            // would extract it before calling route_response).
            let payload = serde_json::json!({
                "op": "queryResult",
                "requestId": req_id,
                "ok": true,
                "result": {"databases": [{"name": "app.db"}]},
            });
            reg_for_sdk.route_response(&req_id, payload);
        });

        let (request_id, payload) = reg
            .dispatch("database", "listDatabases", serde_json::json!({}), Duration::from_secs(2))
            .await
            .expect("dispatch ok");

        assert!(request_id.starts_with("q-"), "requestId is q-N: {}", request_id);
        assert_eq!(payload["ok"], true);
        assert_eq!(payload["result"]["databases"][0]["name"], "app.db");

        sdk.await.unwrap();
    }

    // ── route_response: late / unsolicited is a no-op ─────────────────────────

    #[tokio::test]
    async fn route_response_for_unknown_request_id_is_noop() {
        let reg = ClientRegistry::new();
        // No pending waiter for "q-999" — routing must not panic or error.
        reg.route_response(
            "q-999",
            serde_json::json!({"op": "queryResult", "requestId": "q-999", "ok": true}),
        );
        // Nothing to assert beyond "did not panic"; the pending map stays empty.
        let pending_count = reg.pending.lock().unwrap_or_else(|e| e.into_inner()).len();
        assert_eq!(pending_count, 0);
    }

    #[tokio::test]
    async fn route_response_after_timeout_is_late_noop() {
        // A response arriving after dispatch timed out must not panic (the slot
        // was already reclaimed).
        let reg = ClientRegistry::new();
        let (tx, _rx) = mpsc::channel::<WsMessage>(4);
        reg.register(1, tx);

        let err = reg
            .dispatch("database", "listDatabases", serde_json::json!({}), Duration::from_millis(50))
            .await
            .expect_err("timeout");
        assert!(matches!(err, QueryError::Timeout));

        // Late response with the (unknown, reclaimed) requestId — silent no-op.
        reg.route_response(
            "q-1",
            serde_json::json!({"op": "queryResult", "requestId": "q-1", "ok": true}),
        );
    }

    // ── requestId counter ──────────────────────────────────────────────────────

    #[tokio::test]
    async fn request_ids_are_monotonic_q_n() {
        // Each NoClient dispatch still consumes a requestId before failing.
        let reg = ClientRegistry::new();
        // First dispatch fails (no client) but increments the counter.
        let _ = reg
            .dispatch("database", "listDatabases", serde_json::json!({}), Duration::from_millis(10))
            .await;
        let n_after_first = reg.counter.load(Ordering::Relaxed);
        assert_eq!(n_after_first, 1);

        let _ = reg
            .dispatch("database", "listTables", serde_json::json!({}), Duration::from_millis(10))
            .await;
        let n_after_second = reg.counter.load(Ordering::Relaxed);
        assert_eq!(n_after_second, 2);
    }
}
