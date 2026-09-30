//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! What travels on a single link between two neighbours, and how bundles are
//! split for links whose frames are smaller than a bundle (LoRa: ~200 bytes).
//!
//! The exchange between two neighbours is the classic anti-entropy protocol:
//! each side announces the ids it holds (`Summary`), asks for the ones it
//! lacks (`Want`), and receives them (`Bundle`, possibly as `Fragment`s).

use std::collections::HashMap;

use crate::bundle::{Bundle, BundleId, Fingerprint};
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

const FRAME_VERSION: u8 = 1;
/// Fixed bytes of a `Fragment` frame before its data.
pub const FRAGMENT_OVERHEAD: usize = 1 + 1 + 16 + 1 + 1 + 2;
/// Smallest link MTU meshlink will operate over.
pub const MIN_MTU: usize = 64;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Frame {
    /// First frame on a link: who we are.
    Hello { fingerprint: Fingerprint },
    /// Ids of forwardable bundles we hold.
    Summary { ids: Vec<BundleId> },
    /// Ids we would like to receive.
    Want { ids: Vec<BundleId> },
    /// A whole bundle.
    Bundle(Bundle),
    /// Part `index` of `total` of the encoded bundle with the given id.
    Fragment {
        id: BundleId,
        index: u8,
        total: u8,
        data: Vec<u8>,
    },
}

impl Frame {
    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u8(FRAME_VERSION);
        match self {
            Frame::Hello { fingerprint } => {
                w.u8(1).fixed(fingerprint);
            }
            Frame::Summary { ids } => {
                w.u8(2).u16(ids.len() as u16);
                for id in ids {
                    w.fixed(id);
                }
            }
            Frame::Want { ids } => {
                w.u8(3).u16(ids.len() as u16);
                for id in ids {
                    w.fixed(id);
                }
            }
            Frame::Bundle(b) => {
                w.u8(4).fixed(&b.encode());
            }
            Frame::Fragment {
                id,
                index,
                total,
                data,
            } => {
                w.u8(5).fixed(id).u8(*index).u8(*total).bytes(data);
            }
        }
        w.finish()
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        let mut r = Reader::new(data);
        if r.u8()? != FRAME_VERSION {
            return Err(Error::Wire("unsupported frame version"));
        }
        let frame = match r.u8()? {
            1 => Frame::Hello {
                fingerprint: r.fixed::<16>()?,
            },
            2 | 3 => {
                let n = r.u16()? as usize;
                let mut ids = Vec::with_capacity(n.min(1024));
                for _ in 0..n {
                    ids.push(r.fixed::<16>()?);
                }
                if data[1] == 2 {
                    Frame::Summary { ids }
                } else {
                    Frame::Want { ids }
                }
            }
            4 => Frame::Bundle(Bundle::decode(r.rest())?),
            5 => Frame::Fragment {
                id: r.fixed::<16>()?,
                index: r.u8()?,
                total: r.u8()?,
                data: r.bytes()?.to_vec(),
            },
            _ => return Err(Error::Wire("unknown frame type")),
        };
        if !matches!(frame, Frame::Bundle(_)) {
            r.finish()?;
        }
        Ok(frame)
    }
}

/// Splits a bundle into `Fragment` frames that each fit in `mtu` bytes.
/// Returns a single `Bundle` frame when it already fits.
pub fn fragment_bundle(bundle: &Bundle, mtu: usize) -> Result<Vec<Frame>> {
    let whole = Frame::Bundle(bundle.clone());
    let encoded = whole.encode();
    if encoded.len() <= mtu {
        return Ok(vec![whole]);
    }
    let mtu = mtu.max(MIN_MTU);
    let chunk = mtu - FRAGMENT_OVERHEAD;
    let body = bundle.encode();
    let total = body.len().div_ceil(chunk);
    if total > u8::MAX as usize {
        return Err(Error::TooLarge(body.len(), chunk * u8::MAX as usize));
    }
    let id = bundle.id();
    Ok(body
        .chunks(chunk)
        .enumerate()
        .map(|(i, data)| Frame::Fragment {
            id,
            index: i as u8,
            total: total as u8,
            data: data.to_vec(),
        })
        .collect())
}

