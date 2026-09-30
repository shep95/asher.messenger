//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Device-keyed identity and the offline prekey bundle ("contact card").
//!
//! A [`MeshIdentity`] is a Signal identity key pair plus the signed and Kyber
//! prekeys a peer needs to start a PQXDH session. Its [`ContactCard`] is that
//! prekey bundle in a self-describing, signed wire form that can be shown as a
//! QR code, sent over any link, or broadcast as a beacon. Holding a card is
//! sufficient to send to its owner; no server is consulted.

use base64::Engine as _;
use libsignal_protocol::{
    DeviceId, GenericSignedPreKey as _, IdentityKey, IdentityKeyPair, InMemSignalProtocolStore,
    KeyPair, KyberPreKeyId, KyberPreKeyRecord, PreKeyBundle, ProtocolAddress, PublicKey,
    SignedPreKeyId, SignedPreKeyRecord, Timestamp, kem,
};
use rand::{CryptoRng, Rng};

use crate::bundle::{Fingerprint, fingerprint_hex, fingerprint_of};
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

const CARD_VERSION: u8 = 1;

/// A node's offline prekey bundle.
#[derive(Clone, Debug)]
pub struct ContactCard {
    pub registration_id: u32,
    pub device_id: u8,
    pub identity_key: IdentityKey,
    pub signed_pre_key_id: u32,
    pub signed_pre_key: PublicKey,
    pub signed_pre_key_signature: Vec<u8>,
    pub kyber_pre_key_id: u32,
    pub kyber_pre_key: kem::PublicKey,
    pub kyber_pre_key_signature: Vec<u8>,
    /// Seconds since the Unix epoch when the card was issued.
    pub created_at: u64,
}

impl ContactCard {
    pub fn fingerprint(&self) -> Fingerprint {
        fingerprint_of(&self.identity_key.serialize())
    }

    /// Checks both prekey signatures against the identity key.
    pub fn verify(&self) -> Result<()> {
        let ik = self.identity_key.public_key();
        let spk_ok = ik.verify_signature(
            &self.signed_pre_key.serialize(),
            &self.signed_pre_key_signature,
        );
        let kyber_ok = ik.verify_signature(
            &self.kyber_pre_key.serialize(),
            &self.kyber_pre_key_signature,
        );
        if spk_ok && kyber_ok {
            Ok(())
        } else {
            Err(Error::BadCardSignature)
        }
    }

    pub fn device_id(&self) -> Result<DeviceId> {
        DeviceId::new(self.device_id).map_err(|_| Error::Wire("invalid device id"))
    }

    pub fn address(&self) -> Result<ProtocolAddress> {
        Ok(ProtocolAddress::new(
            fingerprint_hex(&self.fingerprint()),
            self.device_id()?,
        ))
    }

    /// The libsignal prekey bundle used to start a session with this contact.
    /// No one-time prekey: like Signal's last-resort Kyber prekey, the card's
    /// keys may be reused by many initiators, which is the trade-off for
    /// working without a server that hands out single-use keys.
    pub fn to_pre_key_bundle(&self) -> Result<PreKeyBundle> {
        Ok(PreKeyBundle::new(
            self.registration_id,
            self.device_id()?,
            None,
            SignedPreKeyId::from(self.signed_pre_key_id),
            self.signed_pre_key,
            self.signed_pre_key_signature.clone(),
            KyberPreKeyId::from(self.kyber_pre_key_id),
            self.kyber_pre_key.clone(),
            self.kyber_pre_key_signature.clone(),
            self.identity_key,
        )?)
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u8(CARD_VERSION)
            .u32(self.registration_id)
            .u8(self.device_id)
            .bytes(&self.identity_key.serialize())
            .u32(self.signed_pre_key_id)
            .bytes(&self.signed_pre_key.serialize())
            .bytes(&self.signed_pre_key_signature)
            .u32(self.kyber_pre_key_id)
            .bytes(&self.kyber_pre_key.serialize())
            .bytes(&self.kyber_pre_key_signature)
            .u64(self.created_at);
        w.finish()
    }

    /// Decodes and verifies a card.
    pub fn decode(data: &[u8]) -> Result<Self> {
        let mut r = Reader::new(data);
        if r.u8()? != CARD_VERSION {
            return Err(Error::Wire("unsupported contact card version"));
        }
        let registration_id = r.u32()?;
        let device_id = r.u8()?;
        let identity_key = IdentityKey::decode(r.bytes()?)?;
        let signed_pre_key_id = r.u32()?;
        let signed_pre_key = PublicKey::deserialize(r.bytes()?)?;
        let signed_pre_key_signature = r.bytes()?.to_vec();
        let kyber_pre_key_id = r.u32()?;
        let kyber_pre_key = kem::PublicKey::deserialize(r.bytes()?)?;
        let kyber_pre_key_signature = r.bytes()?.to_vec();
        let created_at = r.u64()?;
        r.finish()?;
        let card = Self {
            registration_id,
            device_id,
            identity_key,
            signed_pre_key_id,
            signed_pre_key,
            signed_pre_key_signature,
            kyber_pre_key_id,
            kyber_pre_key,
            kyber_pre_key_signature,
            created_at,
        };
        card.verify()?;
        Ok(card)
    }

