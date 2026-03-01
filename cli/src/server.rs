use std::collections::VecDeque;
use std::fs::OpenOptions;
use std::io::Write as IoWrite;
use std::sync::{Arc, Mutex};
use anyhow::Result;
use futures_util::{StreamExt, SinkExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_tungstenite::accept_async;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tracing::{error, info, warn};

use crate::display::Displayer;
use crate::filter::Filter;
use crate::protocol::Message;
use crate::Args;

/// Shared ring buffer of captured transactions (last N, thread-safe)
pub struct TransactionBuffer {
    inner: Mutex<VecDeque<serde_json::Value>>,
    capacity: usize,
}

impl TransactionBuffer {
    pub fn new(capacity: usize) -> Self {
        Self {
            inner: Mutex::new(VecDeque::with_capacity(capacity)),
            capacity,
        }
    }

    pub fn push(&self, value: serde_json::Value) {
        let mut buf = self.inner.lock().unwrap();
        if buf.len() >= self.capacity {
            buf.pop_front();
        }
        buf.push_back(value);
    }

    /// Returns last `n` transactions as JSONL string
    #[allow(dead_code)]
    pub fn dump_last(&self, n: usize) -> String {
        let buf = self.inner.lock().unwrap();
        let skip = buf.len().saturating_sub(n);
        buf.iter()
            .skip(skip)
            .map(|v| v.to_string())
            .collect::<Vec<_>>()
            .join("\n")
    }
}

pub async fn run_server(args: Arc<Args>) -> Result<()> {
    let addr = format!("0.0.0.0:{}", args.port);
    let listener = TcpListener::bind(&addr).await?;

    let displayer = Arc::new(Displayer::new(args.verbose, args.bodies, args.no_color));
    displayer.print_startup_banner(args.port);

    let filter = Arc::new(Filter::new(args.filter.as_deref(), args.min_size)?);

    // Ring buffer: last 1000 transactions
    let buffer = Arc::new(TransactionBuffer::new(1000));

    // Optional JSONL save file — opened once, shared across connections
    let save_file: Option<Arc<Mutex<std::fs::File>>> = args.save.as_ref().map(|path| {
        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)
            .expect("Failed to open save file");
        println!("Saving transactions to: {}", path.display());
        Arc::new(Mutex::new(file))
    });

    loop {
        match listener.accept().await {
            Ok((stream, peer_addr)) => {
                info!("New TCP connection from {}", peer_addr);
                let args_clone = Arc::clone(&args);
                let displayer_clone = Arc::clone(&displayer);
                let filter_clone = Arc::clone(&filter);
                let buffer_clone = Arc::clone(&buffer);
                let save_file_clone = save_file.clone();
                tokio::spawn(async move {
                    if let Err(e) = handle_connection(
                        stream,
                        args_clone,
                        displayer_clone,
                        filter_clone,
                        buffer_clone,
                        save_file_clone,
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
}

async fn handle_connection(
    stream: TcpStream,
    _args: Arc<Args>,
    displayer: Arc<Displayer>,
    filter: Arc<Filter>,
    buffer: Arc<TransactionBuffer>,
    save_file: Option<Arc<Mutex<std::fs::File>>>,
) -> Result<()> {
    let ws_stream = accept_async(stream).await?;
    let (mut write, mut read) = ws_stream.split();

    let mut app_package: Option<String> = None;

    while let Some(msg_result) = read.next().await {
        let text = match msg_result {
            Ok(WsMessage::Text(t)) => t,
            Ok(WsMessage::Binary(data)) => match String::from_utf8(data.into()) {
                Ok(t) => t,
                Err(_) => { warn!("Received non-UTF8 binary message, ignoring"); continue; }
            },
            Ok(WsMessage::Ping(data)) => {
                if let Err(e) = write.send(WsMessage::Pong(data)).await {
                    warn!("Failed to send pong: {}", e);
                    break;
                }
                continue;
            }
            Ok(WsMessage::Close(_)) => { info!("Client sent close frame"); break; }
            Ok(_) => continue,
            Err(e) => { warn!("WebSocket error: {}", e); break; }
        };

        match serde_json::from_str::<Message>(&text) {
            Ok(Message::Hello(hello)) => {
                app_package = Some(hello.app_package.clone());
                displayer.print_connected(&hello);
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
                    displayer.print_transaction(&tx);
                }
            }
            Err(e) => {
                warn!("Failed to parse message: {} | raw: {}", e, &text[..text.len().min(200)]);
            }
        }
    }

    if let Some(pkg) = app_package {
        displayer.print_disconnected(&pkg);
    }

    Ok(())
}