/// Splits an id list across several `Summary`/`Want` frames that fit in `mtu`.
pub fn chunk_ids(ids: &[BundleId], mtu: usize, want: bool) -> Vec<Frame> {
    let per_frame = ((mtu.max(MIN_MTU) - 4) / 16).max(1);
    ids.chunks(per_frame)
        .map(|c| {
            if want {
                Frame::Want { ids: c.to_vec() }
            } else {
                Frame::Summary { ids: c.to_vec() }
            }
        })
        .collect()
}

struct Partial {
    total: u8,
    parts: Vec<Option<Vec<u8>>>,
    bytes: usize,
    first_seen: u64,
}

/// Most bundles one link may have half-assembled at once.
pub const MAX_PARTIALS: usize = 64;
/// Largest single fragment body accepted (bigger than any sane MTU).
pub const MAX_FRAGMENT_DATA: usize = 2048;

/// Reassembles fragments per link. Incomplete bundles are dropped after
/// `timeout_secs` so a lost fragment cannot pin memory forever, the number
/// of partial bundles is capped, and no partial may grow past the largest
/// legal bundle. A link therefore cannot cost more than about
/// `MAX_PARTIALS * MAX_WIRE_LEN` bytes however it misbehaves.
pub struct Reassembler {
    partials: HashMap<BundleId, Partial>,
    timeout_secs: u64,
}

impl Reassembler {
    pub fn new(timeout_secs: u64) -> Self {
        Self {
            partials: HashMap::new(),
            timeout_secs,
        }
    }

    /// Feeds one fragment. Returns the decoded bundle once complete.
    pub fn push(
        &mut self,
        id: BundleId,
        index: u8,
        total: u8,
        data: Vec<u8>,
        now: u64,
    ) -> Result<Option<Bundle>> {
        if total == 0 || index >= total {
            return Err(Error::Wire("fragment index out of range"));
        }
        if data.is_empty() || data.len() > MAX_FRAGMENT_DATA {
            return Err(Error::Wire("fragment size out of range"));
        }
        if !self.partials.contains_key(&id) {
            if self.partials.len() >= MAX_PARTIALS {
                // Make room by dropping the oldest partial.
                if let Some(oldest) = self
                    .partials
                    .iter()
                    .min_by_key(|(_, p)| p.first_seen)
                    .map(|(k, _)| *k)
                {
                    self.partials.remove(&oldest);
                }
            }
            self.partials.insert(
                id,
                Partial {
                    total,
                    parts: vec![None; total as usize],
                    bytes: 0,
                    first_seen: now,
                },
            );
        }
        let partial = self.partials.get_mut(&id).expect("inserted above");
        if partial.total != total {
            return Err(Error::Wire("fragment total mismatch"));
        }
        let slot = &mut partial.parts[index as usize];
        if slot.is_some() {
            // Duplicate fragment: harmless, costs nothing.
            return Ok(None);
        }
        if partial.bytes + data.len() > crate::bundle::MAX_WIRE_LEN {
            self.partials.remove(&id);
            return Err(Error::Wire("fragments exceed the largest bundle"));
        }
        partial.bytes += data.len();
        *slot = Some(data);
        if partial.parts.iter().all(Option::is_some) {
            let partial = self.partials.remove(&id).expect("present");
            let body: Vec<u8> = partial.parts.into_iter().flatten().flatten().collect();
            let bundle = Bundle::decode(&body)?;
            if bundle.id() != id {
                return Err(Error::Wire("reassembled bundle id mismatch"));
            }
            return Ok(Some(bundle));
        }
        Ok(None)
    }

    pub fn expire(&mut self, now: u64) {
        let timeout = self.timeout_secs;
        self.partials
            .retain(|_, p| now.saturating_sub(p.first_seen) < timeout);
    }

    pub fn pending(&self) -> usize {
        self.partials.len()
    }
}

#[cfg(test)]
mod test {
    use super::*;
    use crate::bundle::BundleKind;

