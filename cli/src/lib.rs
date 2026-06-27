pub mod buffer;
pub mod control;
#[cfg(unix)]
pub mod control_client;
pub mod display;
pub mod filter;
pub mod protocol;
pub mod server;

use std::path::PathBuf;

use clap::{Parser, Subcommand};

/// Default daemon cap for the in-memory event ring buffer.
pub const DEFAULT_BUFFER_CAPACITY: usize = 10_000;

/// Top-level CLI. `command: None` means the default `serve` daemon (so bare
/// `devlens` is a working agent daemon). All flags are `global` so they may
/// appear before or after the subcommand.
#[derive(Debug, Clone, Default, Parser)]
#[command(
    name = "devlens",
    version,
    about = "DevLens — plugin-based mobile app inspector. By default runs as a \
             no-stream agent daemon (buffers events; use `devlens dump` to fetch)."
)]
pub struct Args {
    /// Subcommand. Omit (= `serve`) to run the daemon.
    #[command(subcommand)]
    pub command: Option<Command>,

    /// Port the WebSocket server listens on.
    #[arg(short, long, default_value_t = 8484, global = true)]
    pub port: u16,

    /// Pretty (legacy colored human) stdout output instead of the silent agent
    /// daemon. Also enabled by `DEVLENS_PRETTY=1`.
    #[arg(long, global = true)]
    pub pretty: bool,

    /// Path to the daemon control socket. Env: `DEVLENS_SOCK`.
    #[arg(long, env = "DEVLENS_SOCK", value_name = "PATH", global = true)]
    pub control_socket: Option<PathBuf>,

    /// Filter by URL pattern (regex, partial match on full URL) — display only.
    #[arg(short, long, value_name = "PATTERN", global = true)]
    pub filter: Option<String>,

    /// Show request and response headers (pretty mode only).
    #[arg(short, long, global = true)]
    pub verbose: bool,

    /// Show request and response bodies, truncated to 2000 chars (pretty only).
    #[arg(short, long, global = true)]
    pub bodies: bool,

    /// Only show responses larger than N bytes (pretty mode only).
    #[arg(long, value_name = "BYTES", global = true)]
    pub min_size: Option<usize>,

    /// Save all captured events to a JSONL file (one JSON per line).
    #[arg(long, value_name = "FILE", global = true)]
    pub save: Option<PathBuf>,

    /// Disable colored output (pretty mode only).
    #[arg(long, global = true)]
    pub no_color: bool,
}

/// Agent daemon subcommands. `Serve` is the default (bare `devlens`).
#[derive(Debug, Clone, Subcommand)]
pub enum Command {
    /// Run as the no-stream agent daemon (default). Binds the WebSocket server
    /// and a Unix control socket. stdout stays silent unless `--pretty`.
    Serve,

    /// Position a dump-session cursor at the current high watermark so later
    /// `dump` calls return only events captured after this point.
    Start {
        /// Named session (defaults to the implicit `default` session).
        #[arg(long, value_name = "NAME")]
        session: Option<String>,
        /// Reposition the cursor even if the session already exists.
        #[arg(long)]
        reset: bool,
    },

    /// Dump buffered events since the session cursor as NDJSON on stdout, then
    /// advance the cursor. Works without a prior `start` (default session).
    Dump {
        /// Named session (defaults to the implicit `default` session).
        #[arg(long, value_name = "NAME")]
        session: Option<String>,
    },

    /// End (remove) a named dump session.
    End {
        /// Named session (defaults to the implicit `default` session).
        #[arg(long, value_name = "NAME")]
        session: Option<String>,
    },

    /// Show running daemon status (pid, port, socket, sessions).
    Status,

    /// Shut down a running daemon.
    Shutdown,
}

/// Resolve the daemon control-socket path.
///
/// Precedence: explicit `--control-socket` / `DEVLENS_SOCK`, then
/// `$XDG_RUNTIME_DIR/devlens.sock`, then `~/.devlens/control.sock`.
pub fn resolve_socket_path(args: &Args) -> PathBuf {
    if let Some(p) = args.control_socket.as_ref() {
        return p.clone();
    }
    #[cfg(unix)]
    {
        if let Ok(xdg) = std::env::var("XDG_RUNTIME_DIR") {
            if !xdg.is_empty() {
                return PathBuf::from(xdg).join("devlens.sock");
            }
        }
    }
    devlens_state_dir().join("control.sock")
}

/// Directory for daemon bookkeeping (`daemon.json`, default socket location).
///
/// `~/.devlens` on Unix; `.devlens` in the current directory as a fallback
/// (e.g. when `HOME` is unset or on non-Unix hosts).
pub fn devlens_state_dir() -> PathBuf {
    #[cfg(unix)]
    {
        if let Ok(home) = std::env::var("HOME") {
            if !home.is_empty() {
                return PathBuf::from(home).join(".devlens");
            }
        }
    }
    PathBuf::from(".devlens")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn args_default_is_serve_with_default_port() {
        let a = Args::default();
        assert!(a.command.is_none(), "default command is None (= serve)");
        assert_eq!(a.port, 0, "Default derive yields 0; clap default_value_t=8484 applies at parse time");
        assert!(!a.pretty);
        assert!(a.control_socket.is_none());
    }

    #[test]
    fn resolve_socket_path_explicit_wins() {
        let args = Args {
            control_socket: Some(PathBuf::from("/tmp/custom.sock")),
            ..Default::default()
        };
        assert_eq!(resolve_socket_path(&args), PathBuf::from("/tmp/custom.sock"));
    }

    #[test]
    fn resolve_socket_path_falls_back_under_home() {
        // Without XDG_RUNTIME_DIR and without --control-socket, the path lives
        // under the devlens state dir.
        let args = Args::default();
        let p = resolve_socket_path(&args);
        assert!(
            p.ends_with("control.sock") || p.ends_with("devlens.sock"),
            "unexpected fallback path: {:?}",
            p
        );
    }
}
