//! Network response mocking: CLI-side mapping of `devlens mock ...` subcommands
//! onto the existing query/response path.
//!
//! Mock commands are dispatched as
//! `Request::Query { plugin: "network", method: <setMock|listMocks|removeMock|clearMocks>, params }`
//! through the unchanged control plane ([`crate::control::Request::Query`] →
//! [`crate::control_client`]). The SDK's `NetworkPlugin.onQuery` owns the rule
//! store and acks via a normal `queryResult` [`crate::protocol::Message::Event`].
//!
//! This module only builds the outbound `Request`. It touches no daemon state,
//! no WebSocket path, and no control-plane plumbing — the entire mock flow rides
//! the existing query/response infrastructure that `db` already uses.
//!
//! # Wire contract
//!
//! - `setMock` params (HTTP): `{"method":"GET","url":"...","status":200,
//!   "reason":"OK","headers":{"k":"v"},"body":"..."}` (optional fields omitted
//!   when absent; `method: null` = any method).
//! - `setMock` params (error): `{"method":"GET","url":"...","error":"..."}`.
//! - `listMocks` / `clearMocks` params: `{}`. `removeMock` params: `{"id":"..."}`.
//!
//! The CLI assigns NO rule id; the SDK assigns `rule-<uuid>` and returns it in
//! the ack. See `.work-state/artifacts/architecture.json` for the full contract.

use std::collections::HashMap;
use std::fmt;
use std::path::PathBuf;

use clap::{Args, Subcommand};

use crate::control::Request;

/// Exit code for mock-spec validation errors ([`MockSpecError`]). Reuses the
/// control plane's user-error code 2 (clap arg errors also exit 2), per
/// `architecture.json` `exit_codes`.
pub const EXIT_VALIDATION: u8 = 2;

/// `devlens mock` subcommands. Each maps to a `network` plugin query method.
///
/// Wire method names (`setMock` / `listMocks` / `removeMock` / `clearMocks`)
/// are protocol-stable; the kebab-case CLI spellings (`add` / `list` / `remove`
/// / `clear`) are clap's default rename for the enum variant names.
#[derive(Debug, Clone, Subcommand)]
pub enum MockCommand {
    /// Install a mock rule that short-circuits matching requests on the SDK.
    Add(MockAddArgs),

    /// List currently-active mock rules (one NDJSON line on stdout).
    List,

    /// Remove a single mock rule by its SDK-assigned id (`rule-<uuid>`).
    /// Idempotent: an unknown id acks with `removed:false` and exit 0.
    Remove {
        /// Rule id returned by a prior `setMock` ack.
        id: String,
    },

    /// Remove all mock rules.
    Clear,
}

/// Arguments for `devlens mock add`.
///
/// Two mutually exclusive flavors of mock are supported:
/// - **HTTP response** (default): a synthetic response with `--status` (default
///   200), optional `--reason`/`--body`/`--body-file`/`--header`.
/// - **Network error** (`--error`): the SDK throws an `IOException` with the
///   given message. `--error` conflicts with all response-shaping flags.
///
/// Validation split:
/// - At clap parse — `--url` required, `--body`/`--body-file` mutually exclusive,
///   `--error` conflicts with response flags, `--status` range 100..=599.
/// - In [`Self::to_params`] — non-empty url, `KEY=VALUE` header format, and
///   `--body-file` read (the only side-effecting check; necessarily runtime).
#[derive(Debug, Clone, Args)]
pub struct MockAddArgs {
    /// HTTP method to match (case-insensitive on the SDK). Omit = any method.
    #[arg(long)]
    pub method: Option<String>,

    /// URL substring to match (case-sensitive, `String.contains` on the SDK).
    /// Required; must be non-empty.
    #[arg(long, required = true)]
    pub url: String,

    /// HTTP status code to return (default 200, range 100..=599). Ignored with
    /// `--error` (passing both is a clap conflict → exit 2).
    #[arg(long, default_value_t = 200, value_parser = parse_status_code)]
    pub status: u16,

    /// HTTP reason phrase (e.g. `"OK"`, `"Not Found"`).
    #[arg(long)]
    pub reason: Option<String>,

    /// Response body text. Mutually exclusive with `--body-file`.
    #[arg(long, conflicts_with = "body_file")]
    pub body: Option<String>,

    /// Read response body from a file. Mutually exclusive with `--body`.
    #[arg(long, value_name = "PATH", conflicts_with = "body")]
    pub body_file: Option<PathBuf>,

    /// Response header as `KEY=VALUE`. Repeatable; last wins on duplicate key.
    #[arg(long = "header", value_name = "KEY=VALUE")]
    pub headers: Vec<String>,

