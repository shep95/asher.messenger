//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! The unit of store-carry-forward delivery.
//!
//! A bundle is opaque to relays apart from its header: who it is from (needed
//! by the recipient to pick the Signal session), who it is for, when it
//! expires, how many hops it may still travel, and a 16-byte *acknowledgement
//! commitment*. The payload is Signal Protocol ciphertext (messages), a signed
//! contact card (beacons) or an acknowledgement (a bundle id plus the token
//! that opens its commitment). Relays never need to decrypt anything.
//!
//! Every field is fixed-width or length-prefixed; nothing is parsed
//! recursively; every size is bounded before allocation.

use sha2::{Digest, Sha256};

use crate::wire::{Reader, Writer};
use crate::{Error, Result};

/// 16-byte truncated SHA-256 of a Signal identity public key: a node's address.
pub type Fingerprint = [u8; 16];
/// Content hash identifying a bundle everywhere in the network.
pub type BundleId = [u8; 16];
/// Commitment to an acknowledgement token (see [`ack_commitment`]).
pub type AckCommitment = [u8; 16];
/// The secret that opens an [`AckCommitment`]; travels inside the ciphertext.
pub type AckToken = [u8; 16];

/// Destination of beacons and acknowledgements.
pub const BROADCAST: Fingerprint = [0xff; 16];

/// Wire format version.
pub const VERSION: u8 = 1;
/// Largest payload a single bundle may carry. Big enough for a contact card
/// (about 2 KiB with a Kyber-1024 prekey) or a long text; attachments are out
/// of scope for a low-bandwidth transport.
pub const MAX_PAYLOAD: usize = 4096;
/// Bytes of header before the payload length prefix.
pub const HEADER_LEN: usize = 1 + 1 + 16 + 16 + 8 + 4 + 1 + 1 + 8 + 16;
/// Largest encoded bundle.
pub const MAX_WIRE_LEN: usize = HEADER_LEN + 2 + MAX_PAYLOAD;
/// Longest lifetime any node will honour, whatever the header says.
pub const MAX_TTL_SECS: u32 = 30 * 24 * 3600;
/// Largest hop limit any node will honour.
pub const MAX_HOPS: u8 = 16;
/// How far in the future a sender's clock may be before the bundle is refused.
pub const MAX_CLOCK_SKEW_SECS: u64 = 300;
/// Creation times are rounded down to this many seconds so a bundle does not
/// leak the exact moment it was written.
pub const TIME_GRANULARITY_SECS: u64 = 60;
/// A bundle whose lifetime is at most this is *urgent* (call signalling):
/// nodes offer and send it ahead of everything else and do not hold it back
/// for pacing. Priority is thus expressed by the TTL already on the wire;
/// nothing new for a relay to trust.
pub const URGENT_TTL_SECS: u32 = 120;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum BundleKind {
    /// Signal Protocol ciphertext for `dst`. Payload: `[message_type u8][ciphertext]`.
    Message = 1,
    /// A signed [`crate::ContactCard`] from `src`. Payload: the card bytes.
    Beacon = 2,
    /// `src` received a bundle. Payload: `[bundle id 16][ack token 16]`.
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
    /// Creation time, seconds since the Unix epoch (sender's clock, rounded).
    pub created_at: u64,
    /// Lifetime from `created_at`.
    pub ttl_secs: u32,
    /// Hops travelled so far. Incremented by each relay that forwards it.
    pub hops: u8,
    /// Maximum hops; a bundle with `hops >= max_hops` is delivered but not forwarded.
    pub max_hops: u8,
    /// Random per-bundle value making the id unique even for identical payloads.
    pub nonce: u64,
    /// For messages: `ack_commitment(token)` where `token` is inside the
    /// ciphertext. An `Ack` for this bundle is only honoured if it opens the
    /// commitment, so nobody who cannot read the message can pretend it was
    /// delivered. Zero for beacons and acks.
    pub ack_commit: AckCommitment,
    pub payload: Vec<u8>,
}

