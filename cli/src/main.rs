use std::sync::Arc;

use anyhow::Result;
use clap::Parser;

use devlens::{Args, Command};

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("warn")),
        )
        .with_target(false)
        .init();

    let args = Args::parse();

    match args.command.clone().unwrap_or(Command::Serve) {
        Command::Serve => {
            // Default: run the no-stream agent daemon (WS server + control socket).
            devlens::server::run_server(Arc::new(args)).await
        }
        #[cfg(unix)]
        command => {
            // Ephemeral control subcommand: talk to a running daemon and exit.
            let code = devlens::server::run_control_subcommand(&args, command).await?;
            if code != 0 {
                std::process::exit(code as i32);
            }
            Ok(())
        }
        #[cfg(not(unix))]
        _ => {
            eprintln!("control subcommands require a Unix domain socket (unsupported on this platform)");
            std::process::exit(1);
        }
    }
}