    /// Simulate a network error: the SDK throws `IOException` with this message.
    /// Mutually exclusive with `--status`/`--reason`/`--body`/`--body-file`/
    /// `--header`.
    #[arg(long, conflicts_with_all = ["status", "reason", "body", "body_file", "headers"])]
    pub error: Option<String>,
}

/// Validation error raised by [`MockAddArgs::to_params`]. Surfaces to the user
/// as exit code [`EXIT_VALIDATION`] (2) with a clean stderr message.
#[derive(Debug)]
pub enum MockSpecError {
    /// `--url` resolved to an empty string.
    EmptyUrl,
    /// A `--header` value was not `KEY=VALUE` (no `=` found).
    BadHeader(String),
    /// `--body-file` could not be read.
    BodyFileRead {
        path: PathBuf,
        source: std::io::Error,
    },
}

impl fmt::Display for MockSpecError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            MockSpecError::EmptyUrl => {
                f.write_str("--url must be a non-empty substring")
            }
            MockSpecError::BadHeader(raw) => write!(
                f,
                "invalid --header '{}': expected KEY=VALUE (split on first '=')",
                raw
            ),
            MockSpecError::BodyFileRead { path, source } => write!(
                f,
                "failed to read --body-file {}: {}",
                path.display(),
                source
            ),
        }
    }
}

impl std::error::Error for MockSpecError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            MockSpecError::BodyFileRead { source, .. } => Some(source),
            _ => None,
        }
    }
}

/// Clap value parser enforcing HTTP status codes in the standard range.
///
/// Used via `#[arg(value_parser = parse_status_code)]`; a parse failure is
/// reported by clap as an argument error (exit 2) with this message. A u16
/// overflow (e.g. `"999999"`) is distinguished from non-numeric input so the
/// message is not misleading — `"999999"` IS an integer, just out of u16 range.
fn parse_status_code(raw: &str) -> Result<u16, String> {
    let n: u16 = raw.parse::<u16>().map_err(|e| match e.kind() {
        std::num::IntErrorKind::PosOverflow | std::num::IntErrorKind::NegOverflow => {
            format!("invalid status code '{}': integer overflow (must be 0..=65535)", raw)
        }
        _ => format!("invalid status code '{}': not an integer", raw),
    })?;
    if !(100..=599).contains(&n) {
        return Err(format!("status code {} out of range 100..=599", n));
    }
    Ok(n)
}

impl MockAddArgs {
    /// Build the `setMock` query params and run the runtime validation checks
    /// (non-empty url, header format, body-file read). Returns the JSON object
    /// delivered verbatim as the Query frame's `params` to the SDK's
    /// `NetworkPlugin.onQuery`.
    ///
    /// Optional fields are omitted when absent so the wire object stays minimal;
    /// `method` is always present (string, or `null` = wildcard).
    pub fn to_params(&self) -> Result<serde_json::Value, MockSpecError> {
        if self.url.trim().is_empty() {
            return Err(MockSpecError::EmptyUrl);
        }

        let mut params = serde_json::Map::new();

        // method: always present. null = wildcard (any method) on the SDK.
        let method_val = match &self.method {
            Some(m) => serde_json::Value::String(m.clone()),
            None => serde_json::Value::Null,
        };
        params.insert("method".into(), method_val);
        params.insert("url".into(), serde_json::Value::String(self.url.clone()));

        if let Some(err_msg) = &self.error {
            // Network-error mock: only method + url + error.
            params.insert(
                "error".into(),
                serde_json::Value::String(err_msg.clone()),
            );
        } else {
            // HTTP response mock.
            params.insert(
                "status".into(),
                serde_json::Value::Number(self.status.into()),
            );
            if let Some(reason) = &self.reason {
                params.insert(
                    "reason".into(),
                    serde_json::Value::String(reason.clone()),
                );
            }
            // body / body_file are clap-enforced mutually exclusive; exactly one
            // may be set. body_file is read here (side effect → runtime check).
            let body: Option<String> = match (&self.body, &self.body_file) {
                (Some(text), None) => Some(text.clone()),
                (None, Some(path)) => Some(
                    std::fs::read_to_string(path).map_err(|e| MockSpecError::BodyFileRead {
                        path: path.clone(),
                        source: e,
                    })?,
                ),
                _ => None,
            };
            if let Some(body) = body {
                params.insert("body".into(), serde_json::Value::String(body));
            }
            // Headers: split each on first '='; later duplicates overwrite
            // earlier ones (HashMap insert = last-wins). An empty key (left
            // side, trimmed) is rejected — `=value` and `   =value` are not
            // valid HTTP header names.
            if !self.headers.is_empty() {
                let mut headers: HashMap<String, String> = HashMap::new();
                for raw in &self.headers {
                    let (key, val) = raw
                        .split_once('=')
                        .ok_or_else(|| MockSpecError::BadHeader(raw.clone()))?;
                    if key.trim().is_empty() {
                        return Err(MockSpecError::BadHeader(raw.clone()));
                    }
                    headers.insert(key.to_string(), val.to_string());
                }
                let map: serde_json::Map<String, serde_json::Value> = headers
                    .into_iter()
                    .map(|(k, v)| (k, serde_json::Value::String(v)))
                    .collect();
                params.insert("headers".into(), serde_json::Value::Object(map));
            }
        }

        Ok(serde_json::Value::Object(params))
    }
}