    #[test]
    fn frames_round_trip() {
        let b = Bundle::new(
            BundleKind::Message,
            [1; 16],
            [2; 16],
            60,
            3,
            [0; 16],
            vec![1, 2, 3],
        )
        .unwrap();
        for f in [
            Frame::Hello {
                fingerprint: [9; 16],
            },
            Frame::Summary {
                ids: vec![[1; 16], [2; 16]],
            },
            Frame::Want { ids: vec![] },
            Frame::Bundle(b.clone()),
            Frame::Fragment {
                id: b.id(),
                index: 1,
                total: 3,
                data: vec![4, 5],
            },
        ] {
            assert_eq!(Frame::decode(&f.encode()).unwrap(), f);
        }
    }

    #[test]
    fn fragment_and_reassemble_over_small_mtu() {
        let b = Bundle::new(
            BundleKind::Message,
            [1; 16],
            [2; 16],
            60,
            3,
            [0; 16],
            vec![0xAB; 1500],
        )
        .unwrap();
        let frames = fragment_bundle(&b, 200).unwrap();
        assert!(frames.len() > 1);
        for f in &frames {
            assert!(f.encode().len() <= 200, "frame exceeds mtu");
        }
        let mut r = Reassembler::new(60);
        let mut out = None;
        // Deliver out of order to prove ordering does not matter.
        for f in frames.iter().rev() {
            if let Frame::Fragment {
                id,
                index,
                total,
                data,
            } = f
            {
                out = r.push(*id, *index, *total, data.clone(), 0).unwrap();
            }
        }
        assert_eq!(out.unwrap(), b);
        assert_eq!(r.pending(), 0);
    }

    #[test]
    fn reassembler_times_out_partials() {
        let mut r = Reassembler::new(10);
        assert!(r.push([1; 16], 0, 2, vec![1], 0).unwrap().is_none());
        r.expire(5);
        assert_eq!(r.pending(), 1);
        r.expire(11);
        assert_eq!(r.pending(), 0);
        assert!(r.push([1; 16], 2, 2, vec![1], 0).is_err());
    }

    #[test]
    fn reassembler_is_bounded_against_floods() {
        let mut r = Reassembler::new(60);
        // A flood of distinct half-finished bundles never exceeds the cap.
        for i in 0..(MAX_PARTIALS as u64 * 4) {
            let mut id = [0u8; 16];
            id[..8].copy_from_slice(&i.to_be_bytes());
            assert!(r.push(id, 0, 255, vec![1; 100], i).unwrap().is_none());
            assert!(r.pending() <= MAX_PARTIALS);
        }
        // Oversized fragments and byte totals beyond a legal bundle are refused.
        assert!(
            r.push([9; 16], 0, 2, vec![0; MAX_FRAGMENT_DATA + 1], 0)
                .is_err()
        );
        assert!(r.push([9; 16], 0, 2, vec![], 0).is_err());
        let mut r = Reassembler::new(60);
        for i in 0..3u8 {
            let res = r.push([7; 16], i, 3, vec![0; MAX_FRAGMENT_DATA], 0);
            if i == 2 {
                assert!(res.is_err(), "3 x 2048 bytes exceeds MAX_WIRE_LEN");
            }
        }
        // Duplicate fragment is a no-op, not an error.
        let mut r = Reassembler::new(60);
        assert!(r.push([3; 16], 0, 2, vec![1], 0).unwrap().is_none());
        assert!(r.push([3; 16], 0, 2, vec![1], 0).unwrap().is_none());
        assert_eq!(r.pending(), 1);
    }

    #[test]
    fn id_lists_are_chunked_to_mtu() {
        let ids: Vec<BundleId> = (0..100u8).map(|i| [i; 16]).collect();
        let frames = chunk_ids(&ids, 200, false);
        assert!(frames.len() > 1);
        let mut n = 0;
        for f in frames {
            assert!(f.encode().len() <= 200);
            if let Frame::Summary { ids } = f {
                n += ids.len();
            }
        }
        assert_eq!(n, 100);
    }
}
