//! CLI version-guard logic.
//!
//! When an SDK connects it may advertise a `minCliVersion` in its hello
//! message. The daemon compares its own [`CLI_VERSION`] against that minimum
//! and, if it is older, prints a human-readable warning to stderr (stdout is
//! NDJSON-only on the daemon path — never `println!`). Policy is WARN &
//! CONTINUE: the WebSocket connection is never broken on a version mismatch.
//!
//! Parsing is intentionally hand-rolled (zero new dependencies — no semver
//! crate) and fail-safe: any unparseable input collapses to
//! [`VersionCheck::Ok`], so a malformed `minCliVersion` from the SDK never
//! panics the daemon or degrades the connection.

/// The CLI's own version, captured at compile time from `CARGO_PKG_VERSION`.
pub const CLI_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Parsed semantic version triple (major.minor.patch).
///
/// Hand-rolled: no pre-release or build metadata — the CLI uses clean release
/// tagging. Manual [`Ord`] compares lexicographically (major, then minor, then
/// patch), matching the natural `u32` ordering for each component so there is
/// no string-comparison trap (e.g. `0.10.0 > 0.9.0`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Version {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
}

impl Version {
    /// Parse a `"major.minor.patch"` string. Returns `None` on any malformed
    /// input — missing component, non-numeric component, or extra segments.
    pub fn parse(s: &str) -> Option<Self> {
        let mut parts = s.split('.');
        let major = parts.next()?.parse().ok()?;
        let minor = parts.next()?.parse().ok()?;
        let patch = parts.next()?.parse().ok()?;
        if parts.next().is_some() {
            return None;
        }
        Some(Self {
            major,
            minor,
            patch,
        })
    }
}

impl Ord for Version {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.major
            .cmp(&other.major)
            .then(self.minor.cmp(&other.minor))
            .then(self.patch.cmp(&other.patch))
    }
}

impl PartialOrd for Version {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

/// Outcome of comparing the running CLI version against an SDK's
/// `minCliVersion`.
///
/// Carries the compared strings back out so the I/O site (stderr formatting)
/// stays decoupled from the pure comparison — the guard block in
/// `server.rs` has zero conditional logic, and the value remains testable
/// without capturing stderr.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VersionCheck {
    /// CLI meets the minimum, or the minimum was absent / unparseable
    /// (fail-safe).
    Ok,
    /// CLI is strictly older than the advertised minimum.
    Stale {
        /// The running CLI version (as a string, for the warning text).
        cli: String,
        /// The SDK-advertised minimum version (as a string, for the warning).
        min: String,
    },
}

/// Pure comparison. Any parse failure (either side) collapses to
/// [`VersionCheck::Ok`] — intentional fail-safe behavior: an unparseable
/// `minCliVersion` must never panic the daemon or degrade the connection.
pub fn check_min(cli: &str, min: &str) -> VersionCheck {
    match (Version::parse(cli), Version::parse(min)) {
        (Some(c), Some(m)) if c < m => VersionCheck::Stale {
            cli: cli.to_owned(),
            min: min.to_owned(),
        },
        _ => VersionCheck::Ok,
    }
}

/// Convenience wrapper binding the compile-time [`CLI_VERSION`] const — the
/// entry point used by the hello handler. `min = None` means the SDK did not
/// advertise a minimum; the result is [`VersionCheck::Ok`].
pub fn check_running_against(min: Option<&str>) -> VersionCheck {
    match min {
        Some(m) => check_min(CLI_VERSION, m),
        None => VersionCheck::Ok,
    }
}