/// Map a [`MockCommand`] onto a control-plane [`Request::Query`] targeted at the
/// `network` plugin. Mirrors [`crate::server::map_db_command`] (database) but is
/// fallible: only [`MockCommand::Add`] validates (via [`MockAddArgs::to_params`]);
/// the other variants carry no user input to check.
///
/// The caller's per-invocation `query_timeout_ms` threads through as
/// `Request::Query::timeout_ms` so the daemon honors it for this dispatch.
pub fn map_command(
    mock: MockCommand,
    query_timeout_ms: u64,
) -> Result<Request, MockSpecError> {
    let (method, params) = match mock {
        MockCommand::Add(args) => ("setMock", args.to_params()?),
        MockCommand::List => ("listMocks", serde_json::json!({})),
        MockCommand::Remove { id } => ("removeMock", serde_json::json!({ "id": id })),
        MockCommand::Clear => ("clearMocks", serde_json::json!({})),
    };
    Ok(Request::Query {
        plugin: "network".to_string(),
        method: method.to_string(),
        params,
        timeout_ms: Some(query_timeout_ms),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: build `MockAddArgs` with sensible defaults for HTTP mocks.
    fn add_args(url: &str) -> MockAddArgs {
        MockAddArgs {
            method: None,
            url: url.to_string(),
            status: 200,
            reason: None,
            body: None,
            body_file: None,
            headers: Vec::new(),
            error: None,
        }
    }

    // ── to_params: HTTP response mock ────────────────────────────────────────

    #[test]
    fn to_params_http_minimal_emits_method_url_status() {
        let args = MockAddArgs {
            method: Some("GET".into()),
            ..add_args("/people/")
        };
        let v = args.to_params().expect("ok");
        assert_eq!(v["method"], "GET");
        assert_eq!(v["url"], "/people/");
        assert_eq!(v["status"], 200);
        // Optional fields absent.
        assert!(v.get("reason").is_none());
        assert!(v.get("body").is_none());
        assert!(v.get("headers").is_none());
        assert!(v.get("error").is_none());
    }

    #[test]
    fn to_params_http_full_shape() {
        let args = MockAddArgs {
            method: Some("POST".into()),
            reason: Some("Created".into()),
            body: Some("{\"k\":1}".into()),
            headers: vec!["X-A=1".into(), "X-B=2".into()],
            ..add_args("/items/")
        };
        let v = args.to_params().expect("ok");
        assert_eq!(v["method"], "POST");
        assert_eq!(v["url"], "/items/");
        assert_eq!(v["status"], 200);
        assert_eq!(v["reason"], "Created");
        assert_eq!(v["body"], "{\"k\":1}");
        assert_eq!(v["headers"]["X-A"], "1");
        assert_eq!(v["headers"]["X-B"], "2");
        assert!(v.get("error").is_none());
    }

    #[test]
    fn to_params_status_override_honored() {
        let args = MockAddArgs {
            status: 404,
            ..add_args("/missing/")
        };
        let v = args.to_params().expect("ok");
        assert_eq!(v["status"], 404);
    }

    // ── to_params: method omitted → null ─────────────────────────────────────

    #[test]
    fn to_params_method_omitted_is_null_wildcard() {
        let args = add_args("/any/");
        let v = args.to_params().expect("ok");
        assert!(v["method"].is_null(), "omitted method must serialize as null");
        assert_eq!(v["url"], "/any/");
    }

    // ── to_params: error mock ────────────────────────────────────────────────

    #[test]
    fn to_params_error_mock_omits_response_fields() {
        let args = MockAddArgs {
            error: Some("Connection reset".into()),
            ..add_args("/flaky/")
        };
        let v = args.to_params().expect("ok");
        assert_eq!(v["error"], "Connection reset");
        assert_eq!(v["url"], "/flaky/");
        // Even though status defaulted to 200 (clap default), it must NOT be
        // emitted for an error mock.
        assert!(v.get("status").is_none());
        assert!(v.get("reason").is_none());
        assert!(v.get("body").is_none());
        assert!(v.get("headers").is_none());
    }

    // ── to_params: headers — split on first '=', last wins ───────────────────

    #[test]
    fn to_params_header_split_on_first_equals_keeps_value_equals() {
        let args = MockAddArgs {
            headers: vec!["Authorization=Bearer abc==def".into()],
            ..add_args("/auth/")
        };
        let v = args.to_params().expect("ok");
        assert_eq!(v["headers"]["Authorization"], "Bearer abc==def");
    }

    #[test]
    fn to_params_header_last_wins_on_duplicate_key() {
        let args = MockAddArgs {
            headers: vec!["X-A=1".into(), "X-A=2".into(), "X-B=3".into()],
            ..add_args("/dup/")
        };
        let v = args.to_params().expect("ok");
        assert_eq!(v["headers"]["X-A"], "2", "last duplicate wins");
        assert_eq!(v["headers"]["X-B"], "3");
    }

    #[test]
    fn to_params_bad_header_missing_equals_is_err() {
        let args = MockAddArgs {
            headers: vec!["NoEqualsHere".into()],
            ..add_args("/x/")
        };
        let err = args.to_params().expect_err("bad header");
        assert!(matches!(err, MockSpecError::BadHeader(s) if s == "NoEqualsHere"));
    }

    #[test]
    fn to_params_bad_header_empty_key_is_err() {
        // R2: `=value` has an empty key (left of '='), which is not a valid
        // HTTP header name and must be rejected as BadHeader.
        let args = MockAddArgs {
            headers: vec!["=value".into()],
            ..add_args("/x/")
        };
        let err = args.to_params().expect_err("empty key header");
        assert!(matches!(err, MockSpecError::BadHeader(s) if s == "=value"));
    }

    #[test]
    fn to_params_bad_header_whitespace_key_is_err() {
        // R2: a whitespace-only key (trimmed to empty) is also rejected.
        let args = MockAddArgs {
            headers: vec!["   =value".into()],
            ..add_args("/x/")
        };
        let err = args.to_params().expect_err("whitespace-only key header");
        assert!(matches!(err, MockSpecError::BadHeader(s) if s == "   =value"));
    }

    // ── to_params: url validation ────────────────────────────────────────────

    #[test]
    fn to_params_empty_url_is_err() {
        let args = add_args("   ");
        let err = args.to_params().expect_err("empty url");
        assert!(matches!(err, MockSpecError::EmptyUrl));
    }

    // ── to_params: body-file read ───────────────────────────────────────────

    #[test]
    fn to_params_body_file_is_read_into_body() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("body.txt");
        std::fs::write(&path, "hello-from-file").expect("write");
        let args = MockAddArgs {
            body_file: Some(path.clone()),
            ..add_args("/file/")
        };
        let v = args.to_params().expect("ok");
        assert_eq!(v["body"], "hello-from-file");
    }

    #[test]
    fn to_params_body_file_missing_is_err() {
        let args = MockAddArgs {
            body_file: Some(PathBuf::from("/no/such/path/here/xyz")),
            ..add_args("/file/")
        };
        let err = args.to_params().expect_err("missing file");
        assert!(matches!(err, MockSpecError::BodyFileRead { .. }));
    }

    // ── to_params: the exact DoD-1 / e2e example ─────────────────────────────
    //
    // `mock add --method GET --url people/ --status 200 --body '{"x":1}'`
    // must yield the wire params the Android side cross-checks against.

    #[test]
    fn to_params_dod_example_shape() {
        let args = MockAddArgs {
            method: Some("GET".into()),
            status: 200,
            body: Some("{\"x\":1}".into()),
            ..add_args("people/")
        };
        let v = args.to_params().expect("ok");
        assert_eq!(v["method"], "GET");
        assert_eq!(v["url"], "people/");
        assert_eq!(v["status"], 200);
        assert_eq!(v["body"], "{\"x\":1}");
        assert!(v.get("reason").is_none());
        assert!(v.get("headers").is_none());
        assert!(v.get("error").is_none());
        // Exactly these four keys, nothing else.
        let keys: Vec<&str> = v.as_object().unwrap().keys().map(String::as_str).collect();
        let mut expected = keys.clone();
        expected.sort();
        assert_eq!(expected, vec!["body", "method", "status", "url"]);
    }

    // ── map_command: each variant → Request::Query{plugin:"network"} ─────────

    #[test]
    fn map_command_add_targets_network_set_mock() {
        let req = map_command(
            MockCommand::Add(MockAddArgs {
                method: Some("GET".into()),
                ..add_args("/x/")
            }),
            5_000,
        )
        .expect("ok");
        match req {
            Request::Query { plugin, method, params, timeout_ms } => {
                assert_eq!(plugin, "network");
                assert_eq!(method, "setMock");
                assert_eq!(params["url"], "/x/");
                assert_eq!(params["method"], "GET");
                assert_eq!(timeout_ms, Some(5_000));
            }
            other => panic!("expected Query, got {:?}", other),
        }
    }

    #[test]
    fn map_command_list_targets_network_list_mocks_empty_params() {
        let req = map_command(MockCommand::List, 1_000).expect("ok");
        match req {
            Request::Query { plugin, method, params, timeout_ms } => {
                assert_eq!(plugin, "network");
                assert_eq!(method, "listMocks");
                assert_eq!(params, serde_json::json!({}));
                assert_eq!(timeout_ms, Some(1_000));
            }
            other => panic!("expected Query, got {:?}", other),
        }
    }

    #[test]
    fn map_command_remove_targets_network_remove_mock_with_id() {
        let req =
            map_command(MockCommand::Remove { id: "rule-abc".into() }, 2_000).expect("ok");
        match req {
            Request::Query { plugin, method, params, timeout_ms } => {
                assert_eq!(plugin, "network");
                assert_eq!(method, "removeMock");
                assert_eq!(params["id"], "rule-abc");
                assert_eq!(timeout_ms, Some(2_000));
            }
            other => panic!("expected Query, got {:?}", other),
        }
    }

    #[test]
    fn map_command_clear_targets_network_clear_mocks_empty_params() {
        let req = map_command(MockCommand::Clear, 3_000).expect("ok");
        match req {
            Request::Query { plugin, method, params, timeout_ms } => {
                assert_eq!(plugin, "network");
                assert_eq!(method, "clearMocks");
                assert_eq!(params, serde_json::json!({}));
                assert_eq!(timeout_ms, Some(3_000));
            }
            other => panic!("expected Query, got {:?}", other),
        }
    }

    #[test]
    fn map_command_add_propagates_validation_error() {
        // Empty url → to_params() Err → map_command() Err (no Request built).
        let req = map_command(MockCommand::Add(add_args("   ")), 1_000);
        assert!(req.is_err());
    }

    // ── MockSpecError Display ────────────────────────────────────────────────

    #[test]
    fn mock_spec_error_displays_cleanly() {
        assert!(
            MockSpecError::EmptyUrl.to_string().contains("--url"),
            "EmptyUrl message should mention --url"
        );
        let bad = MockSpecError::BadHeader("oops".into()).to_string();
        assert!(bad.contains("oops") && bad.contains("KEY=VALUE"));
        let io = std::io::Error::new(std::io::ErrorKind::NotFound, "missing");
        let read = MockSpecError::BodyFileRead {
            path: PathBuf::from("/tmp/x"),
            source: io,
        }
        .to_string();
        assert!(read.contains("/tmp/x") && read.contains("missing"));
    }

    // ── status value parser ──────────────────────────────────────────────────

    #[test]
    fn parse_status_code_accepts_valid_range() {
        assert_eq!(parse_status_code("100").unwrap(), 100);
        assert_eq!(parse_status_code("200").unwrap(), 200);
        assert_eq!(parse_status_code("599").unwrap(), 599);
    }

    #[test]
    fn parse_status_code_rejects_out_of_range() {
        assert!(parse_status_code("99").is_err());
        assert!(parse_status_code("600").is_err());
    }

    #[test]
    fn parse_status_code_rejects_non_numeric() {
        assert!(parse_status_code("OK").is_err());
        assert!(parse_status_code("").is_err());
    }

    #[test]
    fn parse_status_code_overflow_not_labeled_not_an_integer() {
        // R6: a u16 overflow (e.g. "999999") is an integer, just too large for
        // u16. The message must distinguish this from non-numeric input rather
        // than misleadingly saying "not an integer".
        let err = parse_status_code("999999").unwrap_err();
        assert!(
            !err.contains("not an integer"),
            "overflow case must not be labeled 'not an integer': {}",
            err
        );
        assert!(
            err.contains("999999"),
            "message should include the offending input: {}",
            err
        );
    }

    #[test]
    fn parse_status_code_non_numeric_keeps_not_an_integer_message() {
        // R6: genuinely non-numeric input retains the "not an integer" message.
        let err = parse_status_code("OK").unwrap_err();
        assert!(
            err.contains("not an integer"),
            "non-numeric input should say 'not an integer': {}",
            err
        );
    }
}
