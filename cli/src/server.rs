use std::fs::OpenOptions;
use std::io::Write as IoWrite;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use anyhow::Result;
use futures_util::{StreamExt, SinkExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio_tungstenite::accept_async;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tracing::{error, info, warn};

use crate::buffer::EventBuffer;
use crate::control::{
    ControlState, DaemonInfo, exit, run_control_listener,
};
#[cfg(unix)]
use crate::control_client;
use crate::display::Displayer;
use crate::filter::Filter;
use crate::protocol::{HelloMessage, Message};
use crate::query::ClientRegistry;
use crate::{Args, Command, DEFAULT_BUFFER_CAPACITY};

/// Monotonic connection-id generator. Each accepted WS handshake gets a fresh
/// `conn_id` so the [`ClientRegistry`] can distinguish a stale disconnect from
/// a newer connection's active registration.
static CONN_ID_COUNTER: AtomicU64 = AtomicU64::new(1);

fn next_conn_id() -> u64 {
    CONN_ID_COUNTER.fetch_add(1, Ordering::Relaxed)
}

pub async fn run_server(args: Arc<Args>) -> Result<()> {
    let addr = format!("0.0.0.0:{}", args.port);
    let listener = TcpListener::bind(&addr).await?;
    // Use the actually-bound port (matters for `--port 0`, which picks an
    // ephemeral port) so the banner and daemon.json are accurate.
    let bound_port = listener
        .local_addr()
        .map(|a| a.port())
        .unwrap_or(args.port);

    // Pretty (human, colored) output is opt-in: build a Displayer only when
    // `--pretty` or `DEVLENS_PRETTY=1` (the env is resolved manually because
    // clap's bool env parser rejects "1"). The default agent daemon keeps
    // stdout silent (NDJSON is only ever produced by `devlens dump`).
    let pretty = args.pretty || std::env::var("DEVLENS_PRETTY").as_deref() == Ok("1");
    let displayer: Option<Arc<Displayer>> = if pretty {
        Some(Arc::new(Displayer::new(args.verbose, args.bodies, args.no_color)))
    } else {
        None
    };
    if let Some(d) = displayer.as_ref() {
        d.print_startup_banner(bound_port);
    }

    let filter = Arc::new(Filter::new(args.filter.as_deref(), args.min_size)?);

    // Ring buffer of recent events (CLI cap: 10_000 entries, FIFO eviction).
    let buffer = Arc::new(EventBuffer::new(DEFAULT_BUFFER_CAPACITY));

    // Active SDK client + pending query map. Threaded into both the WS serve
    // path (so inbound queryResult Events route to the dispatcher) and the
    // control plane (so `devlens db ...` can dispatch a Query to the SDK).
    let registry = Arc::new(ClientRegistry::new());

    // Optional JSONL save file — opened once, shared across connections
    let save_file: Option<Arc<Mutex<std::fs::File>>> = if let Some(path) = args.save.as_ref() {
        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)
            .map_err(|e| anyhow::anyhow!("Failed to open save file '{}': {}", path.display(), e))?;
        // Human-facing log -> stderr so agent stdout stays clean NDJSON.
        eprintln!("Saving transactions to: {}", path.display());
        Some(Arc::new(Mutex::new(file)))
    } else {
        None
    };

    // ── Control plane (Unix only) ────────────────────────────────────────────
    // Bind the daemon control socket, write daemon.json, spawn the control
    // listener and a signal handler, and arm a shutdown broadcast. The accept
    // loop below selects on shutdown so `devlens shutdown` / SIGTERM / SIGINT
    // bring the daemon down cleanly.
    #[cfg(unix)]
    let mut ctrl = setup_control_plane(
        &args,
        Arc::clone(&buffer),
        Arc::clone(&registry),
        bound_port,
    )?;

    loop {
        // Accept with optional shutdown racing (unix only).
        #[cfg(unix)]
        let accept_result = {
            tokio::select! {
                biased;
                _ = ctrl.shutdown_rx.recv() => {
                    info!("shutdown signal received, stopping daemon");
                    break;
                }
                r = listener.accept() => r,
            }
        };
        #[cfg(not(unix))]
        let accept_result = listener.accept().await;

        match accept_result {
            Ok((stream, peer_addr)) => {
                info!("New TCP connection from {}", peer_addr);
                let args_clone = Arc::clone(&args);
                let displayer_clone = displayer.clone();
                let filter_clone = Arc::clone(&filter);
                let buffer_clone = Arc::clone(&buffer);
                let save_file_clone = save_file.clone();
                let registry_clone = Arc::clone(&registry);
                tokio::spawn(async move {
                    if let Err(e) = handle_connection(
                        stream,
                        args_clone,
                        displayer_clone,
                        filter_clone,
                        buffer_clone,
                        save_file_clone,
                        registry_clone,
                    )
                    .await
                    {
                        error!("Connection error: {}", e);
                    }
                });
            }
            Err(e) => {
                error!("Failed to accept connection: {}", e);
            }
        }
    }

    #[cfg(unix)]
    {
        ctrl.cleanup();
    }
    #[cfg(unix)]
    return Ok(());

    #[cfg(not(unix))]
    // Unreachable: the loop above never breaks on non-Unix. Kept so the fn
    // body type-checks without a spurious trailing return.
    {
        unreachable!("non-unix accept loop is infinite")
    }
}