impl Bundle {
    pub fn new(
        kind: BundleKind,
        src: Fingerprint,
        dst: Fingerprint,
        ttl_secs: u32,
        max_hops: u8,
        ack_commit: AckCommitment,
        payload: Vec<u8>,
    ) -> Result<Self> {
        if payload.len() > MAX_PAYLOAD {
            return Err(Error::TooLarge(payload.len(), MAX_PAYLOAD));
        }
        let now = crate::now_secs();
        Ok(Self {
            kind,
            src,
            dst,
            created_at: now - now % TIME_GRANULARITY_SECS,
            ttl_secs: ttl_secs.min(MAX_TTL_SECS),
            hops: 0,
            max_hops: max_hops.min(MAX_HOPS),
            nonce: rand::random(),
            ack_commit,
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
        h.update(self.ack_commit);
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

    /// Whether this bundle should go ahead of ordinary traffic (see
    /// [`URGENT_TTL_SECS`]).
    pub fn is_urgent(&self) -> bool {
        self.kind == BundleKind::Message && self.ttl_secs <= URGENT_TTL_SECS
    }

    /// Whether a relay may pass this bundle on to another node.
    pub fn is_forwardable(&self, now: u64) -> bool {
        !self.is_expired(now) && self.hops < self.max_hops
    }

    /// Size on the wire, without encoding.
    pub fn wire_len(&self) -> usize {
        HEADER_LEN + 2 + self.payload.len()
    }

    /// Header sanity a receiver applies before spending any memory or CPU on
    /// a bundle: bounded lifetime and hop count, a clock that is not from the
    /// future, and a payload shape that matches the kind.
    pub fn validate(&self, now: u64) -> Result<()> {
        if self.created_at > now.saturating_add(MAX_CLOCK_SKEW_SECS) {
            return Err(Error::Wire("bundle created in the future"));
        }
        if self.ttl_secs > MAX_TTL_SECS {
            return Err(Error::Wire("bundle lifetime too long"));
        }
        if self.max_hops > MAX_HOPS || self.hops > self.max_hops {
            return Err(Error::Wire("bundle hop count out of range"));
        }
        match self.kind {
            BundleKind::Message => {
                if self.payload.len() < 2 || self.dst == BROADCAST {
                    return Err(Error::Wire("malformed message bundle"));
                }
            }
            BundleKind::Beacon => {
                if self.dst != BROADCAST || self.payload.is_empty() || self.ack_commit != [0; 16] {
                    return Err(Error::Wire("malformed beacon bundle"));
                }
            }
            BundleKind::Ack => {
                if self.dst != BROADCAST || self.payload.len() != 32 || self.ack_commit != [0; 16] {
                    return Err(Error::Wire("malformed ack bundle"));
                }
            }
        }
        Ok(())
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
            .fixed(&self.ack_commit)
            .bytes(&self.payload);
        w.finish()
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        if data.len() > MAX_WIRE_LEN {
            return Err(Error::TooLarge(data.len(), MAX_WIRE_LEN));
        }
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
        let ack_commit = r.fixed::<16>()?;
        let payload = r.bytes()?;
        if payload.len() > MAX_PAYLOAD {
            return Err(Error::TooLarge(payload.len(), MAX_PAYLOAD));
        }
        r.finish()?;
        Ok(Self {
            kind,
            src,
            dst,
            created_at,
            ttl_secs,
            hops,
            max_hops,
            nonce,
            ack_commit,
            payload: payload.to_vec(),
        })
    }
}

/// Derives the address of an identity key.
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

/// The commitment placed in a message header for its acknowledgement token.
pub fn ack_commitment(token: &AckToken) -> AckCommitment {
    let mut h = Sha256::new();
    h.update(b"meshlink-ack-v1");
    h.update(token);
    let out = h.finalize();
    out[..16].try_into().expect("16 bytes")
}

/// Constant-time check that `token` opens `commit`.
pub fn ack_opens(commit: &AckCommitment, token: &AckToken) -> bool {
    use subtle::ConstantTimeEq as _;
    ack_commitment(token).ct_eq(commit).into()
}

/// Payload of an `Ack` bundle.
pub fn ack_payload(id: &BundleId, token: &AckToken) -> Vec<u8> {
    let mut v = Vec::with_capacity(32);
    v.extend_from_slice(id);
    v.extend_from_slice(token);
    v
}

/// Splits an `Ack` payload into `(acked bundle id, token)`.
pub fn parse_ack(payload: &[u8]) -> Result<(BundleId, AckToken)> {
    if payload.len() != 32 {
        return Err(Error::Wire("ack payload must be 32 bytes"));
    }
    Ok((
        payload[..16].try_into().expect("16"),
        payload[16..].try_into().expect("16"),
    ))
}

#[cfg(test)]
mod test {
    use super::*;

    fn sample() -> Bundle {
        Bundle::new(
            BundleKind::Message,
            [1; 16],
            [2; 16],
            600,
            5,
            [9; 16],
            vec![3, 4, 5],
        )
        .unwrap()
    }

    #[test]
    fn encode_decode_and_stable_id() {
        let b = sample();
        let bytes = b.encode();
        assert_eq!(bytes.len(), b.wire_len());
        let back = Bundle::decode(&bytes).unwrap();
        assert_eq!(back, b);
        let mut hopped = b.clone();
        hopped.hops = 3;
        assert_eq!(hopped.id(), b.id(), "hops do not change the id");
        let mut other = b.clone();
        other.ack_commit = [8; 16];
        assert_ne!(other.id(), b.id(), "the commitment is bound by the id");
        assert_eq!(b.created_at % TIME_GRANULARITY_SECS, 0);
    }

    #[test]
    fn expiry_and_forwarding() {
        let b = sample();
        let now = b.created_at;
        assert!(!b.is_expired(now));
        assert!(b.is_forwardable(now));
        assert!(b.is_expired(now + 600));
        let mut at_limit = b.clone();
        at_limit.hops = 5;
        assert!(!at_limit.is_forwardable(now));
        assert!(!at_limit.is_expired(now));
    }

    #[test]
    fn rejects_oversized_and_garbage() {
        assert!(matches!(
            Bundle::new(
                BundleKind::Message,
                [1; 16],
                [2; 16],
                1,
                1,
                [0; 16],
                vec![0; MAX_PAYLOAD + 1]
            ),
            Err(Error::TooLarge(_, _))
        ));
        assert!(Bundle::decode(&[]).is_err());
        assert!(Bundle::decode(&[VERSION, 9]).is_err());
        let mut bytes = sample().encode();
        bytes.push(0);
        assert!(Bundle::decode(&bytes).is_err(), "trailing bytes rejected");
        assert!(Bundle::decode(&vec![0; MAX_WIRE_LEN + 1]).is_err());
    }

    #[test]
    fn validation_bounds() {
        let b = sample();
        let now = b.created_at;
        b.validate(now).unwrap();
        let mut future = b.clone();
        future.created_at = now + MAX_CLOCK_SKEW_SECS + 60;
        assert!(future.validate(now).is_err());
        let mut long = b.clone();
        long.ttl_secs = MAX_TTL_SECS + 1;
        assert!(long.validate(now).is_err());
        let mut hops = b.clone();
        hops.hops = 6;
        assert!(hops.validate(now).is_err());
        let mut ack = b.clone();
        ack.kind = BundleKind::Ack;
        assert!(
            ack.validate(now).is_err(),
            "ack needs broadcast dst and 32-byte payload"
        );
    }

    #[test]
    fn ack_commitments() {
        let token = [7u8; 16];
        let commit = ack_commitment(&token);
        assert!(ack_opens(&commit, &token));
        assert!(!ack_opens(&commit, &[8u8; 16]));
        let (id, t) = parse_ack(&ack_payload(&[1; 16], &token)).unwrap();
        assert_eq!((id, t), ([1; 16], token));
        assert!(parse_ack(&[0; 31]).is_err());
    }
}
