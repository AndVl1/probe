//! Agent control plane: a Unix-domain-socket server living inside the daemon
//! that answers `start` / `dump` / `end` / `status` / `shutdown` requests, plus
//! the typed request/response protocol shared with [`control_client`].
//!
//! # Lock order
//!
//! When handling a `dump`, the control handler takes the `ControlState` lock
//! first and then calls [`EventBuffer::dump_since`] (which locks the buffer).
//! This `state -> buffer` ordering is mandatory and must never be inverted; the
//! SDK serve path ([`crate::server::handle_connection`]) only ever locks the
//! buffer, so the two never deadlock.
//!
//! # Protocol
//!
//! One JSON request line in, one JSON response-header line out, followed by
//! `count` raw NDJSON body lines for `dump` (the first of which is a
//! `{type:gap}` envelope when entries were lost to eviction).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use anyhow::Result;
use serde::{Deserialize, Serialize};

use crate::buffer::EventBuffer;

/// Default implicit session used by `dump`/`start`/`end` when no `--session`
/// is supplied. Created at daemon start with its cursor at the (then-empty)
/// high watermark so a first `devlens dump` returns everything captured since.
pub const DEFAULT_SESSION: &str = "default";

/// Process exit codes used by the ephemeral control subcommands.
///
/// These are part of the agent-facing contract — `devlens dump` returning `2`
/// means "named session not found", etc.
pub mod exit {
    /// Success (including an empty dump).
    pub const OK: u8 = 0;
    /// Daemon not running / socket / IO error.
    pub const IO: u8 = 1;
    /// Named session not found.
    pub const NOT_FOUND: u8 = 2;
    /// `start` on an existing session without `--reset`.
    pub const EXISTS: u8 = 3;
    /// `serve` when a daemon is already running.
    pub const RUNNING: u8 = 4;
    /// `db` subcommand: no SDK client connected (or it went away mid-query).
    pub const NO_CLIENT: u8 = 5;
    /// `db` subcommand: SDK did not respond within the query timeout.
    pub const TIMEOUT: u8 = 6;
    /// `db` subcommand: plugin returned `ok:false` or a malformed response.
    pub const PLUGIN_ERROR: u8 = 7;
}

/// Per-session cursor state: `session name -> last-dumped high watermark`.
#[derive(Debug, Default)]
pub struct ControlState {
    pub sessions: HashMap<String, u64>,
}

impl ControlState {
    pub fn new() -> Self {
        Self::default()
    }

    /// Create state seeded with the default session positioned at `cursor`.
    pub fn with_default_cursor(cursor: u64) -> Self {
        let mut s = Self::default();
        s.sessions.insert(DEFAULT_SESSION.to_string(), cursor);
        s
    }
}

/// Informational record published by the daemon (also written to
/// `~/.devlens/daemon.json`) and returned by a `status` request.
#[derive(Debug, Clone)]
pub struct DaemonInfo {
    pub pid: u32,
    pub port: u16,
    pub socket_path: String,
    pub started_at: i64,
}

/// Control request envelope (line-delimited JSON from the client).
///
/// `Eq` is intentionally NOT derived: the [`Request::Query`] variant carries a
/// `serde_json::Value` (arbitrary JSON), which is not `Eq` (it may hold
/// floats). Structural `PartialEq` is sufficient for the round-trip tests.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum Request {
    Start {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        session: Option<String>,
        #[serde(default)]
        reset: bool,
    },
    Dump {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        session: Option<String>,
    },
    End {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        session: Option<String>,
    },
    Status,
    Shutdown,
    /// Forward a query to the active SDK client and await its response.
    /// `op` tag = `"query"`.
    ///
    /// `timeout_ms` overrides the daemon-side default query timeout for this
    /// single invocation when present (`Some`). The control-plane handler
    /// falls back to the daemon's `--query-timeout-ms` value when `None`.
    Query {
        plugin: String,
        method: String,
        #[serde(default)]
        params: serde_json::Value,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        timeout_ms: Option<u64>,
    },
}

/// Control response header (line-delimited JSON from the daemon). For `dump`,
/// the header is followed by `count` raw NDJSON body lines on stdout. For
/// [`Response::QueryResult`], the header is followed by exactly 1 NDJSON body
/// line carrying the query `body` (mirroring the dump header+body pattern).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Response {
    /// Operation succeeded. `count` is the number of NDJSON body lines that
    /// follow (dump only); `dropped` is the eviction gap size (0 when no gap).
    Ok {
        op: String,
        count: usize,
        dropped: u64,
    },
    /// `status` response: daemon snapshot.
    Status {
        pid: u32,
        port: u16,
        socket_path: String,
        started_at: i64,
        sessions: HashMap<String, u64>,
    },
    /// Operation failed. `code` is the process exit code the client should use.
    Error {
        op: String,
        code: u8,
        message: String,
    },
    /// `db` query result. The header carries `request_id` + `ok`; the `body`
    /// value is written as 1 NDJSON line immediately after the header. `ok`
    /// reflects whether the SDK reported success; the client maps the body's
    /// `code` field (no_client/timeout) or `ok:false` to the exit code.
    QueryResult {
        request_id: String,
        ok: bool,
    },
}