/// Dispatch a non-`serve` subcommand against a running daemon. Returns the
/// process exit code that `main` should propagate.
#[cfg(unix)]
pub async fn run_control_subcommand(args: &Args, command: Command) -> Result<u8> {
    let socket = crate::resolve_socket_path(args);
    let request = match command {
        Command::Start { session, reset } => crate::control::Request::Start { session, reset },
        Command::Dump { session } => crate::control::Request::Dump { session },
        Command::End { session } => crate::control::Request::End { session },
        Command::Status => crate::control::Request::Status,
        Command::Shutdown => crate::control::Request::Shutdown,
        // Serve is handled by run_server, not here.
        Command::Serve => unreachable!("serve is not a control subcommand"),
        Command::Db { db } => map_db_command(db, args.query_timeout_ms),
        Command::Mock { mock } => match crate::mock::map_command(mock, args.query_timeout_ms) {
            Ok(req) => req,
            Err(e) => {
                // MockSpecError = user argument/validation error → exit 2.
                eprintln!("devlens: {}", e);
                return Ok(crate::mock::EXIT_VALIDATION);
            }
        },
    };
    control_client::run_command(&socket, request).await
}

/// Map a [`DbCommand`] into a control-plane [`Request::Query`] with the
/// contract method name and params. `plugin` is always `"database"`
/// (protocol-stable plugin id). The caller's per-invocation
/// `query_timeout_ms` is threaded through as `Request::Query::timeout_ms` so
/// the daemon honors it for this single dispatch (M3).
#[cfg(unix)]
fn map_db_command(db: crate::DbCommand, query_timeout_ms: u64) -> crate::control::Request {
    use crate::DbCommand;
    let (method, params) = match db {
        DbCommand::ListDatabases => ("listDatabases", serde_json::json!({})),
        DbCommand::Tables { database } => {
            ("listTables", serde_json::json!({"database": database}))
        }
        DbCommand::Inspect { database, table, limit, offset } => (
            "inspectTable",
            serde_json::json!({
                "database": database,
                "table": table,
                "limit": limit,
                "offset": offset,
            }),
        ),
    };
    crate::control::Request::Query {
        plugin: "database".to_string(),
        method: method.to_string(),
        params,
        timeout_ms: Some(query_timeout_ms),
    }
}

/// Owned handles from the control-plane setup so the accept loop can select on
/// shutdown and the cleanup runs when the loop exits.
#[cfg(unix)]
struct ControlHandle {
    shutdown_rx: tokio::sync::broadcast::Receiver<()>,
    socket_path: std::path::PathBuf,
    daemon_json: std::path::PathBuf,
}

