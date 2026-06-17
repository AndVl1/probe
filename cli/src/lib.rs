pub mod display;
pub mod filter;
pub mod protocol;
pub mod server;

use clap::Parser;

#[derive(Debug, Parser)]
#[command(
    name = "probe",
    version,
    about = "Plugin-based mobile app inspector. Captures network, database, preferences and more."
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

    /// Save all captured transactions to a JSONL file (one JSON per line)
    #[arg(long, value_name = "FILE")]
    pub save: Option<std::path::PathBuf>,

    /// Disable colored output
    #[arg(long)]
    pub no_color: bool,
}
