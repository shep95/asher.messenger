//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! The unit of store-carry-forward delivery.
//!
//! A bundle is opaque to relays apart from its header: who it is from (needed
//! by the recipient to pick the Signal session), who it is for, when it
//! expires and how many hops it may still travel. The payload is Signal
//! Protocol ciphertext (messages), a signed contact card (beacons) or a bundle
//! id (acknowledgements). Relays never need to decrypt anything.

use sha2::{Digest, Sha256};

use crate::wire::{Reader, Writer};
use crate::{Error, Result};

/// 16-byte truncated SHA-256 of a Signal identity public key: a node's address.
pub type Fingerprint = [u8; 16];
/// Content hash identifying a bundle everywhere in the network.
pub type BundleId = [u8; 16];

/// Destination of beacons and acknowledgements.
pub const BROADCAST: Fingerprint = [0xff; 16];

/// Wire format version.
pub const VERSION: u8 = 1;
/// Largest payload a single bundle may carry. Big enough for a contact card
/// (about 1.9 KiB with a Kyber-1024 prekey) or a long text; attachments are
/// out of scope for a low-bandwidth transport.
pub const MAX_PAYLOAD: usize = 4096;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum BundleKind {
    /// Signal Protocol ciphertext for `dst`. Payload: `[message_type u8][ciphertext]`.
    Message = 1,
    /// A signed [`crate::ContactCard`] from `src`. Payload: the card bytes.
    Beacon = 2,
    /// `dst` received bundle `payload[..16]`; relays may drop it.
    Ack = 3,
}

