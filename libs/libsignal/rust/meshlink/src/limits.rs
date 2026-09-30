//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Token buckets with bounded memory. A node cannot control who talks to it,
//! so every per-peer table here has a hard cap and forgets the quietest
//! entries first.

use std::collections::HashMap;
use std::hash::Hash;

/// A single token bucket measured in whole tokens per second.
#[derive(Clone, Debug)]
pub struct Bucket {
    tokens: f64,
    capacity: f64,
    per_sec: f64,
    last_ms: u64,
}

impl Bucket {
    pub fn new(per_sec: f64, capacity: f64) -> Self {
        Self {
            tokens: capacity,
            capacity,
            per_sec,
            last_ms: 0,
        }
    }

    fn refill(&mut self, now_ms: u64) {
        if now_ms > self.last_ms {
            let dt = (now_ms - self.last_ms) as f64 / 1000.0;
            self.tokens = (self.tokens + dt * self.per_sec).min(self.capacity);
            self.last_ms = now_ms;
        }
    }

    /// Takes `n` tokens if available.
    pub fn take(&mut self, n: f64, now_ms: u64) -> bool {
        self.refill(now_ms);
        if self.tokens >= n {
            self.tokens -= n;
            true
        } else {
            false
        }
    }

    pub fn available(&mut self, now_ms: u64) -> f64 {
        self.refill(now_ms);
        self.tokens
    }
}

/// Buckets keyed by peer with a hard cap on the number of peers remembered.
pub struct KeyedLimiter<K> {
    buckets: HashMap<K, (Bucket, u64)>,
    per_sec: f64,
    capacity: f64,
    max_keys: usize,
}

impl<K: Eq + Hash + Clone> KeyedLimiter<K> {
    pub fn new(per_sec: f64, capacity: f64, max_keys: usize) -> Self {
        Self {
            buckets: HashMap::new(),
            per_sec,
            capacity,
            max_keys: max_keys.max(1),
        }
    }

    /// Takes `n` tokens from `key`'s bucket, creating it if needed. When the
    /// table is full the least recently used quarter is dropped.
    pub fn take(&mut self, key: &K, n: f64, now_ms: u64) -> bool {
        if !self.buckets.contains_key(key) && self.buckets.len() >= self.max_keys {
            let mut by_age: Vec<(u64, K)> = self
                .buckets
                .iter()
                .map(|(k, (_, t))| (*t, k.clone()))
                .collect();
            by_age.sort_unstable_by_key(|(t, _)| *t);
            for (_, k) in by_age.iter().take(self.max_keys.div_ceil(4)) {
                self.buckets.remove(k);
            }
        }
        let entry = self
            .buckets
            .entry(key.clone())
            .or_insert_with(|| (Bucket::new(self.per_sec, self.capacity), now_ms));
        entry.1 = now_ms;
        entry.0.take(n, now_ms)
    }

    pub fn len(&self) -> usize {
        self.buckets.len()
    }

    pub fn is_empty(&self) -> bool {
        self.buckets.is_empty()
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn bucket_refills_over_time() {
        let mut b = Bucket::new(10.0, 5.0);
        for _ in 0..5 {
            assert!(b.take(1.0, 0));
        }
        assert!(!b.take(1.0, 0));
        assert!(b.take(1.0, 100), "refilled 1 token after 100ms");
        assert!(!b.take(1.0, 100));
        assert!(b.take(5.0, 5000), "capped at capacity");
    }

    #[test]
    fn keyed_limiter_is_bounded() {
        let mut l = KeyedLimiter::new(1.0, 1.0, 8);
        for i in 0..100u32 {
            assert!(l.take(&i, 1.0, i as u64));
            assert!(l.len() <= 8);
        }
        assert!(!l.take(&99, 1.0, 99), "same key second time is refused");
    }
}
