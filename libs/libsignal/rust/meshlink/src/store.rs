//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! The carry store: every bundle a node holds for itself or on behalf of
//! others, plus a memory of ids it has already seen so a bundle is never
//! processed or forwarded twice.
//!
//! Every structure here is bounded. The byte budget bounds the bundles, a
//! per-source quota stops one sender from filling it, and the seen-set has a
//! hard entry cap. Expiry uses an ordered index so a tick costs
//! `O(expired · log n)`, not a scan.

use std::collections::{BTreeSet, HashMap, HashSet};

use crate::bundle::{Bundle, BundleId, Fingerprint};

/// How long a "seen" id is remembered after the bundle itself is gone.
const SEEN_GRACE_SECS: u64 = 24 * 3600;
/// Hard cap on remembered ids (16 bytes + 8 bytes each, about 5 MiB).
pub const MAX_SEEN: usize = 200_000;

pub struct BundleStore {
    bundles: HashMap<BundleId, Bundle>,
    /// (expires_at, id) ordered so the soonest expiry is first.
    by_expiry: BTreeSet<(u64, BundleId)>,
    /// id -> time until which the id is remembered
    seen: HashMap<BundleId, u64>,
    seen_by_until: BTreeSet<(u64, BundleId)>,
    /// Bytes held per source fingerprint.
    per_src: HashMap<Fingerprint, usize>,
    total_bytes: usize,
    max_bytes: usize,
    /// Largest share of the budget one foreign source may hold.
    src_quota: usize,
    /// Our own fingerprint is exempt from the per-source quota.
    local: Option<Fingerprint>,
}

impl BundleStore {
    pub fn new(max_bytes: usize) -> Self {
        Self::with_quota(max_bytes, max_bytes / 8, None)
    }

