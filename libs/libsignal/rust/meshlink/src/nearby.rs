//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Who has been seen on the mesh lately: every contact card that passed by
//! (beacons, card shares) and every neighbour that said hello, whether or not
//! it is a saved contact. Replaces server-side contact discovery with what
//! the radio can actually tell us. Bounded to [`MAX_NEARBY`] entries and
//! [`NEARBY_TTL_SECS`] of silence.

use std::collections::HashMap;

use crate::bundle::Fingerprint;
use crate::identity::MAX_NAME_BYTES;
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

/// Most peers remembered.
pub const MAX_NEARBY: usize = 256;
/// A peer not seen for this long is forgotten.
pub const NEARBY_TTL_SECS: u64 = 24 * 3600;

/// A peer seen recently, as reported to the application.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Nearby {
    pub fingerprint: Fingerprint,
    /// Display name from the last card seen; empty if only a hello was heard.
    pub name: String,
    pub last_seen: u64,
    /// Whether the peer is a neighbour on a link right now.
    pub direct: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Entry {
    name: String,
    last_seen: u64,
}

#[derive(Default)]
pub struct NearbyTable {
    entries: HashMap<Fingerprint, Entry>,
}

impl NearbyTable {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Records a sighting. `name` `None` keeps whatever name was known.
    pub fn seen(&mut self, fp: Fingerprint, name: Option<&str>, now: u64) {
        match self.entries.get_mut(&fp) {
            Some(e) => {
                e.last_seen = e.last_seen.max(now);
                if let Some(n) = name {
                    e.name = n.chars().take(MAX_NAME_BYTES).collect();
                }
            }
            None => {
                if self.entries.len() >= MAX_NEARBY
                    && let Some(victim) = self
                        .entries
                        .iter()
                        .min_by_key(|(_, e)| e.last_seen)
                        .map(|(k, _)| *k)
                {
                    self.entries.remove(&victim);
                }
                self.entries.insert(
                    fp,
                    Entry {
                        name: name.unwrap_or("").chars().take(MAX_NAME_BYTES).collect(),
                        last_seen: now,
                    },
                );
            }
        }
    }

    pub fn forget(&mut self, fp: &Fingerprint) -> bool {
        self.entries.remove(fp).is_some()
    }

    /// Drops entries older than [`NEARBY_TTL_SECS`]. Returns how many.
    pub fn expire(&mut self, now: u64) -> usize {
        let before = self.entries.len();
        self.entries
            .retain(|_, e| now.saturating_sub(e.last_seen) < NEARBY_TTL_SECS);
        before - self.entries.len()
    }

    /// Everyone seen within the last day, most recent first. `direct` says
    /// whether a fingerprint is a current neighbour.
    pub fn list(&self, now: u64, direct: impl Fn(&Fingerprint) -> bool) -> Vec<Nearby> {
        let mut out: Vec<Nearby> = self
            .entries
            .iter()
            .filter(|(_, e)| now.saturating_sub(e.last_seen) < NEARBY_TTL_SECS)
            .map(|(fp, e)| Nearby {
                fingerprint: *fp,
                name: e.name.clone(),
                last_seen: e.last_seen,
                direct: direct(fp),
            })
            .collect();
        out.sort_by(|a, b| {
            b.last_seen
                .cmp(&a.last_seen)
                .then(a.fingerprint.cmp(&b.fingerprint))
        });
        out
    }