#[cfg(unix)]
impl ControlHandle {
    fn cleanup(&self) {
        let _ = std::fs::remove_file(&self.socket_path);
        let _ = std::fs::remove_file(&self.daemon_json);
    }
}

/// Bind the control socket, publish daemon metadata, spawn the control
/// listener + signal handler. Returns the handle the caller uses to select on
/// shutdown.
#[cfg(unix)]
fn setup_control_plane(
    args: &Args,
    buffer: Arc<EventBuffer>,
    registry: Arc<ClientRegistry>,
    bound_port: u16,
) -> Result<ControlHandle> {
    let socket_path = crate::resolve_socket_path(args);

    // Liveness probe: if the socket file exists, try to talk to whatever owns
    // it. A responsive peer means a daemon is live -> exit code 4. A failed
    // connect means stale -> remove and rebind. (Unix-domain connect to a dead
    // listener fails immediately with ECONNREFUSED, so no timeout is needed.)
    if socket_path.exists() {
        match std::os::unix::net::UnixStream::connect(&socket_path) {
            Ok(_) => {
                eprintln!(
                    "devlens: daemon already running (socket at {})",
                    socket_path.display()
                );
                std::process::exit(exit::RUNNING as i32);
            }
            Err(_) => {
                // Stale socket from a crashed daemon — reclaim it.
                let _ = std::fs::remove_file(&socket_path);
            }
        }
    }

    if let Some(parent) = socket_path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| {
            anyhow::anyhow!(
                "failed to create control-socket dir {}: {}",
                parent.display(),
                e
            )
        })?;
    }

    let ctrl_listener = match tokio::net::UnixListener::bind(&socket_path) {
        Ok(l) => l,
        // bind() tiebreak loser: a peer won the race for the socket between
        // the liveness probe above and here. Honor the same contract as the
        // live-peer branch (exit RUNNING, code 4) — propagating as a generic
        // error would surface as exit 1 and mislead agents branching on 4.
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => {
            eprintln!(
                "devlens: daemon already running (socket at {})",
                socket_path.display()
            );
            std::process::exit(exit::RUNNING as i32);
        }
        Err(e) => {
            return Err(anyhow::anyhow!(
                "failed to bind control socket {}: {}",
                socket_path.display(),
                e
            ))
        }
    };
    eprintln!(
        "devlens: control socket listening at {}",
        socket_path.display()
    );

    // daemon.json (informational; read by `status` bookkeeping).
    let state_dir = crate::devlens_state_dir();
    let _ = std::fs::create_dir_all(&state_dir);
    let daemon_json = state_dir.join("daemon.json");
    let info = DaemonInfo {
        pid: std::process::id(),
        port: bound_port,
        socket_path: socket_path.display().to_string(),
        started_at: crate::protocol::lifecycle::now_millis(),
    };
    if let Ok(json) = serde_json::to_string_pretty(&serde_json::json!({
        "pid": info.pid,
        "port": info.port,
        "socketPath": info.socket_path,
        "startedAt": info.started_at,
    })) {
        let _ = std::fs::write(&daemon_json, json);
    }
    let info = Arc::new(info);

    let (shutdown_tx, _guard) = tokio::sync::broadcast::channel::<()>(8);
    let state = Arc::new(Mutex::new(ControlState::with_default_cursor(
        buffer.high_watermark(),
    )));

    // Control listener task.
    {
        let st = Arc::clone(&state);
        let inf = Arc::clone(&info);
        let stx = shutdown_tx.clone();
        let query_timeout = Duration::from_millis(args.query_timeout_ms);
        tokio::spawn(run_control_listener(
            ctrl_listener,
            buffer,
            registry,
            st,
            inf,
            stx,
            query_timeout,
        ));
    }

    // Signal handler task: SIGTERM / SIGINT -> broadcast shutdown + cleanup.
    {
        let stx = shutdown_tx.clone();
        let sock = socket_path.clone();
        let dj = daemon_json.clone();
        tokio::spawn(async move {
            wait_for_signal().await;
            tracing::info!("signal received, initiating shutdown");
            let _ = stx.send(());
            // Best-effort immediate cleanup; run_server also cleans on exit.
            let _ = std::fs::remove_file(&sock);
            let _ = std::fs::remove_file(&dj);
        });
    }

    Ok(ControlHandle {
        shutdown_rx: shutdown_tx.subscribe(),
        socket_path,
        daemon_json,
    })
}