    /// URL-safe base64 for QR codes and copy-paste.
    pub fn to_base64(&self) -> String {
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(self.encode())
    }

    pub fn from_base64(s: &str) -> Result<Self> {
        let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(s.trim())
            .map_err(|_| Error::Wire("contact card is not base64"))?;
        Self::decode(&bytes)
    }
}

/// This device's identity, prekeys and Signal Protocol stores.
pub struct MeshIdentity {
    pub store: InMemSignalProtocolStore,
    fingerprint: Fingerprint,
    device_id: DeviceId,
    card: ContactCard,
}

impl MeshIdentity {
    /// Generates a fresh identity with one signed prekey and one Kyber prekey.
    pub fn generate<R: Rng + CryptoRng>(csprng: &mut R) -> Result<Self> {
        let identity = IdentityKeyPair::generate(csprng);
        let registration_id: u32 = csprng.random_range(1..16380);
        Self::from_identity(identity, registration_id, csprng)
    }

    /// Builds the mesh identity around an existing Signal identity key pair
    /// (the apps pass their own so the fingerprint equals their safety-number
    /// identity).
    pub fn from_identity<R: Rng + CryptoRng>(
        identity: IdentityKeyPair,
        registration_id: u32,
        csprng: &mut R,
    ) -> Result<Self> {
        let mut store = InMemSignalProtocolStore::new(identity, registration_id)?;
        let device_id = DeviceId::new(1).expect("1 is a valid device id");

        let signed_pre_key_pair = KeyPair::generate(csprng);
        let signed_pre_key_signature = identity
            .private_key()
            .calculate_signature(&signed_pre_key_pair.public_key.serialize(), csprng)?;
        let kyber_pre_key_pair = kem::KeyPair::generate(kem::KeyType::Kyber1024, csprng);
        let kyber_pre_key_signature = identity
            .private_key()
            .calculate_signature(&kyber_pre_key_pair.public_key.serialize(), csprng)?;

        let signed_pre_key_id: u32 = csprng.random_range(1..0x00ff_ffff);
        let kyber_pre_key_id: u32 = csprng.random_range(1..0x00ff_ffff);
        let now_ms = crate::now_secs() * 1000;

        use libsignal_protocol::{KyberPreKeyStore as _, SignedPreKeyStore as _};
        crate::complete_now(store.save_signed_pre_key(
            signed_pre_key_id.into(),
            &SignedPreKeyRecord::new(
                signed_pre_key_id.into(),
                Timestamp::from_epoch_millis(now_ms),
                &signed_pre_key_pair,
                &signed_pre_key_signature,
            ),
        ))?;
        crate::complete_now(store.save_kyber_pre_key(
            kyber_pre_key_id.into(),
            &KyberPreKeyRecord::new(
                kyber_pre_key_id.into(),
                Timestamp::from_epoch_millis(now_ms),
                &kyber_pre_key_pair,
                &kyber_pre_key_signature,
            ),
        ))?;

        let card = ContactCard {
            registration_id,
            device_id: 1,
            identity_key: *identity.identity_key(),
            signed_pre_key_id,
            signed_pre_key: signed_pre_key_pair.public_key,
            signed_pre_key_signature: signed_pre_key_signature.to_vec(),
            kyber_pre_key_id,
            kyber_pre_key: kyber_pre_key_pair.public_key.clone(),
            kyber_pre_key_signature: kyber_pre_key_signature.to_vec(),
            created_at: crate::now_secs(),
        };
        card.verify()?;
        let fingerprint = card.fingerprint();

        Ok(Self {
            store,
            fingerprint,
            device_id,
            card,
        })
    }

    pub fn fingerprint(&self) -> Fingerprint {
        self.fingerprint
    }

    pub fn card(&self) -> &ContactCard {
        &self.card
    }

    pub fn address(&self) -> ProtocolAddress {
        ProtocolAddress::new(fingerprint_hex(&self.fingerprint), self.device_id)
    }
}

#[cfg(test)]
mod test {
    use rand::TryRngCore as _;

    use super::*;

    #[test]
    fn card_round_trip_and_tamper_detection() {
        let mut rng = rand::rngs::OsRng.unwrap_err();
        let me = MeshIdentity::generate(&mut rng).unwrap();
        let card = me.card();
        let encoded = card.to_base64();
        let back = ContactCard::from_base64(&encoded).unwrap();
        assert_eq!(back.fingerprint(), me.fingerprint());
        assert_eq!(back.registration_id, card.registration_id);
        back.to_pre_key_bundle().unwrap();

        // Flip a byte of the signed prekey: signature no longer verifies.
        let mut raw = card.encode();
        let idx = 1 + 4 + 1 + 2 + 33 + 4 + 2 + 5;
        raw[idx] ^= 0x01;
        assert!(matches!(
            ContactCard::decode(&raw),
            Err(Error::BadCardSignature) | Err(Error::Protocol(_))
        ));
    }
}