// ─── Unix-domain-socket server (unix-only) ──────────────────────────────────

#[cfg(unix)]
mod unix {
    use super::*;
    use crate::query::{ClientRegistry, QueryError};
    use std::time::Duration;
    use tokio::io::{AsyncBufReadExt, AsyncWrite, AsyncWriteExt, BufReader};
    use tokio::net::{UnixListener, UnixStream};
    use tokio::sync::broadcast;

    /// Run the control listener until a shutdown signal is broadcast or the
    /// listener fails. Each accepted connection is handled on its own task.
    pub async fn run_control_listener(
        listener: UnixListener,
        buffer: Arc<EventBuffer>,
        registry: Arc<ClientRegistry>,
        state: Arc<Mutex<ControlState>>,
        info: Arc<DaemonInfo>,
        shutdown_tx: broadcast::Sender<()>,
        query_timeout: Duration,
    ) {
        let mut shutdown_rx = shutdown_tx.subscribe();
        loop {
            tokio::select! {
                biased;
                _ = shutdown_rx.recv() => {
                    tracing::debug!("control listener shutting down");
                    break;
                }
                res = listener.accept() => {
                    match res {
                        Ok((stream, _peer)) => {
                            let buf = Arc::clone(&buffer);
                            let reg = Arc::clone(&registry);
                            let st = Arc::clone(&state);
                            let inf = Arc::clone(&info);
                            let stx = shutdown_tx.clone();
                            let qt = query_timeout;
                            tokio::spawn(async move {
                                if let Err(e) = handle_control_connection(
                                    stream, buf, reg, st, inf, stx, qt,
                                )
                                .await
                                {
                                    tracing::warn!("control connection error: {}", e);
                                }
                            });
                        }
                        Err(e) => {
                            tracing::warn!("control accept error: {}", e);
                            break;
                        }
                    }
                }
            }
        }
    }

