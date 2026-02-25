use std::sync::Arc;
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

pub async fn run_server(args: Arc<Args>) -> Result<()> {
    let addr = format!("0.0.0.0:{}", args.port);
    let listener = TcpListener::bind(&addr).await?;

    let displayer = Arc::new(Displayer::new(args.verbose, args.bodies, args.no_color));
    displayer.print_startup_banner(args.port);

    let filter = Arc::new(Filter::new(args.filter.as_deref(), args.min_size)?);

    loop {
        match listener.accept().await {
            Ok((stream, peer_addr)) => {
                info!("New TCP connection from {}", peer_addr);
                let args_clone = Arc::clone(&args);
                let displayer_clone = Arc::clone(&displayer);
                let filter_clone = Arc::clone(&filter);
                tokio::spawn(async move {
                    if let Err(e) = handle_connection(stream, args_clone, displayer_clone, filter_clone).await {
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
) -> Result<()> {
    let ws_stream = accept_async(stream).await?;
    let (mut write, mut read) = ws_stream.split();

    let mut app_package: Option<String> = None;

    while let Some(msg_result) = read.next().await {
        match msg_result {
            Ok(WsMessage::Text(text)) => {
                match serde_json::from_str::<Message>(&text) {
                    Ok(Message::Hello(hello)) => {
                        app_package = Some(hello.app_package.clone());
                        displayer.print_connected(&hello);
                    }
                    Ok(Message::Transaction(tx)) => {
                        if filter.matches(&tx) {
                            displayer.print_transaction(&tx);
                        }
                    }
                    Err(e) => {
                        warn!("Failed to parse message: {} | raw: {}", e, &text[..text.len().min(200)]);
                    }
                }
            }
            Ok(WsMessage::Binary(data)) => {
                // Try to parse as UTF-8 text
                match std::str::from_utf8(&data) {
                    Ok(text) => {
                        match serde_json::from_str::<Message>(text) {
                            Ok(Message::Hello(hello)) => {
                                app_package = Some(hello.app_package.clone());
                                displayer.print_connected(&hello);
                            }
                            Ok(Message::Transaction(tx)) => {
                                if filter.matches(&tx) {
                                    displayer.print_transaction(&tx);
                                }
                            }
                            Err(e) => {
                                warn!("Failed to parse binary message: {}", e);
                            }
                        }
                    }
                    Err(_) => {
                        warn!("Received non-UTF8 binary message, ignoring");
                    }
                }
            }
            Ok(WsMessage::Ping(data)) => {
                // Respond to ping with pong
                if let Err(e) = write.send(WsMessage::Pong(data)).await {
                    warn!("Failed to send pong: {}", e);
                    break;
                }
            }
            Ok(WsMessage::Close(_)) => {
                info!("Client sent close frame");
                break;
            }
            Ok(_) => {
                // Ignore other message types (Pong, Frame)
            }
            Err(e) => {
                warn!("WebSocket error: {}", e);
                break;
            }
        }
    }

    if let Some(pkg) = app_package {
        displayer.print_disconnected(&pkg);
    }

    Ok(())
}
