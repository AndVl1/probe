mod display;
mod filter;
mod protocol;
mod server;

use std::sync::Arc;
use anyhow::Result;
use clap::Parser;

#[derive(Debug, Parser)]
#[command(
    name = "netsniff",
    version,
    about = "Android network traffic sniffer for debugging AI agent requests"
)]
pub struct Args {
    /// Port to listen on
    #[arg(short, long, default_value_t = 8484)]
    pub port: u16,

    /// Filter by URL pattern (regex, partial match on full URL)
    #[arg(short, long, value_name = "PATTERN")]
    pub filter: Option<String>,

    /// Show request and response headers
    #[arg(short, long)]
    pub verbose: bool,

    /// Show request and response bodies (truncated to 2000 chars)
    #[arg(short, long)]
    pub bodies: bool,

    /// Only show responses larger than N bytes
    #[arg(long, value_name = "BYTES")]
    pub min_size: Option<usize>,

    /// Disable colored output
    #[arg(long)]
    pub no_color: bool,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("warn")),
        )
        .with_target(false)
        .init();

    let args = Arc::new(Args::parse());
    server::run_server(args).await
}
