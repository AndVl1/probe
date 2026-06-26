//! Bounded ring buffer of captured events with cursored, gap-aware dumps.
//!
//! Extracted from the original `server::TransactionBuffer`. Adds monotonic
//! sequence numbers so the agent control plane can dump "everything since cursor
//! N" and detect entries lost to eviction between dumps.
//!
//! Concurrency model mirrors the rest of the CLI: one `std::sync::Mutex` around
//! the inner deque, with poison recovery via [`std::sync::PoisonError::into_inner`]
//! (same precedent as the `Mutex<File>` save-file handle in `server.rs`).

use std::collections::VecDeque;
use std::sync::Mutex;

/// One entry in the ring buffer: a monotonic sequence number and its JSON value.
#[derive(Debug, Clone)]
pub struct BufferEntry {
    /// Monotonically increasing sequence number assigned at push time (1-based).
    pub seq: u64,
    /// The buffered JSON value (event envelope or CLI-internal lifecycle envelope).
    pub value: serde_json::Value,
}

/// A gap marker: entries were evicted before the client could dump them.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Gap {
    /// How many entries the client expected (seq > cursor) but were already
    /// evicted (seq < low watermark) at dump time.
    pub dropped: u64,
}

/// Result of [`EventBuffer::dump_since`].
///
/// `entries` are in ascending `seq` order. `gap` is `Some` when the client's
/// cursor fell behind the buffer's low watermark (entries were evicted before
/// they could be dumped). `high_watermark` is the highest `seq` observed at the
/// instant of the snapshot — callers advance their cursor to this value so the
/// next dump is race-free even if pushes land between dumps.
#[derive(Debug, Clone)]
pub struct DumpResult {
    pub entries: Vec<serde_json::Value>,
    pub gap: Option<Gap>,
    /// Highest assigned seq at snapshot time (0 when the buffer has never held
    /// any entry). Use this to advance the caller's cursor.
    pub high_watermark: u64,
}

struct BufferInner {
    entries: VecDeque<BufferEntry>,
    /// Seq that will be assigned to the next pushed entry (starts at 1, so an
    /// empty buffer reports `high_watermark == 0` and `low_watermark == 1`).
    next_seq: u64,
}

/// Thread-safe bounded ring buffer of captured events (CLI-internal).
pub struct EventBuffer {
    inner: Mutex<BufferInner>,
    capacity: usize,
}

impl EventBuffer {
    /// Create a buffer that retains at most `capacity` entries (FIFO eviction).
    pub fn new(capacity: usize) -> Self {
        Self {
            inner: Mutex::new(BufferInner {
                entries: VecDeque::with_capacity(capacity),
                next_seq: 1,
            }),
            capacity,
        }
    }

    /// Append a value, evicting the oldest entry if at capacity.
    ///
    /// Returns the assigned sequence number (which is also the new high
    /// watermark).
    pub fn push(&self, value: serde_json::Value) -> u64 {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if inner.entries.len() >= self.capacity {
            inner.entries.pop_front();
        }
        let seq = inner.next_seq;
        inner.next_seq += 1;
        inner.entries.push_back(BufferEntry { seq, value });
        seq
    }

    /// Highest assigned sequence number, or `0` if the buffer has never held an
    /// entry.
    pub fn high_watermark(&self) -> u64 {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        inner.next_seq.saturating_sub(1)
    }

    /// Sequence number of the oldest surviving entry. If the buffer is empty,
    /// returns `next_seq` (one past the high watermark) so that no `seq > cursor`
    /// query can match a non-existent entry.
    pub fn low_watermark(&self) -> u64 {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        inner.entries.front().map(|e| e.seq).unwrap_or(inner.next_seq)
    }

    /// Current number of entries retained.
    pub fn len(&self) -> usize {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        inner.entries.len()
    }

