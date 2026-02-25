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