    async fn handle_control_connection(
        stream: UnixStream,
        buffer: Arc<EventBuffer>,
        registry: Arc<ClientRegistry>,
        state: Arc<Mutex<ControlState>>,
        info: Arc<DaemonInfo>,
        shutdown_tx: broadcast::Sender<()>,
        query_timeout: Duration,
    ) -> Result<()> {
        let (read_half, mut write_half) = stream.into_split();
        let mut reader = BufReader::new(read_half);
        let mut line = String::new();
        reader.read_line(&mut line).await?;
        let request: Request = serde_json::from_str(line.trim())
            .map_err(|e| anyhow::anyhow!("invalid control request: {}", e))?;

        match request {
            Request::Start { session, reset } => {
                let name = session.unwrap_or_else(|| DEFAULT_SESSION.to_string());
                let resp = {
                    let mut st = state.lock().unwrap_or_else(|e| e.into_inner());
                    if st.sessions.contains_key(&name) && !reset {
                        Response::Error {
                            op: "start".into(),
                            code: exit::EXISTS,
                            message: format!(
                                "session '{}' already exists (use --reset to reposition)",
                                name
                            ),
                        }
                    } else {
                        // Position the session cursor at the current high
                        // watermark so subsequent dumps return only new events.
                        let h = buffer.high_watermark();
                        st.sessions.insert(name.clone(), h);
                        Response::Ok { op: "start".into(), count: 0, dropped: 0 }
                    }
                };
                write_response(&mut write_half, &resp).await?;
            }
            Request::Dump { session } => {
                let name = session.unwrap_or_else(|| DEFAULT_SESSION.to_string());
                // Compute response under state->buffer lock order, then release.
                let (header, body): (Response, Vec<serde_json::Value>) = {
                    let mut st = state.lock().unwrap_or_else(|e| e.into_inner());
                    if !st.sessions.contains_key(&name) && name != DEFAULT_SESSION {
                        (
                            Response::Error {
                                op: "dump".into(),
                                code: exit::NOT_FOUND,
                                message: format!("session '{}' not found", name),
                            },
                            Vec::new(),
                        )
                    } else {
                        // Default session auto-creates at the current high
                        // watermark (convenience: `dump` without `start`).
                        if !st.sessions.contains_key(&name) {
                            let h = buffer.high_watermark();
                            st.sessions.insert(name.clone(), h);
                        }
                        let cursor = *st.sessions.get(&name).expect("present");
                        let result = buffer.dump_since(cursor);
                        // Race-free cursor advance: snapshot high under the same
                        // buffer lock that produced `entries`.
                        st.sessions.insert(name, result.high_watermark);
                        let mut body: Vec<serde_json::Value> = Vec::new();
                        let dropped = match result.gap {
                            Some(g) => {
                                body.push(crate::protocol::lifecycle::gap(
                                    g.dropped,
                                    crate::protocol::lifecycle::now_millis(),
                                ));
                                g.dropped
                            }
                            None => 0,
                        };
                        body.extend(result.entries);
                        let count = body.len();
                        (Response::Ok { op: "dump".into(), count, dropped }, body)
                    }
                };
                write_response(&mut write_half, &header).await?;
                // Body lines (verbatim NDJSON) — gap envelope first, if any.
                for item in &body {
                    let s = serde_json::to_string(item)?;
                    write_half.write_all(s.as_bytes()).await?;
                    write_half.write_all(b"\n").await?;
                }
            }
            Request::End { session } => {
                let name = session.unwrap_or_else(|| DEFAULT_SESSION.to_string());
                let resp = {
                    let mut st = state.lock().unwrap_or_else(|e| e.into_inner());
                    if st.sessions.remove(&name).is_some() {
                        Response::Ok { op: "end".into(), count: 0, dropped: 0 }
                    } else {
                        Response::Error {
                            op: "end".into(),
                            code: exit::NOT_FOUND,
                            message: format!("session '{}' not found", name),
                        }
                    }
                };
                write_response(&mut write_half, &resp).await?;
            }
            Request::Status => {
                let resp = {
                    let st = state.lock().unwrap_or_else(|e| e.into_inner());
                    Response::Status {
                        pid: info.pid,
                        port: info.port,
                        socket_path: info.socket_path.clone(),
                        started_at: info.started_at,
                        sessions: st.sessions.clone(),
                    }
                };
                write_response(&mut write_half, &resp).await?;
            }
            Request::Shutdown => {
                // Acknowledge before pulling the plug so the client gets its
                // exit-0 response.
                write_response(
                    &mut write_half,
                    &Response::Ok { op: "shutdown".into(), count: 0, dropped: 0 },
                )
                .await?;
                write_half.flush().await?;
                let _ = shutdown_tx.send(());
                return Ok(());
            }
            Request::Query { plugin, method, params, timeout_ms } => {
                // Dispatch to the active SDK client and await its queryResult.
                // The header carries request_id + ok; `body` is written as 1
                // NDJSON line after the header. Exit codes are mapped on the
                // client from (ok, body.code).
                //
                // Per-invocation timeout wins over the daemon default so
                // `devlens db ... --query-timeout-ms N` actually shortens the
                // wait for that one invocation (M3).
                let effective_timeout = match timeout_ms {
                    Some(ms) => Duration::from_millis(ms),
                    None => query_timeout,
                };
                let outcome = registry
                    .dispatch(&plugin, &method, params, effective_timeout)
                    .await;
                let (header, body): (Response, serde_json::Value) = match outcome {
                    Ok((request_id, payload)) => {
                        let ok = payload
                            .get("ok")
                            .and_then(|v| v.as_bool())
                            .unwrap_or(false);
                        let body = if ok {
                            payload
                                .get("result")
                                .cloned()
                                .unwrap_or(serde_json::Value::Null)
                        } else {
                            // Plugin error: forward the `error` object verbatim.
                            payload
                                .get("error")
                                .cloned()
                                .unwrap_or(serde_json::Value::Null)
                        };
                        (Response::QueryResult { request_id, ok }, body)
                    }
                    Err(QueryError::NoClient) => (
                        Response::QueryResult {
                            request_id: String::new(),
                            ok: false,
                        },
                        serde_json::json!({"code": "no_client"}),
                    ),
                    Err(QueryError::ClientGone) => (
                        // Client disconnected mid-flight — indistinguishable to
                        // the agent from "no client". Maps to exit NO_CLIENT.
                        Response::QueryResult {
                            request_id: String::new(),
                            ok: false,
                        },
                        serde_json::json!({"code": "no_client"}),
                    ),
                    Err(QueryError::Timeout) => (
                        Response::QueryResult {
                            request_id: String::new(),
                            ok: false,
                        },
                        serde_json::json!({"code": "timeout"}),
                    ),
                };
                write_response(&mut write_half, &header).await?;
                // Body: exactly 1 NDJSON line (compact JSON via Value::Display).
                let body_line = body.to_string();
                write_half.write_all(body_line.as_bytes()).await?;
                write_half.write_all(b"\n").await?;
            }
        }

        write_half.flush().await?;
        Ok(())
    }

