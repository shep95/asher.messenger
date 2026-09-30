//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Passphrase-encrypted backup of a node: its identity and its whole
//! snapshot (contacts, groups, carried bundles, outstanding acks, nearby
//! list, half-finished attachments). Replaces the storage service for mesh
//! state.
//!
//! ```text
//! "ASHB"  u8 version = 1  salt 16  nonce 12  ciphertext
//! ```
//!
//! The key is Argon2id(passphrase, salt; 64 MiB, 3 passes) and the cipher
//! AES-256-GCM-SIV with the 33-byte header as associated data. The plaintext
//! is `u32 len + identity export` followed by the snapshot encoding.

use aes_gcm_siv::aead::{Aead, Payload};
use aes_gcm_siv::{Aes256GcmSiv, KeyInit, Nonce};
use argon2::{Algorithm, Argon2, ParamsBuilder, Version};

use crate::persist::Snapshot;
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

pub const MAGIC: &[u8; 4] = b"ASHB";
pub const VERSION: u8 = 1;
pub const SALT_LEN: usize = 16;
pub const NONCE_LEN: usize = 12;
const HEADER_LEN: usize = 4 + 1 + SALT_LEN + NONCE_LEN;
const TAG_LEN: usize = 16;
/// Refuse to even derive a key for a blob larger than this (the snapshot
/// limit plus the identity and the header).
pub const MAX_BLOB_LEN: usize = crate::persist::MAX_SNAPSHOT_LEN + (1 << 20);
/// Largest identity export inside a backup.
const MAX_IDENTITY_LEN: usize = 64 * 1024;

const ARGON2_M_COST_KIB: u32 = 64 * 1024;
const ARGON2_T_COST: u32 = 3;
const ARGON2_P_COST: u32 = 1;

fn derive_key(passphrase: &str, salt: &[u8]) -> Result<[u8; 32]> {
    let params = ParamsBuilder::new()
        .m_cost(ARGON2_M_COST_KIB)
        .t_cost(ARGON2_T_COST)
        .p_cost(ARGON2_P_COST)
        .output_len(32)
        .build()
        .map_err(|e| Error::Other(format!("argon2 params: {e}")))?;
    let hasher = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut key = [0u8; 32];
    hasher
        .hash_password_into(passphrase.as_bytes(), salt, &mut key)
        .map_err(|e| Error::Other(format!("argon2: {e}")))?;
    Ok(key)
}

/// Encrypts `plaintext` under `passphrase` into a backup blob.
pub fn seal(passphrase: &str, plaintext: &[u8]) -> Result<Vec<u8>> {
    let salt: [u8; SALT_LEN] = rand::random();
    let nonce: [u8; NONCE_LEN] = rand::random();
    let mut header = Vec::with_capacity(HEADER_LEN);
    header.extend_from_slice(MAGIC);
    header.push(VERSION);
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce);
    let key = derive_key(passphrase, &salt)?;
    let ciphertext = Aes256GcmSiv::new(&key.into())
        .encrypt(
            &Nonce::from(nonce),
            Payload {
                msg: plaintext,
                aad: &header,
            },
        )
        .map_err(|_| Error::Other("backup encryption failed".into()))?;
    header.extend_from_slice(&ciphertext);
    Ok(header)
}

/// Decrypts a backup blob. A wrong passphrase or a tampered blob is
/// [`Error::BadPassphrase`]; a blob that is not a backup at all is a wire
/// error.
pub fn open(passphrase: &str, blob: &[u8]) -> Result<Vec<u8>> {
    if blob.len() > MAX_BLOB_LEN {
        return Err(Error::TooLarge(blob.len(), MAX_BLOB_LEN));
    }
    if blob.len() < HEADER_LEN + TAG_LEN {
        return Err(Error::Wire("backup too short"));
    }
    if &blob[..4] != MAGIC {
        return Err(Error::Wire("not a meshlink backup"));
    }
    if blob[4] != VERSION {
        return Err(Error::Wire("unsupported backup version"));
    }
    let (header, ciphertext) = blob.split_at(HEADER_LEN);
    let salt = &header[5..5 + SALT_LEN];
    let nonce: [u8; NONCE_LEN] = header[5 + SALT_LEN..HEADER_LEN]
        .try_into()
        .expect("fixed header layout");
    let key = derive_key(passphrase, salt)?;
    Aes256GcmSiv::new(&key.into())
        .decrypt(
            &Nonce::from(nonce),
            Payload {
                msg: ciphertext,
                aad: header,
            },
        )
        .map_err(|_| Error::BadPassphrase)
}

/// What a backup holds once opened.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Backup {
    /// [`crate::MeshIdentity::export`] bytes.
    pub identity: Vec<u8>,
    pub snapshot: Snapshot,
}

impl Backup {
    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        let len = u32::try_from(self.identity.len()).expect("identity export is small");
        w.u32(len).fixed(&self.identity);
        let mut out = w.finish();
        out.extend(self.snapshot.encode());
        out
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        let mut r = Reader::new(data);
        let n = r.u32()? as usize;
        if n == 0 || n > MAX_IDENTITY_LEN {
            return Err(Error::Wire("identity length out of range"));
        }
        let identity = r.take(n)?.to_vec();
        let snapshot = Snapshot::decode(r.rest())?;
        Ok(Self { identity, snapshot })
    }

    pub fn seal(&self, passphrase: &str) -> Result<Vec<u8>> {
        seal(passphrase, &self.encode())
    }

    pub fn open(passphrase: &str, blob: &[u8]) -> Result<Self> {
        Self::decode(&open(passphrase, blob)?)
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn seal_open_and_reject_wrong_passphrase() {
        let blob = seal("correct horse", b"hello").unwrap();
        assert_eq!(&blob[..4], MAGIC);
        assert_eq!(blob[4], VERSION);
        assert_eq!(blob.len(), HEADER_LEN + 5 + TAG_LEN);
        assert_eq!(open("correct horse", &blob).unwrap(), b"hello");
        assert!(matches!(
            open("battery staple", &blob),
            Err(Error::BadPassphrase)
        ));
        let mut tampered = blob.clone();
        *tampered.last_mut().unwrap() ^= 1;
        assert!(matches!(
            open("correct horse", &tampered),
            Err(Error::BadPassphrase)
        ));
        let mut wrong_magic = blob.clone();
        wrong_magic[0] = b'X';
        assert!(matches!(
            open("correct horse", &wrong_magic),
            Err(Error::Wire(_))
        ));
        assert!(open("x", &blob[..10]).is_err());
        // Two seals of the same data differ (fresh salt and nonce).
        assert_ne!(seal("p", b"same").unwrap(), seal("p", b"same").unwrap());
    }

    #[test]
    fn backup_round_trips() {
        let b = Backup {
            identity: vec![1, 2, 3],
            snapshot: Snapshot::default(),
        };
        assert_eq!(Backup::decode(&b.encode()).unwrap(), b);
        let sealed = b.seal("pw").unwrap();
        assert_eq!(Backup::open("pw", &sealed).unwrap(), b);
        assert!(Backup::decode(&[0, 0, 0, 0]).is_err());
    }
}
