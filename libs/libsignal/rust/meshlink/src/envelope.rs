//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! What goes *inside* the Signal ciphertext of a message bundle.
//!
//! ```text
//! u8   version = 1
//! u8   kind          1 text, 2 group text, 3 group invite, 4 card share
//! 16   ack token     opens the bundle header's ack commitment
//! u16  body length + body
//! ...  zero padding to a multiple of PAD_BLOCK bytes
//! ```
//!
//! The padding hides the true length of short messages from anyone watching
//! the air; the token lets the recipient, and only the recipient, produce an
//! acknowledgement relays will honour.

use crate::bundle::AckToken;
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

const ENVELOPE_VERSION: u8 = 1;
/// Plaintexts are padded to a multiple of this many bytes.
pub const PAD_BLOCK: usize = 256;
/// Largest body an envelope can carry and still fit a bundle after the
/// ciphertext overhead of a PreKeySignalMessage with a Kyber-1024 ciphertext.
pub const MAX_BODY: usize = 2048;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum EnvelopeKind {
    /// Body: UTF-8 (or any bytes) for a one-to-one conversation.
    Text = 1,
    /// Body: `[group id 16][text]`.
    GroupText = 2,
    /// Body: an encoded [`crate::group::MeshGroup`].
    GroupInvite = 3,
    /// Body: an encoded [`crate::ContactCard`] of a third party (its own
    /// signature makes it trustworthy regardless of who forwarded it).
    CardShare = 4,
}

impl TryFrom<u8> for EnvelopeKind {
    type Error = Error;
    fn try_from(v: u8) -> Result<Self> {
        Ok(match v {
            1 => Self::Text,
            2 => Self::GroupText,
            3 => Self::GroupInvite,
            4 => Self::CardShare,
            _ => return Err(Error::Wire("unknown envelope kind")),
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Envelope {
    pub kind: EnvelopeKind,
    pub ack_token: AckToken,
    pub body: Vec<u8>,
}

impl Envelope {
    pub fn new(kind: EnvelopeKind, body: Vec<u8>) -> Result<Self> {
        if body.len() > MAX_BODY {
            return Err(Error::TooLarge(body.len(), MAX_BODY));
        }
        Ok(Self {
            kind,
            ack_token: rand::random(),
            body,
        })
    }

    /// Encodes and pads.
    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u8(ENVELOPE_VERSION)
            .u8(self.kind as u8)
            .fixed(&self.ack_token)
            .bytes(&self.body);
        let mut out = w.finish();
        let padded = out.len().div_ceil(PAD_BLOCK) * PAD_BLOCK;
        out.resize(padded, 0);
        out
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        let mut r = Reader::new(data);
        if r.u8()? != ENVELOPE_VERSION {
            return Err(Error::Wire("unsupported envelope version"));
        }
        let kind = EnvelopeKind::try_from(r.u8()?)?;
        let ack_token = r.fixed::<16>()?;
        let body = r.bytes()?;
        if body.len() > MAX_BODY {
            return Err(Error::TooLarge(body.len(), MAX_BODY));
        }
        // Whatever follows must be padding.
        if r.rest().iter().any(|b| *b != 0) {
            return Err(Error::Wire("envelope padding is not zero"));
        }
        Ok(Self {
            kind,
            ack_token,
            body: body.to_vec(),
        })
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn pads_to_blocks_and_round_trips() {
        let e = Envelope::new(EnvelopeKind::Text, b"hi".to_vec()).unwrap();
        let bytes = e.encode();
        assert_eq!(bytes.len(), PAD_BLOCK);
        assert_eq!(Envelope::decode(&bytes).unwrap(), e);
        let long = Envelope::new(EnvelopeKind::Text, vec![b'x'; 300]).unwrap();
        assert_eq!(long.encode().len(), 2 * PAD_BLOCK);
        assert!(Envelope::new(EnvelopeKind::Text, vec![0; MAX_BODY + 1]).is_err());
        let mut tampered = bytes.clone();
        *tampered.last_mut().unwrap() = 1;
        assert!(Envelope::decode(&tampered).is_err());
    }
}