impl TryFrom<u8> for BundleKind {
    type Error = Error;
    fn try_from(v: u8) -> Result<Self> {
        Ok(match v {
            1 => Self::Message,
            2 => Self::Beacon,
            3 => Self::Ack,
            _ => return Err(Error::Wire("unknown bundle kind")),
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Bundle {
    pub kind: BundleKind,
    pub src: Fingerprint,
    pub dst: Fingerprint,
    /// Creation time, seconds since the Unix epoch (sender's clock).
    pub created_at: u64,
    /// Lifetime from `created_at`.
    pub ttl_secs: u32,
    /// Hops travelled so far. Incremented by each relay that forwards it.
    pub hops: u8,
    /// Maximum hops; a bundle with `hops >= max_hops` is delivered but not forwarded.
    pub max_hops: u8,
    /// Random per-bundle value making the id unique even for identical payloads.
    pub nonce: u64,
    pub payload: Vec<u8>,
}

impl Bundle {
    pub fn new(
        kind: BundleKind,
        src: Fingerprint,
        dst: Fingerprint,
        ttl_secs: u32,
        max_hops: u8,
        payload: Vec<u8>,
    ) -> Result<Self> {
        if payload.len() > MAX_PAYLOAD {
            return Err(Error::TooLarge(payload.len(), MAX_PAYLOAD));
        }
        Ok(Self {
            kind,
            src,
            dst,
            created_at: crate::now_secs(),
            ttl_secs,
            hops: 0,
            max_hops,
            nonce: rand::random(),
            payload,
        })
    }

    /// The bundle id: a hash of everything except the mutable hop counter, so
    /// every relay computes the same id for the same bundle.
    pub fn id(&self) -> BundleId {
        let mut h = Sha256::new();
        h.update(b"meshlink-bundle-v1");
        h.update([self.kind as u8]);
        h.update(self.src);
        h.update(self.dst);
        h.update(self.created_at.to_be_bytes());
        h.update(self.ttl_secs.to_be_bytes());
        h.update([self.max_hops]);
        h.update(self.nonce.to_be_bytes());
        h.update(&self.payload);
        let out = h.finalize();
        out[..16].try_into().expect("16 bytes")
    }

    pub fn expires_at(&self) -> u64 {
        self.created_at.saturating_add(self.ttl_secs as u64)
    }

    pub fn is_expired(&self, now: u64) -> bool {
        now >= self.expires_at()
    }

    pub fn is_broadcast(&self) -> bool {
        self.dst == BROADCAST
    }

    /// Whether a relay may pass this bundle on to another node.
    pub fn is_forwardable(&self, now: u64) -> bool {
        !self.is_expired(now) && self.hops < self.max_hops
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u8(VERSION)
            .u8(self.kind as u8)
            .fixed(&self.src)
            .fixed(&self.dst)
            .u64(self.created_at)
            .u32(self.ttl_secs)
            .u8(self.hops)
            .u8(self.max_hops)
            .u64(self.nonce)
            .bytes(&self.payload);
        w.finish()
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        let mut r = Reader::new(data);
        if r.u8()? != VERSION {
            return Err(Error::Wire("unsupported bundle version"));
        }
        let kind = BundleKind::try_from(r.u8()?)?;
        let src = r.fixed::<16>()?;
        let dst = r.fixed::<16>()?;
        let created_at = r.u64()?;
        let ttl_secs = r.u32()?;
        let hops = r.u8()?;
        let max_hops = r.u8()?;
        let nonce = r.u64()?;
        let payload = r.bytes()?.to_vec();
        r.finish()?;
        if payload.len() > MAX_PAYLOAD {
            return Err(Error::TooLarge(payload.len(), MAX_PAYLOAD));
        }
        Ok(Self {
            kind,
            src,
            dst,
            created_at,
            ttl_secs,
            hops,
            max_hops,
            nonce,
            payload,
        })
    }
}

/// Fingerprint of a serialized Signal identity public key.
pub fn fingerprint_of(identity_public_key: &[u8]) -> Fingerprint {
    let mut h = Sha256::new();
    h.update(b"meshlink-fingerprint-v1");
    h.update(identity_public_key);
    let out = h.finalize();
    out[..16].try_into().expect("16 bytes")
}

pub fn fingerprint_hex(fp: &Fingerprint) -> String {
    hex::encode(fp)
}

pub fn parse_fingerprint(s: &str) -> Result<Fingerprint> {
    let bytes = hex::decode(s.trim()).map_err(|_| Error::Wire("fingerprint is not hex"))?;
    bytes
        .try_into()
        .map_err(|_| Error::Wire("fingerprint must be 16 bytes"))
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn encode_decode_and_stable_id() {
        let b = Bundle::new(
            BundleKind::Message,
            [1; 16],
            [2; 16],
            3600,
            7,
            vec![9, 8, 7],
        )
        .unwrap();
        let bytes = b.encode();
        let back = Bundle::decode(&bytes).unwrap();
        assert_eq!(b, back);
        // A relay increments hops; the id must not change.
        let mut relayed = back.clone();
        relayed.hops += 1;
        assert_eq!(b.id(), relayed.id());
        // Different nonce => different id even with identical content.
        let b2 = Bundle::new(
            BundleKind::Message,
            [1; 16],
            [2; 16],
            3600,
            7,
            vec![9, 8, 7],
        )
        .unwrap();
        assert_ne!(b.id(), b2.id());
    }

    #[test]
    fn rejects_oversized_and_garbage() {
        assert!(
            Bundle::new(
                BundleKind::Message,
                [0; 16],
                [0; 16],
                1,
                1,
                vec![0; MAX_PAYLOAD + 1]
            )
            .is_err()
        );
        assert!(Bundle::decode(&[VERSION, 1, 2, 3]).is_err());
        assert!(Bundle::decode(&[9]).is_err());
    }

    #[test]
    fn expiry_and_forwarding() {
        let mut b = Bundle::new(BundleKind::Message, [1; 16], [2; 16], 10, 2, vec![]).unwrap();
        let now = b.created_at;
        assert!(b.is_forwardable(now));
        assert!(!b.is_forwardable(now + 10));
        b.hops = 2;
        assert!(!b.is_forwardable(now));
    }
}
