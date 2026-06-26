//! Client side of the control plane: ephemeral subcommands (`start` / `dump` /
//! `end` / `status` / `shutdown`) that open a short-lived connection to a
//! running daemon, exchange one request/response, and exit with a code from
//! [`crate::control::exit`].
//!
//! stdout discipline: only `dump` and `status` write to stdout; everything else
//! is silent on success and prints diagnostics to stderr on failure.

use std::io::Write as _;
use std::path::Path;

use anyhow::Result;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;

use crate::control::{Request, Response, exit};

/// Connect to the daemon at `socket_path`, send `request`, read the typed
/// response header, copy any `dump` body lines to stdout (flushing per line),
/// and return the process exit code.
pub async fn run_command(socket_path: &Path, request: Request) -> Result<u8> {
    let stream = UnixStream::connect(socket_path)
        .await
        .map_err(|e| {
            anyhow::anyhow!(
                "cannot connect to daemon socket {} ({}); is `devlens serve` running?",
                socket_path.display(),
                e
            )
        })?;

    let (read_half, mut write_half) = stream.into_split();
    let req_line = serde_json::to_string(&request)?;
    write_half.write_all(req_line.as_bytes()).await?;
    write_half.write_all(b"\n").await?;
    write_half.flush().await?;
    drop(write_half);

    let mut reader = BufReader::new(read_half);
    let mut header_line = String::new();
    if reader.read_line(&mut header_line).await? == 0 {
        anyhow::bail!("daemon closed the connection without responding");
    }
    let header: Response = serde_json::from_str(header_line.trim())
        .map_err(|e| anyhow::anyhow!("invalid response header from daemon: {}", e))?;

    let stdout = std::io::stdout();
    let mut out = stdout.lock();

    match header {
        Response::Ok { op, count, dropped: _ } => {
            // Copy `count` NDJSON body lines to stdout. Only `dump` produces
            // body lines; flush per line so a piped consumer (jq/agent) sees
            // output incrementally rather than after block-buffer fills.
            let is_dump = op == "dump";
            for _ in 0..count {
                let mut line = String::new();
                if reader.read_line(&mut line).await? == 0 {
                    break;
                }
                if is_dump {
                    out.write_all(line.as_bytes())?;
                    out.flush()?;
                }
            }
            Ok(exit::OK)
        }
        Response::Status { pid, port, socket_path, started_at, sessions } => {
            writeln!(out, "devlens daemon")?;
            writeln!(out, "  pid:        {}", pid)?;
            writeln!(out, "  port:       {}", port)?;
            writeln!(out, "  socket:     {}", socket_path)?;
            writeln!(out, "  started_at: {} (ms, epoch)", started_at)?;
            if sessions.is_empty() {
                writeln!(out, "  sessions:   (none)")?;
            } else {
                let mut names: Vec<&String> = sessions.keys().collect();
                names.sort();
                for name in names {
                    writeln!(out, "  session {:<10} cursor={}", name, sessions[name])?;
                }
            }
            out.flush()?;
            Ok(exit::OK)
        }
        Response::Error { op, code, message } => {
            eprintln!("devlens: '{}' failed: {}", op, message);
            Ok(code)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_dump_with_none_session_serializes() {
        let r = Request::Dump { session: None };
        let s = serde_json::to_string(&r).unwrap();
        // client always sends a well-formed request line
        let back: Request = serde_json::from_str(&s).unwrap();
        assert_eq!(back, r);
    }
}