/// Format the staleness warning. Pure (no I/O) so the caller chooses
/// `eprintln!` vs `tracing::warn!` — the daemon uses `eprintln!` to preserve
/// this exact text without a timestamp/level prefix and to remain visible
/// regardless of `RUST_LOG`.
///
/// Note: the separator is an em-dash (U+2014 `—`), not an ASCII hyphen.
pub fn format_warning(cli: &str, min: &str) -> String {
    format!(
        "devlens: WARNING CLI version {cli} is older than SDK minCliVersion {min} — some events may be unsupported. Upgrade: brew upgrade devlens"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Version::parse ────────────────────────────────────────────────────────

    #[test]
    fn version_parse_happy() {
        let v = Version::parse("0.3.0").expect("0.3.0 parses");
        assert_eq!(
            v,
            Version {
                major: 0,
                minor: 3,
                patch: 0
            }
        );

        let v = Version::parse("1.2.3").expect("1.2.3 parses");
        assert_eq!(
            v,
            Version {
                major: 1,
                minor: 2,
                patch: 3
            }
        );
    }

    #[test]
    fn version_parse_bad_returns_none() {
        // Missing components.
        assert!(Version::parse("").is_none());
        assert!(Version::parse("0").is_none());
        assert!(Version::parse("0.3").is_none());
        // Non-numeric.
        assert!(Version::parse("a.b.c").is_none());
        assert!(Version::parse("0.x.0").is_none());
        assert!(Version::parse("0.3.-1").is_none());
        // Extra segments / pre-release suffix (unsupported shape).
        assert!(Version::parse("0.3.0.1").is_none());
        assert!(Version::parse("0.3.0-rc1").is_none());
        // Surrounding whitespace — strict parse.
        assert!(Version::parse(" 0.3.0").is_none());
        assert!(Version::parse("0.3.0 ").is_none());
    }

    // ── Version ordering (lexicographic major.minor.patch) ────────────────────

    #[test]
    fn version_ordering_is_lexicographic() {
        assert!(Version::parse("0.2.9").unwrap() < Version::parse("0.3.0").unwrap());
        assert!(Version::parse("0.3.0").unwrap() < Version::parse("0.3.1").unwrap());
        assert!(Version::parse("0.3.0").unwrap() < Version::parse("1.0.0").unwrap());
        assert_eq!(
            Version::parse("0.3.0").unwrap(),
            Version::parse("0.3.0").unwrap()
        );
        // No string-comparison trap: components are u32, so 0.10.0 > 0.9.0.
        assert!(Version::parse("0.10.0").unwrap() > Version::parse("0.9.0").unwrap());
    }

    // ── check_min matrix ──────────────────────────────────────────────────────

    #[test]
    fn check_min_older_cli_is_stale() {
        assert_eq!(
            check_min("0.2.0", "0.3.0"),
            VersionCheck::Stale {
                cli: "0.2.0".to_string(),
                min: "0.3.0".to_string(),
            }
        );
    }

    #[test]
    fn check_min_equal_is_ok() {
        assert_eq!(check_min("0.3.0", "0.3.0"), VersionCheck::Ok);
    }

    #[test]
    fn check_min_newer_cli_is_ok() {
        assert_eq!(check_min("0.4.0", "0.3.0"), VersionCheck::Ok);
        assert_eq!(check_min("1.0.0", "0.3.0"), VersionCheck::Ok);
    }

    #[test]
    fn check_min_unparseable_min_is_ok_fail_safe() {
        assert_eq!(check_min("0.3.0", "garbage"), VersionCheck::Ok);
        assert_eq!(check_min("0.3.0", "0.3"), VersionCheck::Ok);
        assert_eq!(check_min("0.3.0", ""), VersionCheck::Ok);
    }

    #[test]
    fn check_min_unparseable_cli_is_ok_fail_safe() {
        assert_eq!(check_min("garbage", "0.3.0"), VersionCheck::Ok);
        assert_eq!(check_min("", "0.3.0"), VersionCheck::Ok);
    }

    #[test]
    fn check_min_both_unparseable_is_ok() {
        assert_eq!(check_min("nope", "also-nope"), VersionCheck::Ok);
    }

    // ── check_running_against ─────────────────────────────────────────────────

    #[test]
    fn check_running_against_none_is_ok() {
        assert_eq!(check_running_against(None), VersionCheck::Ok);
    }

    #[test]
    fn check_running_against_some_stale_min_is_stale() {
        // "99.0.0" is strictly greater than any released CLI version, so this
        // exercises the `Some(_)` branch of the wrapper (which binds the
        // compile-time [`CLI_VERSION`]) regardless of the exact current version.
        // The `cli` field is pinned to [`CLI_VERSION`] so the assertion survives
        // version bumps without edits.
        assert_eq!(
            check_running_against(Some("99.0.0")),
            VersionCheck::Stale {
                cli: CLI_VERSION.to_string(),
                min: "99.0.0".to_string(),
            },
        );
    }

    // ── format_warning ────────────────────────────────────────────────────────

    #[test]
    fn format_warning_is_byte_exact() {
        // Pin the exact wording byte-for-byte (the em-dash U+2014, single
        // spaces, and the `Upgrade: brew upgrade devlens` hint) so an accidental
        // rewording or separator change (e.g. em-dash → ASCII hyphen) surfaces
        // here rather than silently drifting in daemon stderr output.
        assert_eq!(
            format_warning("0.2.0", "0.3.0"),
            "devlens: WARNING CLI version 0.2.0 is older than SDK minCliVersion 0.3.0 — some events may be unsupported. Upgrade: brew upgrade devlens",
        );
    }
}
