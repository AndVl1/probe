use regex::Regex;
use crate::protocol::HttpTransaction;

pub struct Filter {
    pub pattern: Option<Regex>,
    pub min_size: Option<usize>,
}

impl Filter {
    pub fn new(pattern: Option<&str>, min_size: Option<usize>) -> anyhow::Result<Self> {
        let regex = match pattern {
            Some(p) => Some(Regex::new(p)?),
            None => None,
        };
        Ok(Self {
            pattern: regex,
            min_size,
        })
    }

    pub fn matches(&self, tx: &HttpTransaction) -> bool {
        if let Some(ref regex) = self.pattern {
            if !regex.is_match(&tx.url) {
                return false;
            }
        }

        if let Some(min_size) = self.min_size {
            let size = tx.response_size_bytes.unwrap_or(0) as usize;
            if size < min_size {
                return false;
            }
        }

        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn make_tx(url: &str, response_size_bytes: Option<i64>) -> HttpTransaction {
        HttpTransaction {
            id: "test-id".to_string(),
            timestamp: 1700000000,
            method: "GET".to_string(),
            url: url.to_string(),
            request_headers: HashMap::new(),
            request_body: None,
            request_size_bytes: 0,
            response_code: Some(200),
            response_message: None,
            response_headers: HashMap::new(),
            response_body: None,
            response_size_bytes,
            duration_ms: Some(100),
            app_id: None,
            error: None,
            mocked: None,
        }
    }

    // ── No constraints → matches everything ──────────────────────────────────

    #[test]
    fn filter_none_none_matches_any_url() {
        let filter = Filter::new(None, None).unwrap();
        assert!(filter.matches(&make_tx("https://anything.example.com/api", None)));
    }

    #[test]
    fn filter_none_none_matches_with_no_size() {
        let filter = Filter::new(None, None).unwrap();
        assert!(filter.matches(&make_tx("https://example.com", None)));
    }

    // ── URL regex filter ──────────────────────────────────────────────────────

    #[test]
    fn filter_url_pattern_matches_containing_url() {
        let filter = Filter::new(Some(r"api\.example"), None).unwrap();
        assert!(filter.matches(&make_tx("https://api.example.com/users", None)));
    }

    #[test]
    fn filter_url_pattern_rejects_non_matching_url() {
        let filter = Filter::new(Some(r"api\.example"), None).unwrap();
        assert!(!filter.matches(&make_tx("https://other.com/users", None)));
    }

    // ── min_size filter ───────────────────────────────────────────────────────

    #[test]
    fn filter_min_size_matches_response_at_or_above_threshold() {
        let filter = Filter::new(None, Some(100)).unwrap();
        assert!(filter.matches(&make_tx("https://example.com", Some(100))));
        assert!(filter.matches(&make_tx("https://example.com", Some(200))));
    }

    #[test]
    fn filter_min_size_rejects_response_below_threshold() {
        let filter = Filter::new(None, Some(100)).unwrap();
        assert!(!filter.matches(&make_tx("https://example.com", Some(99))));
        assert!(!filter.matches(&make_tx("https://example.com", Some(0))));
    }

    #[test]
    fn filter_min_size_with_none_response_size_rejects() {
        // None is treated as 0, which is below any positive min_size
        let filter = Filter::new(None, Some(100)).unwrap();
        assert!(!filter.matches(&make_tx("https://example.com", None)));
    }

    // ── Invalid regex → Err ───────────────────────────────────────────────────

    #[test]
    fn filter_invalid_regex_returns_error() {
        let result = Filter::new(Some("(invalid"), None);
        assert!(result.is_err(), "invalid regex should return Err");
    }

    // ── Combined regex + min_size ─────────────────────────────────────────────

    #[test]
    fn filter_combined_both_must_match() {
        let filter = Filter::new(Some(r"api\.example"), Some(500)).unwrap();

        // matches: URL matches and size >= 500
        assert!(filter.matches(&make_tx("https://api.example.com/data", Some(1024))));

        // rejects: URL matches but size too small
        assert!(!filter.matches(&make_tx("https://api.example.com/data", Some(100))));

        // rejects: size ok but URL doesn't match
        assert!(!filter.matches(&make_tx("https://other.com/data", Some(1024))));

        // rejects: neither matches
        assert!(!filter.matches(&make_tx("https://other.com/data", Some(10))));
    }
}
