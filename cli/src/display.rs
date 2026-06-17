use colored::Colorize;
use std::time::{SystemTime, UNIX_EPOCH};
use crate::protocol::HelloMessage;
use crate::protocol::HttpTransaction;

pub struct Displayer {
    pub verbose: bool,
    pub bodies: bool,
    #[allow(dead_code)]
    pub no_color: bool,
}

impl Displayer {
    pub fn new(verbose: bool, bodies: bool, no_color: bool) -> Self {
        if no_color {
            colored::control::set_override(false);
        }
        Self { verbose, bodies, no_color }
    }

    pub fn print_startup_banner(&self, port: u16) {
        let version = env!("CARGO_PKG_VERSION");
        let title = format!("  Probe v{}  •  Listening on :{}       ", version, port);
        let waiting = "  Waiting for mobile app connection...        ";

        // Determine width based on longest line (char count, not bytes, for Unicode safety)
        let width = title.chars().count().max(waiting.chars().count());
        let bar = "═".repeat(width);

        println!("╔{}╗", bar);
        println!("║{}║", pad_right(&title, width));
        println!("║{}║", pad_right(waiting, width));
        println!("╚{}╝", bar);
    }

    pub fn print_connected(&self, hello: &HelloMessage) {
        let ts = current_timestamp();
        println!("{} {}", ts_dim(&ts), format_connected_body(hello));
    }

    pub fn print_disconnected(&self, app_package: &str) {
        let ts = current_timestamp();
        println!("{} Disconnected: {}", ts_dim(&ts), app_package);
    }

    pub fn print_transaction(&self, tx: &HttpTransaction) {
        let ts = current_timestamp();

        let method_str = format!("{:<6}", tx.method);
        let method_colored = method_str.cyan().to_string();

        if let Some(ref error) = tx.error {
            let bullet = "✗".red().bold().to_string();
            let url_str = tx.url.as_str();
            let error_str = format!("ERROR: {}", error).red().bold().to_string();
            println!(
                "{} {} {}  {}  {}",
                ts_dim(&ts),
                bullet,
                method_colored,
                url_str,
                error_str
            );
        } else {
            let bullet = "●".green().to_string();
            let code = tx.response_code.unwrap_or(0);
            let code_str = format_status_code(code);
            let duration_str = format_duration(tx.duration_ms).white().to_string();
            let size_str = format_size(tx.response_size_bytes).white().to_string();
            println!(
                "{} {} {}  {}  {}  {}  {}",
                ts_dim(&ts),
                bullet,
                method_colored,
                tx.url,
                code_str,
                duration_str,
                size_str
            );
        }

        if self.verbose {
            if !tx.request_headers.is_empty() {
                println!("  Request Headers:");
                let mut keys: Vec<_> = tx.request_headers.keys().collect();
                keys.sort();
                for k in keys {
                    println!("    {}: {}", k, tx.request_headers[k]);
                }
            }
            if !tx.response_headers.is_empty() {
                println!("  Response Headers:");
                let mut keys: Vec<_> = tx.response_headers.keys().collect();
                keys.sort();
                for k in keys {
                    println!("    {}: {}", k, tx.response_headers[k]);
                }
            }
        }

        if self.bodies {
            match &tx.request_body {
                None => println!("  Request Body: (none)"),
                Some(body) if body.is_empty() => println!("  Request Body: (none)"),
                Some(body) => {
                    let truncated = truncate(body, 2000);
                    println!("  Request Body:");
                    println!("    {}", truncated);
                }
            }
            match &tx.response_body {
                None => println!("  Response Body: (none)"),
                Some(body) if body.is_empty() => println!("  Response Body: (none)"),
                Some(body) => {
                    let truncated = truncate(body, 2000);
                    if body.len() > 2000 {
                        println!("  Response Body (truncated to 2000 chars):");
                    } else {
                        println!("  Response Body:");
                    }
                    println!("    {}", truncated);
                }
            }
        }
    }
}

fn current_timestamp() -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = now.as_secs();
    let hours = (secs % 86400) / 3600;
    let minutes = (secs % 3600) / 60;
    let seconds = secs % 60;
    format!("{:02}:{:02}:{:02}", hours, minutes, seconds)
}

fn ts_dim(ts: &str) -> String {
    format!("[{}]", ts).dimmed().to_string()
}

fn format_connected_body(hello: &HelloMessage) -> String {
    let platform = hello.platform.as_deref().unwrap_or("unknown");
    let os_ver = hello.android_version.as_deref()
        .or(hello.os_version.as_deref())
        .unwrap_or("?");
    format!(
        "Connected: {} ({}, {} {})",
        hello.app_package, hello.device_model, platform, os_ver
    )
}

fn format_status_code(code: i32) -> String {
    let s = code.to_string();
    match code {
        200..=299 => s.green().to_string(),
        300..=399 => s.yellow().to_string(),
        400..=499 => s.red().to_string(),
        500..=599 => s.bright_red().to_string(),
        _ => s,
    }
}

fn format_duration(ms: Option<i64>) -> String {
    match ms {
        None => "-".to_string(),
        Some(ms) if ms < 1000 => format!("{}ms", ms),
        Some(ms) => {
            let s = ms as f64 / 1000.0;
            format!("{:.1}s", s)
        }
    }
}

fn format_size(bytes: Option<i64>) -> String {
    match bytes {
        None => "-".to_string(),
        Some(b) if b < 1024 => format!("{}B", b),
        Some(b) if b < 1024 * 1024 => {
            let kb = b as f64 / 1024.0;
            format!("{:.1}KB", kb)
        }
        Some(b) => {
            let mb = b as f64 / (1024.0 * 1024.0);
            format!("{:.1}MB", mb)
        }
    }
}

fn truncate(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        s.to_string()
    } else {
        let mut result: String = s.chars().take(max_chars).collect();
        result.push_str("...");
        result
    }
}

fn pad_right(s: &str, width: usize) -> String {
    let char_count = s.chars().count();
    if char_count >= width {
        s.to_string()
    } else {
        format!("{}{}", s, " ".repeat(width - char_count))
    }
}