    /// `src_quota` bounds what any one foreign source may occupy; `local`
    /// names the fingerprint whose bundles are exempt (the node's own).
    pub fn with_quota(max_bytes: usize, src_quota: usize, local: Option<Fingerprint>) -> Self {
        Self {
            bundles: HashMap::new(),
            by_expiry: BTreeSet::new(),
            seen: HashMap::new(),
            seen_by_until: BTreeSet::new(),
            per_src: HashMap::new(),
            total_bytes: 0,
            max_bytes,
            src_quota: src_quota.max(1),
            local,
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

    pub fn iter(&self) -> impl Iterator<Item = &Bundle> {
        self.bundles.values()
    }

    /// Remembers an id without storing a bundle (used for acknowledged and
    /// delivered bundles so re-offers are ignored).
    pub fn mark_seen(&mut self, id: BundleId, now: u64) {
        self.remember(id, now + SEEN_GRACE_SECS);
    }

    fn remember(&mut self, id: BundleId, until: u64) {
        if let Some(old) = self.seen.insert(id, until) {
            self.seen_by_until.remove(&(old, id));
        }
        self.seen_by_until.insert((until, id));
        while self.seen.len() > MAX_SEEN {
            let Some(&(u, victim)) = self.seen_by_until.iter().next() else {
                break;
            };
            self.seen_by_until.remove(&(u, victim));
            self.seen.remove(&victim);
        }
    }

    /// Whether `src` may add `size` more bytes.
    pub fn within_quota(&self, src: &Fingerprint, size: usize) -> bool {
        if self.local == Some(*src) {
            return true;
        }
        self.per_src.get(src).copied().unwrap_or(0) + size <= self.src_quota
    }

    /// Stores a bundle. Returns `false` if it was already seen, is expired,
    /// or its source is over quota. Evicts other bundles, heaviest source
    /// first and soonest expiry within it, when the byte budget is exceeded.
    pub fn insert(&mut self, bundle: Bundle, now: u64) -> bool {
        let id = bundle.id();
        if self.seen.contains_key(&id) || bundle.is_expired(now) {
            return false;
        }
        let size = bundle.wire_len();
        if size > self.max_bytes || !self.within_quota(&bundle.src, size) {
            return false;
        }
        while self.total_bytes + size > self.max_bytes && !self.bundles.is_empty() {
            let victim = self.eviction_victim(&bundle.src);
            self.remove(&victim);
        }
        self.remember(id, bundle.expires_at() + SEEN_GRACE_SECS);
        self.total_bytes += size;
        *self.per_src.entry(bundle.src).or_default() += size;
        self.by_expiry.insert((bundle.expires_at(), id));
        self.bundles.insert(id, bundle);
        true
    }

    /// The bundle to drop when over budget: the soonest-expiring bundle of
    /// the foreign source holding the most bytes; if that is the incoming
    /// bundle's own source, simply the soonest-expiring bundle overall.
    fn eviction_victim(&self, incoming_src: &Fingerprint) -> BundleId {
        let eligible = |src: &Fingerprint| Some(*src) != self.local && src != incoming_src;
        let heaviest = self
            .per_src
            .iter()
            .filter(|(src, _)| eligible(src))
            .map(|(_, bytes)| *bytes)
            .max();
        if let Some(max) = heaviest {
            // Soonest-expiring bundle among the sources tied for heaviest.
            if let Some((_, id)) = self.by_expiry.iter().find(|(_, id)| {
                self.bundles
                    .get(id)
                    .map(|b| eligible(&b.src) && self.per_src.get(&b.src) == Some(&max))
                    .unwrap_or(false)
            }) {
                return *id;
            }
        }
        self.by_expiry
            .iter()
            .next()
            .map(|(_, id)| *id)
            .expect("non-empty")
    }

    pub fn remove(&mut self, id: &BundleId) -> Option<Bundle> {
        let removed = self.bundles.remove(id);
        if let Some(b) = &removed {
            let size = b.wire_len();
            self.total_bytes -= size;
            self.by_expiry.remove(&(b.expires_at(), *id));
            if let Some(s) = self.per_src.get_mut(&b.src) {
                *s = s.saturating_sub(size);
                if *s == 0 {
                    self.per_src.remove(&b.src);
                }
            }
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
        let mut dropped = 0;
        while let Some(&(at, id)) = self.by_expiry.iter().next() {
            if at > now {
                break;
            }
            self.remove(&id);
            dropped += 1;
        }
        while let Some(&(until, id)) = self.seen_by_until.iter().next() {
            if until > now {
                break;
            }
            self.seen_by_until.remove(&(until, id));
            self.seen.remove(&id);
        }
        dropped
    }
}

#[cfg(test)]
mod test {
    use super::*;
    use crate::bundle::BundleKind;

    fn bundle_from(src: u8, ttl: u32, payload: usize) -> Bundle {
        Bundle::new(
            BundleKind::Message,
            [src; 16],
            [2; 16],
            ttl,
            4,
            [0; 16],
            vec![7; payload],
        )
        .unwrap()
    }

    #[test]
    fn dedup_expiry_and_eviction() {
        let mut s = BundleStore::with_quota(1000, 1000, None);
        let now = crate::now_secs();
        let b = bundle_from(1, 100, 10);
        assert!(s.insert(b.clone(), now));
        assert!(!s.insert(b.clone(), now), "duplicate rejected");
        s.remove(&b.id());
        assert!(!s.insert(b, now), "still remembered after removal");

        let old = bundle_from(1, 60, 100);
        let fresh = bundle_from(3, 500, 100);
        assert!(s.insert(old.clone(), now));
        assert!(s.insert(fresh.clone(), now));
        // Budget forces eviction; source 1 is the heaviest foreign source
        // other than the incoming one, so its bundle goes first.
        let big = bundle_from(4, 300, 700);
        assert!(s.insert(big.clone(), now));
        assert!(!s.contains(&old.id()));
        assert!(s.contains(&fresh.id()));
        assert!(s.contains(&big.id()));

        assert_eq!(s.expire(now + 301), 1);
        assert!(!s.contains(&big.id()));
        assert!(s.contains(&fresh.id()));
    }

    #[test]
    fn per_source_quota_and_local_exemption() {
        let mut s = BundleStore::with_quota(10_000, 500, Some([9; 16]));
        let now = crate::now_secs();
        assert!(s.insert(bundle_from(1, 100, 300), now));
        assert!(
            !s.insert(bundle_from(1, 100, 300), now),
            "source 1 over quota"
        );
        assert!(
            s.insert(bundle_from(2, 100, 300), now),
            "other sources unaffected"
        );
        for _ in 0..5 {
            assert!(s.insert(bundle_from(9, 100, 300), now), "local exempt");
        }
    }

    #[test]
    fn seen_set_is_capped() {
        let mut s = BundleStore::new(1 << 20);
        for i in 0..(MAX_SEEN + 100) {
            let mut id = [0u8; 16];
            id[..8].copy_from_slice(&(i as u64).to_be_bytes());
            s.mark_seen(id, i as u64);
        }
        assert_eq!(s.seen.len(), MAX_SEEN);
        assert_eq!(s.seen_by_until.len(), MAX_SEEN);
    }
}
