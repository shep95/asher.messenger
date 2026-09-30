//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! The carry store: every bundle a node holds for itself or on behalf of
//! others, plus a memory of ids it has already seen so a bundle is never
//! processed or forwarded twice.

use std::collections::{HashMap, HashSet};

use crate::bundle::{Bundle, BundleId};

/// How long a "seen" id is remembered after the bundle itself is gone.
const SEEN_GRACE_SECS: u64 = 24 * 3600;

pub struct BundleStore {
    bundles: HashMap<BundleId, Bundle>,
    /// id -> time until which the id is remembered
    seen: HashMap<BundleId, u64>,
    total_bytes: usize,
    max_bytes: usize,
}

impl BundleStore {
    pub fn new(max_bytes: usize) -> Self {
        Self {
            bundles: HashMap::new(),
            seen: HashMap::new(),
            total_bytes: 0,
            max_bytes,
        }
    }

    pub fn len(&self) -> usize {
        self.bundles.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bundles.is_empty()
    }

    pub fn bytes(&self) -> usize {
        self.total_bytes
    }

    pub fn has_seen(&self, id: &BundleId) -> bool {
        self.seen.contains_key(id)
    }

    pub fn contains(&self, id: &BundleId) -> bool {
        self.bundles.contains_key(id)
    }

    pub fn get(&self, id: &BundleId) -> Option<&Bundle> {
        self.bundles.get(id)
    }

    /// Remembers an id without storing a bundle (used for acknowledged and
    /// delivered bundles so re-offers are ignored).
    pub fn mark_seen(&mut self, id: BundleId, now: u64) {
        self.seen.insert(id, now + SEEN_GRACE_SECS);
    }

    /// Stores a bundle. Returns `false` if it was already seen or expired.
    /// Evicts the bundles closest to expiry when the byte budget is exceeded.
    pub fn insert(&mut self, bundle: Bundle, now: u64) -> bool {
        let id = bundle.id();
        if self.seen.contains_key(&id) || bundle.is_expired(now) {
            return false;
        }
        let size = bundle.encode().len();
        while self.total_bytes + size > self.max_bytes && !self.bundles.is_empty() {
            let victim = self
                .bundles
                .iter()
                .min_by_key(|(_, b)| b.expires_at())
                .map(|(k, _)| *k)
                .expect("non-empty");
            self.remove(&victim);
        }
        self.seen.insert(id, bundle.expires_at() + SEEN_GRACE_SECS);
        self.total_bytes += size;
        self.bundles.insert(id, bundle);
        true
    }

    pub fn remove(&mut self, id: &BundleId) -> Option<Bundle> {
        let removed = self.bundles.remove(id);
        if let Some(b) = &removed {
            self.total_bytes -= b.encode().len();
        }
        removed
    }

    /// Ids of bundles that may still be offered to neighbours.
    pub fn forwardable_ids(&self, now: u64) -> Vec<BundleId> {
        self.bundles
            .iter()
            .filter(|(_, b)| b.is_forwardable(now))
            .map(|(k, _)| *k)
            .collect()
    }

    pub fn ids(&self) -> HashSet<BundleId> {
        self.bundles.keys().copied().collect()
    }

    /// Drops expired bundles and forgotten ids.
    pub fn expire(&mut self, now: u64) -> usize {
        let expired: Vec<BundleId> = self
            .bundles
            .iter()
            .filter(|(_, b)| b.is_expired(now))
            .map(|(k, _)| *k)
            .collect();
        for id in &expired {
            self.remove(id);
        }
        self.seen.retain(|_, until| *until > now);
        expired.len()
    }
}

#[cfg(test)]
mod test {
    use super::*;
    use crate::bundle::BundleKind;

    fn bundle(ttl: u32, payload: usize) -> Bundle {
        Bundle::new(
            BundleKind::Message,
            [1; 16],
            [2; 16],
            ttl,
            4,
            vec![7; payload],
        )
        .unwrap()
    }

    #[test]
    fn dedup_expiry_and_eviction() {
        let mut s = BundleStore::new(600);
        let now = crate::now_secs();
        let b = bundle(100, 10);
        assert!(s.insert(b.clone(), now));
        assert!(!s.insert(b.clone(), now), "duplicate rejected");
        s.remove(&b.id());
        assert!(!s.insert(b, now), "still remembered after removal");

        let old = bundle(5, 100);
        let fresh = bundle(500, 100);
        assert!(s.insert(old.clone(), now));
        assert!(s.insert(fresh.clone(), now));
        // Budget forces eviction of the bundle closest to expiry.
        let big = bundle(300, 350);
        assert!(s.insert(big.clone(), now));
        assert!(!s.contains(&old.id()));
        assert!(s.contains(&fresh.id()));
        assert!(s.contains(&big.id()));

        assert_eq!(s.expire(now + 301), 1);
        assert!(!s.contains(&big.id()));
        assert!(s.contains(&fresh.id()));
    }
}