    async fn write_response<W: AsyncWrite + Unpin>(w: &mut W, r: &Response) -> Result<()> {
        let s = serde_json::to_string(r)?;
        w.write_all(s.as_bytes()).await?;
        w.write_all(b"\n").await?;
        Ok(())
    }
}

#[cfg(unix)]
pub use unix::run_control_listener;

#[cfg(test)]
mod tests {
    use super::*;

    // ── Request / Response serde round-trips ───────────────────────────────────

    #[test]
    fn request_start_serializes_with_op_tag() {
        let r = Request::Start { session: Some("s1".into()), reset: true };
        let v: serde_json::Value = serde_json::from_str(&serde_json::to_string(&r).unwrap()).unwrap();
        assert_eq!(v["op"], "start");
        assert_eq!(v["session"], "s1");
        assert_eq!(v["reset"], true);
    }

    #[test]
    fn request_start_omits_null_session() {
        let r = Request::Start { session: None, reset: false };
        let s = serde_json::to_string(&r).unwrap();
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["op"], "start");
        assert!(v.get("session").is_none(), "null session should be skipped");
        assert_eq!(v["reset"], false);
    }

    #[test]
    fn request_roundtrip_all_variants() {
        let cases = vec![
            Request::Start { session: None, reset: false },
            Request::Start { session: Some("x".into()), reset: true },
            Request::Dump { session: Some("x".into()) },
            Request::Dump { session: None },
            Request::End { session: Some("x".into()) },
            Request::Status,
            Request::Shutdown,
        ];
        for r in cases {
            let s = serde_json::to_string(&r).unwrap();
            let back: Request = serde_json::from_str(&s).unwrap();
            assert_eq!(back, r, "round-trip failed for {}", s);
        }
    }

    #[test]
    fn request_parses_op_field() {
        let s = r#"{"op":"dump","session":"foo"}"#;
        let r: Request = serde_json::from_str(s).unwrap();
        assert_eq!(r, Request::Dump { session: Some("foo".into()) });
    }

    #[test]
    fn request_query_roundtrip_omits_null_timeout() {
        let r = Request::Query {
            plugin: "database".into(),
            method: "listDatabases".into(),
            params: serde_json::json!({}),
            timeout_ms: None,
        };
        let s = serde_json::to_string(&r).unwrap();
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["op"], "query");
        assert_eq!(v["plugin"], "database");
        assert_eq!(v["method"], "listDatabases");
        assert!(
            v.get("timeout_ms").is_none(),
            "None timeout_ms must be skipped; got {}",
            s
        );
        let back: Request = serde_json::from_str(&s).unwrap();
        assert_eq!(back, r);
    }

    #[test]
    fn request_query_roundtrip_with_timeout() {
        let r = Request::Query {
            plugin: "database".into(),
            method: "listTables".into(),
            params: serde_json::json!({"database": "app.db"}),
            timeout_ms: Some(500),
        };
        let s = serde_json::to_string(&r).unwrap();
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["timeout_ms"], 500);
        let back: Request = serde_json::from_str(&s).unwrap();
        assert_eq!(back, r);
    }

    #[test]
    fn response_ok_roundtrip() {
        let r = Response::Ok { op: "dump".into(), count: 3, dropped: 2 };
        let s = serde_json::to_string(&r).unwrap();
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["kind"], "ok");
        assert_eq!(v["count"], 3);
        assert_eq!(v["dropped"], 2);
    }

    #[test]
    fn response_error_roundtrip() {
        let r = Response::Error {
            op: "start".into(),
            code: exit::EXISTS,
            message: "exists".into(),
        };
        let s = serde_json::to_string(&r).unwrap();
        let v: serde_json::Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["kind"], "error");
        assert_eq!(v["code"], exit::EXISTS as u64);
    }

    #[test]
    fn control_state_default_session_seeded() {
        let st = ControlState::with_default_cursor(42);
        assert_eq!(st.sessions.get(DEFAULT_SESSION), Some(&42));
    }

    #[test]
    fn exit_codes_match_contract() {
        assert_eq!(exit::OK, 0);
        assert_eq!(exit::IO, 1);
        assert_eq!(exit::NOT_FOUND, 2);
        assert_eq!(exit::EXISTS, 3);
        assert_eq!(exit::RUNNING, 4);
    }
}