/// Wait for SIGTERM or SIGINT (Unix).
#[cfg(unix)]
async fn wait_for_signal() {
    use tokio::signal::unix::{SignalKind, signal};

    let mut term = signal(SignalKind::terminate()).expect("install SIGTERM handler");
    let mut int = signal(SignalKind::interrupt()).expect("install SIGINT handler");
    tokio::select! {
        _ = term.recv() => {}
        _ = int.recv() => {}
    }
}

pub async fn handle_connection(
    stream: TcpStream,
    _args: Arc<Args>,
    displayer: Option<Arc<Displayer>>,
    filter: Arc<Filter>,
    buffer: Arc<EventBuffer>,
    save_file: Option<Arc<Mutex<std::fs::File>>>,
    registry: Arc<ClientRegistry>,
) -> Result<()> {
    // Capture peer address before the stream is moved into accept_async.
    let peer = stream
        .peer_addr()
        .map(|a| a.to_string())
        .unwrap_or_else(|_| "unknown".to_string());

    let ws_stream = accept_async(stream).await?;
    let (write, mut read) = ws_stream.split();
    let conn_id = next_conn_id();

    // Writer task: owns the SplitSink and drains a bounded mpsc channel,
    // serializing ALL outbound WS frames (Pong + Query). This avoids sharing
    // the sink between the reader loop (Pong) and the dispatcher (Query) and
    // keeps every write single-threaded relative to the connection.
    let (writer_tx, mut writer_rx) = mpsc::channel::<WsMessage>(16);
    tokio::spawn(async move {
        let mut write = write;
        while let Some(frame) = writer_rx.recv().await {
            if write.send(frame).await.is_err() {
                break;
            }
        }
        // Best-effort clean close once the channel drains/closes.
        let _ = write.close().await;
    });

    // Lifecycle: record the WS handshake. Always buffered (agent-visible).
    buffer.push(crate::protocol::lifecycle::connect(
        &peer,
        crate::protocol::lifecycle::now_millis(),
    ));

    // Track the most recent hello so the disconnect envelope can carry
    // appPackage + clientId (may remain None if no hello was sent).
    let mut hello: Option<HelloMessage> = None;

    while let Some(msg_result) = read.next().await {
        let text = match msg_result {
            Ok(WsMessage::Text(t)) => t,
            Ok(WsMessage::Binary(data)) => match String::from_utf8(data) {
                Ok(t) => t,
                Err(_) => { warn!("Received non-UTF8 binary message, ignoring"); continue; }
            },
            Ok(WsMessage::Ping(data)) => {
                // Forward to the writer task so Pong + Query writes serialize.
                let _ = writer_tx.send(WsMessage::Pong(data)).await;
                continue;
            }
            Ok(WsMessage::Close(_)) => { info!("Client sent close frame"); break; }
            Ok(_) => continue,
            Err(e) => { warn!("WebSocket error: {}", e); break; }
        };

        match serde_json::from_str::<Message>(&text) {
            Ok(Message::Hello(h)) => {
                // Buffer the hello envelope (pass-through, no field loss).
                if let Ok(raw) = serde_json::from_str::<serde_json::Value>(&text) {
                    buffer.push(crate::protocol::lifecycle::hello(
                        raw,
                        crate::protocol::lifecycle::now_millis(),
                    ));
                }
                if let Some(d) = displayer.as_ref() {
                    d.print_connected(&h);
                }
                // Register as the active query client (latest hello wins).
                // Clone the writer sender so the dispatcher can push Query
                // frames through the same serialized writer path.
                registry.register(conn_id, writer_tx.clone());
                // Version guard: warn (stderr only) at most once per connection
                // if the CLI is older than the SDK-advertised minCliVersion.
                // Policy is WARN & CONTINUE — never break the connection.
                // `hello.is_none()` ensures one warning per connection even on
                // a re-hello. Borrow of `h` happens before the move below.
                if hello.is_none() {
                    if let crate::version::VersionCheck::Stale { cli, min } =
                        crate::version::check_running_against(h.min_cli_version.as_deref())
                    {
                        eprintln!("{}", crate::version::format_warning(&cli, &min));
                    }
                }
                hello = Some(h);
            }
            Ok(Message::Transaction(tx)) => {
                // Always store in ring buffer (regardless of filter)
                if let Ok(raw) = serde_json::from_str::<serde_json::Value>(&text) {
                    buffer.push(raw.clone());
                    // Write to save file if configured
                    if let Some(ref file_mutex) = save_file {
                        if let Ok(mut file) = file_mutex.lock() {
                            let _ = writeln!(file, "{}", raw);
                        }
                    }
                }
                // Only display if passes filter
                if filter.matches(&tx) {
                    if let Some(d) = displayer.as_ref() {
                        d.print_transaction(&tx);
                    }
                }
            }
            Ok(Message::Event(event)) => {
                // Route queryResult responses to the dispatcher and SKIP
                // buffering: an agent fetches query results via the `db`
                // subcommand, and buffering them would pollute `devlens dump`.
                let is_query_result = event
                    .payload
                    .get("op")
                    .and_then(|v| v.as_str())
                    == Some("queryResult");
                if is_query_result {
                    if let Some(req_id) =
                        event.payload.get("requestId").and_then(|v| v.as_str())
                    {
                        registry.route_response(req_id, event.payload.clone());
                    }
                    continue;
                }

                // GENERALIZED buffering: every non-queryResult Event reaches the
                // ring buffer + save file regardless of plugin id. Network
                // remains the only plugin with typed extraction/filter/display.
                if let Ok(raw) = serde_json::from_str::<serde_json::Value>(&text) {
                    buffer.push(raw.clone());
                    if let Some(ref file_mutex) = save_file {
                        if let Ok(mut file) = file_mutex.lock() {
                            let _ = writeln!(file, "{}", raw);
                        }
                    }
                }
                if event.plugin == "network" {
                    match serde_json::from_value::<crate::protocol::HttpTransaction>(
                        event.payload.clone(),
                    ) {
                        Ok(tx) => {
                            if filter.matches(&tx) {
                                if let Some(d) = displayer.as_ref() {
                                    d.print_transaction(&tx);
                                }
                            }
                        }
                        Err(e) => {
                            warn!("Failed to parse network payload as HttpTransaction: {}", e);
                        }
                    }
                }
            }
            Ok(Message::Query(_)) => {
                // Inbound Query (CLI→SDK direction) from a client is unexpected —
                // the CLI is the query originator. Ignore rather than crash.
                warn!("Received unexpected inbound Query frame; ignoring");
            }
            Err(e) => {
                warn!("Failed to parse message: {} | raw: {}", e, &text[..text.len().min(200)]);
            }
        }
    }

    // Disconnect: drop the registry's sender (only if conn_id still matches so
    // a newer connection survives), then drop this loop's sender so the writer
    // task drains and exits.
    registry.unregister(conn_id);
    drop(writer_tx);

    // Lifecycle: record disconnect (always, even if no hello was sent).
    buffer.push(crate::protocol::lifecycle::disconnect(
        hello.as_ref().map(|h| h.app_package.as_str()),
        hello.as_ref().map(|h| h.client_id.as_str()),
        crate::protocol::lifecycle::now_millis(),
    ));
    if let (Some(d), Some(h)) = (displayer.as_ref(), hello.as_ref()) {
        d.print_disconnected(&h.app_package);
    }

    Ok(())
}