    /// `u16 count` then per entry `[fingerprint 16][name u16-len][last_seen u64]`.
    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u16(u16::try_from(self.entries.len().min(MAX_NEARBY)).expect("MAX_NEARBY fits"));
        for (fp, e) in self.entries.iter().take(MAX_NEARBY) {
            w.fixed(fp).bytes(e.name.as_bytes()).u64(e.last_seen);
        }
        w.finish()
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        let mut r = Reader::new(data);
        let n = r.u16()? as usize;
        if n > MAX_NEARBY {
            return Err(Error::Wire("too many nearby entries"));
        }
        let mut table = Self::new();
        for _ in 0..n {
            let fp = r.fixed::<16>()?;
            let name = r.bytes()?;
            if name.len() > MAX_NAME_BYTES {
                return Err(Error::Wire("nearby name too long"));
            }
            let name = std::str::from_utf8(name)
                .map_err(|_| Error::Wire("nearby name is not UTF-8"))?
                .to_owned();
            let last_seen = r.u64()?;
            table.entries.insert(fp, Entry { name, last_seen });
        }
        r.finish()?;
        Ok(table)
    }

    /// Takes the newer sighting of every peer in `other`.
    pub fn merge(&mut self, other: &NearbyTable) {
        let mut sightings: Vec<(&Fingerprint, &Entry)> = other.entries.iter().collect();
        sightings.sort_by_key(|(_, e)| e.last_seen);
        for (fp, e) in sightings {
            let newer = self
                .entries
                .get(fp)
                .map(|mine| mine.last_seen < e.last_seen)
                .unwrap_or(true);
            if newer {
                self.seen(*fp, Some(&e.name), e.last_seen);
            }
        }
    }
}

/// Bridge encoding of a nearby list: `u16 count` then per entry
/// `[fingerprint 16][name u16-len][last_seen u64][direct u8]`.
pub fn encode_nearby(list: &[Nearby]) -> Vec<u8> {
    let mut w = Writer::new();
    let count = list.len().min(usize::from(u16::MAX));
    w.u16(u16::try_from(count).expect("clamped"));
    for n in list.iter().take(count) {
        w.fixed(&n.fingerprint)
            .bytes(n.name.as_bytes())
            .u64(n.last_seen)
            .u8(n.direct as u8);
    }
    w.finish()
}

/// Decodes a bridge-encoded nearby list (used by tests and tools).
pub fn decode_nearby(data: &[u8]) -> Result<Vec<Nearby>> {
    let mut r = Reader::new(data);
    let n = r.u16()? as usize;
    let mut out = Vec::with_capacity(n.min(MAX_NEARBY));
    for _ in 0..n {
        let fingerprint = r.fixed::<16>()?;
        let name = r.bytes()?;
        if name.len() > MAX_NAME_BYTES {
            return Err(Error::Wire("nearby name too long"));
        }
        let name = std::str::from_utf8(name)
            .map_err(|_| Error::Wire("nearby name is not UTF-8"))?
            .to_owned();
        let last_seen = r.u64()?;
        let direct = r.u8()? != 0;
        out.push(Nearby {
            fingerprint,
            name,
            last_seen,
            direct,
        });
    }
    r.finish()?;
    Ok(out)
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn bounded_ordered_and_round_trips() {
        let mut t = NearbyTable::new();
        for i in 0..(MAX_NEARBY + 20) {
            let mut fp = [0u8; 16];
            fp[..8].copy_from_slice(&(i as u64).to_be_bytes());
            t.seen(fp, Some(&format!("peer {i}")), 1000 + i as u64);
        }
        assert_eq!(t.len(), MAX_NEARBY);
        let list = t.list(2000, |_| false);
        assert_eq!(list[0].name, format!("peer {}", MAX_NEARBY + 19));
        assert!(list.windows(2).all(|w| w[0].last_seen >= w[1].last_seen));

        let back = NearbyTable::decode(&t.encode()).unwrap();
        assert_eq!(back.list(2000, |_| false), list);

        // Hello without a name keeps the name from the card.
        let fp = list[0].fingerprint;
        t.seen(fp, None, 3000);
        assert_eq!(t.list(3000, |f| *f == fp)[0].name, list[0].name);
        assert!(t.list(3000, |f| *f == fp)[0].direct);

        // Expiry.
        assert!(t.expire(3000 + NEARBY_TTL_SECS) >= MAX_NEARBY - 1);
        assert!(t.is_empty());

        let enc = encode_nearby(&list);
        assert_eq!(decode_nearby(&enc).unwrap(), list);
        assert!(decode_nearby(&enc[..enc.len() - 1]).is_err());
    }
}