    /// `true` if no entries are currently retained.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Snapshot all entries with `seq > cursor`, reporting any eviction gap.
    ///
    /// Gap semantics: if `cursor + 1 < low_watermark`, the entries with seq in
    /// the half-open range `(cursor, low_watermark)` were evicted before they
    /// could be dumped; their count is returned as [`Gap::dropped`]. Surviving
    /// entries with `seq > cursor` are returned in ascending seq order.
    ///
    /// The snapshot is taken under a single lock, so `high_watermark` is
    /// consistent with `entries` (a concurrent push cannot create a gap or
    /// double-deliver).
    pub fn dump_since(&self, cursor: u64) -> DumpResult {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let high = inner.next_seq.saturating_sub(1);
        if inner.entries.is_empty() {
            return DumpResult {
                entries: Vec::new(),
                gap: None,
                high_watermark: high,
            };
        }
        let low = inner.entries.front().expect("non-empty").seq;
        // Entries the client expected (seq > cursor) but that were evicted
        // (seq < low): the integers strictly between cursor and low.
        let gap = if cursor + 1 < low {
            Some(Gap {
                dropped: low - cursor - 1,
            })
        } else {
            None
        };
        let entries = inner
            .entries
            .iter()
            .filter(|e| e.seq > cursor)
            .map(|e| e.value.clone())
            .collect();
        DumpResult {
            entries,
            gap,
            high_watermark: high,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn buf_values(d: &DumpResult) -> Vec<&serde_json::Value> {
        d.entries.iter().collect()
    }

    // ── new / empty ────────────────────────────────────────────────────────────

    #[test]
    fn new_starts_empty() {
        let buf = EventBuffer::new(10);
        assert_eq!(buf.len(), 0);
        assert!(buf.is_empty());
        assert_eq!(buf.high_watermark(), 0, "empty buffer high watermark is 0");
    }

    // ── push / seq assignment / watermark ──────────────────────────────────────

    #[test]
    fn push_assigns_monotonic_seq_and_grows_len() {
        let buf = EventBuffer::new(10);
        assert_eq!(buf.push(json!({"n": 1})), 1);
        assert_eq!(buf.push(json!({"n": 2})), 2);
        assert_eq!(buf.push(json!({"n": 3})), 3);
        assert_eq!(buf.len(), 3);
        assert_eq!(buf.high_watermark(), 3);
    }

    // ── capacity / eviction ────────────────────────────────────────────────────

    #[test]
    fn evicts_oldest_when_full() {
        let buf = EventBuffer::new(3);
        for i in 1..=5 {
            buf.push(json!({"n": i}));
        }
        assert_eq!(buf.len(), 3, "capacity respected");
        assert_eq!(buf.low_watermark(), 3, "oldest surviving seq is 3");

        let d = buf.dump_since(0);
        // items 1 and 2 evicted; 3, 4, 5 survive
        let nums: Vec<i64> = d.entries.iter().map(|v| v["n"].as_i64().unwrap()).collect();
        assert_eq!(nums, vec![3, 4, 5]);
    }

    #[test]
    fn fifo_eviction_oldest_removed_first() {
        let buf = EventBuffer::new(2);
        buf.push(json!({"seq": "first"}));
        buf.push(json!({"seq": "second"}));
        buf.push(json!({"seq": "third"}));

        let d = buf.dump_since(0);
        assert_eq!(d.entries.len(), 2);
        assert_eq!(d.entries[0]["seq"], "second");
        assert_eq!(d.entries[1]["seq"], "third");
    }

    // ── dump_since: cursor advance (no eviction) ───────────────────────────────

    #[test]
    fn dump_since_returns_all_when_no_eviction() {
        let buf = EventBuffer::new(10);
        for i in 1..=3 {
            buf.push(json!({"n": i}));
        }
        let d = buf.dump_since(0);
        assert_eq!(d.entries.len(), 3);
        assert_eq!(d.gap, None);
        assert_eq!(d.high_watermark, 3);
    }

    #[test]
    fn dump_since_advances_cursor_disjoint_batches() {
        let buf = EventBuffer::new(100);
        buf.push(json!({"n": 1}));
        buf.push(json!({"n": 2}));

        let first = buf.dump_since(0);
        assert_eq!(first.entries.len(), 2);
        assert_eq!(first.high_watermark, 2);

        // more pushes land between dumps
        buf.push(json!({"n": 3}));
        buf.push(json!({"n": 4}));

        let second = buf.dump_since(first.high_watermark);
        assert_eq!(second.entries.len(), 2, "disjoint from first dump");
        assert_eq!(second.entries[0]["n"], 3);
        assert_eq!(second.entries[1]["n"], 4);
        assert_eq!(second.gap, None);
        assert_eq!(second.high_watermark, 4);
    }

    #[test]
    fn dump_since_cursor_at_high_returns_empty_no_gap() {
        let buf = EventBuffer::new(10);
        buf.push(json!({"n": 1}));
        let d = buf.dump_since(1);
        assert!(d.entries.is_empty());
        assert_eq!(d.gap, None);
        assert_eq!(d.high_watermark, 1);
    }

    #[test]
    fn dump_since_cursor_beyond_high_returns_empty_no_gap() {
        let buf = EventBuffer::new(10);
        buf.push(json!({"n": 1}));
        let d = buf.dump_since(999);
        assert!(d.entries.is_empty());
        assert_eq!(d.gap, None);
    }

    // ── dump_since: gap on overflow ─────────────────────────────────────────────

    #[test]
    fn dump_since_reports_gap_when_cursor_behind_low_watermark() {
        let buf = EventBuffer::new(3);
        // push 5 → entries [3,4,5], low=3, high=5; seqs 1 and 2 evicted
        for i in 1..=5 {
            buf.push(json!({"n": i}));
        }
        // client cursor=0 → expects seq 1..5; seqs 1,2 evicted → dropped=2
        let d = buf.dump_since(0);
        assert_eq!(
            d.gap,
            Some(Gap { dropped: 2 }),
            "two entries (seq 1,2) were lost to eviction"
        );
        let nums: Vec<i64> = d.entries.iter().map(|v| v["n"].as_i64().unwrap()).collect();
        assert_eq!(nums, vec![3, 4, 5], "surviving entries returned in order");
        assert_eq!(d.high_watermark, 5);
    }

    #[test]
    fn dump_since_no_gap_when_cursor_adjacent_to_low_watermark() {
        let buf = EventBuffer::new(3);
        for i in 1..=5 {
            buf.push(json!({"n": i}));
        }
        // low=3; cursor=2 → expects seq 3,4,5, all present → no gap
        let d = buf.dump_since(2);
        assert_eq!(d.gap, None);
        assert_eq!(d.entries.len(), 3);
    }

    #[test]
    fn dump_since_on_empty_buffer_returns_empty_no_gap() {
        let buf = EventBuffer::new(10);
        let d = buf.dump_since(0);
        assert!(d.entries.is_empty());
        assert_eq!(d.gap, None);
        assert_eq!(d.high_watermark, 0);
    }

    // ── watermarks ──────────────────────────────────────────────────────────────

    #[test]
    fn low_watermark_tracks_eviction_front() {
        let buf = EventBuffer::new(3);
        assert_eq!(buf.low_watermark(), 1, "empty buffer low watermark is next_seq");
        buf.push(json!({"n": 1}));
        assert_eq!(buf.low_watermark(), 1);
        for i in 2..=5 {
            buf.push(json!({"n": i}));
        }
        assert_eq!(buf.low_watermark(), 3);
    }

    // ── concurrency ─────────────────────────────────────────────────────────────

    #[test]
    fn concurrent_pushes_are_thread_safe() {
        use std::sync::Arc;
        use std::thread;

        let buf = Arc::new(EventBuffer::new(1000));
        let mut handles = Vec::new();
        for _ in 0..4 {
            let b = Arc::clone(&buf);
            handles.push(thread::spawn(move || {
                for i in 0..100 {
                    b.push(json!({"worker": i}));
                }
            }));
        }
        for h in handles {
            h.join().unwrap();
        }
        assert_eq!(buf.len(), 400, "all pushes counted, no races");
        assert_eq!(buf.high_watermark(), 400);
    }

    // ── dump_since snapshot helper used by control plane ───────────────────────

    #[test]
    fn dump_since_result_entries_are_owned_clones() {
        let buf = EventBuffer::new(10);
        buf.push(json!({"x": 1}));
        let d = buf.dump_since(0);
        // entries are owned Values; dropping the buffer must not invalidate them
        drop(buf);
        assert_eq!(d.entries[0]["x"], 1);
        let _ = buf_values(&d); // ensure helper compiles/works
    }
}
