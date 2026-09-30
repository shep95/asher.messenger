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
//!
//! Every byte of a card, including the display name and issue time, is
//! covered by a signature from the identity key, so a relay cannot rename
//! someone or replay an old card as a new one.

use base64::Engine as _;
use libsignal_protocol::{
    DeviceId, GenericSignedPreKey as _, IdentityKey, IdentityKeyPair, KeyPair, KyberPreKeyId,
    KyberPreKeyRecord, PreKeyBundle, ProtocolAddress, PublicKey, SignedPreKeyId,
    SignedPreKeyRecord, Timestamp, kem,
};
use rand::{CryptoRng, Rng};

use crate::bundle::{Fingerprint, fingerprint_hex, fingerprint_of};
use crate::stores::ProtocolStores;
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

const CARD_VERSION: u8 = 1;
const IDENTITY_VERSION: u8 = 1;
/// Longest display name, in bytes of UTF-8.
pub const MAX_NAME_BYTES: usize = 64;
/// Largest encoded card (Kyber-1024 public key dominates).
pub const MAX_CARD_LEN: usize = 2400;
/// Domain separator for the whole-card signature.
const CARD_SIG_DOMAIN: &[u8] = b"meshlink-card-v1";

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
    /// Display name chosen by the owner (may be empty).
    pub name: String,
    /// Signature by the identity key over everything above.
    pub card_signature: Vec<u8>,
}

impl PartialEq for ContactCard {
    fn eq(&self, other: &Self) -> bool {
        self.encode() == other.encode()
    }
}

impl Eq for ContactCard {}

impl ContactCard {
    pub fn fingerprint(&self) -> Fingerprint {
        fingerprint_of(&self.identity_key.serialize())
    }

    fn signed_body(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.fixed(CARD_SIG_DOMAIN)
            .u8(CARD_VERSION)
            .u32(self.registration_id)
            .u8(self.device_id)
            .bytes(&self.identity_key.serialize())
            .u32(self.signed_pre_key_id)
            .bytes(&self.signed_pre_key.serialize())
            .bytes(&self.signed_pre_key_signature)
            .u32(self.kyber_pre_key_id)
            .bytes(&self.kyber_pre_key.serialize())
            .bytes(&self.kyber_pre_key_signature)
            .u64(self.created_at)
            .bytes(self.name.as_bytes());
        w.finish()
    }

    /// Checks the prekey signatures and the whole-card signature against the
    /// identity key.
    pub fn verify(&self) -> Result<()> {
        if self.name.len() > MAX_NAME_BYTES {
            return Err(Error::Wire("name too long"));
        }
        let ik = self.identity_key.public_key();
        let spk_ok = ik.verify_signature(
            &self.signed_pre_key.serialize(),
            &self.signed_pre_key_signature,
        );
        let kyber_ok = ik.verify_signature(
            &self.kyber_pre_key.serialize(),
            &self.kyber_pre_key_signature,
        );
        let card_ok = ik.verify_signature(&self.signed_body(), &self.card_signature);
        if spk_ok && kyber_ok && card_ok {
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
            .u64(self.created_at)
            .bytes(self.name.as_bytes())
            .bytes(&self.card_signature);
        w.finish()
    }

    /// Decodes and verifies a card.
    pub fn decode(data: &[u8]) -> Result<Self> {
        if data.len() > MAX_CARD_LEN {
            return Err(Error::TooLarge(data.len(), MAX_CARD_LEN));
        }
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
        let name_bytes = r.bytes()?;
        if name_bytes.len() > MAX_NAME_BYTES {
            return Err(Error::Wire("name too long"));
        }
        let name = std::str::from_utf8(name_bytes)
            .map_err(|_| Error::Wire("name is not UTF-8"))?
            .to_owned();
        let card_signature = r.bytes()?.to_vec();
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
            name,
            card_signature,
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

/// This device's identity and prekeys. Sessions live in the app's
/// [`ProtocolStores`]; this struct only holds what must be persisted to
/// remain the same node across restarts (see [`MeshIdentity::export`]).
pub struct MeshIdentity {
    identity: IdentityKeyPair,
    registration_id: u32,
    signed_pre_key: SignedPreKeyRecord,
    kyber_pre_key: KyberPreKeyRecord,
    card: ContactCard,
    fingerprint: Fingerprint,
    device_id: DeviceId,
}

impl MeshIdentity {
    /// Generates a fresh identity with one signed prekey and one Kyber prekey.
    pub fn generate<R: Rng + CryptoRng>(name: &str, csprng: &mut R) -> Result<Self> {
        let identity = IdentityKeyPair::generate(csprng);
        let registration_id: u32 = csprng.random_range(1..16380);
        Self::from_identity(identity, registration_id, name, csprng)
    }

    /// Builds the mesh identity around an existing Signal identity key pair
    /// (the apps pass their own so the fingerprint equals their safety-number
    /// identity).
    pub fn from_identity<R: Rng + CryptoRng>(
        identity: IdentityKeyPair,
        registration_id: u32,
        name: &str,
        csprng: &mut R,
    ) -> Result<Self> {
        if name.len() > MAX_NAME_BYTES {
            return Err(Error::Wire("name too long"));
        }
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
        let now = crate::now_secs();
        let signed_pre_key = SignedPreKeyRecord::new(
            signed_pre_key_id.into(),
            Timestamp::from_epoch_millis(now * 1000),
            &signed_pre_key_pair,
            &signed_pre_key_signature,
        );
        let kyber_pre_key = KyberPreKeyRecord::new(
            kyber_pre_key_id.into(),
            Timestamp::from_epoch_millis(now * 1000),
            &kyber_pre_key_pair,
            &kyber_pre_key_signature,
        );
        Self::assemble(
            identity,
            registration_id,
            signed_pre_key,
            kyber_pre_key,
            name,
            now,
            csprng,
        )
    }

    #[allow(clippy::too_many_arguments)]
    fn assemble<R: Rng + CryptoRng>(
        identity: IdentityKeyPair,
        registration_id: u32,
        signed_pre_key: SignedPreKeyRecord,
        kyber_pre_key: KyberPreKeyRecord,
        name: &str,
        created_at: u64,
        csprng: &mut R,
    ) -> Result<Self> {
        let mut card = ContactCard {
            registration_id,
            device_id: 1,
            identity_key: *identity.identity_key(),
            signed_pre_key_id: signed_pre_key.id()?.into(),
            signed_pre_key: signed_pre_key.public_key()?,
            signed_pre_key_signature: signed_pre_key.signature()?,
            kyber_pre_key_id: kyber_pre_key.id()?.into(),
            kyber_pre_key: kyber_pre_key.public_key()?,
            kyber_pre_key_signature: kyber_pre_key.signature()?,
            created_at,
            name: name.to_owned(),
            card_signature: Vec::new(),
        };
        card.card_signature = identity
            .private_key()
            .calculate_signature(&card.signed_body(), csprng)?
            .to_vec();
        card.verify()?;
        let fingerprint = card.fingerprint();
        Ok(Self {
            identity,
            registration_id,
            signed_pre_key,
            kyber_pre_key,
            card,
            fingerprint,
            device_id: DeviceId::new(1).expect("1 is a valid device id"),
        })
    }

    /// Makes sure the stores hold our identity and prekeys. Idempotent; call
    /// once per start. Stores that already hold a different identity key are
    /// left alone and an error is returned, since the caller must decide
    /// which one is right.
    pub fn install(&self, stores: &mut dyn ProtocolStores) -> Result<()> {
        let parts = stores.parts();
        let existing = crate::complete_now(parts.identity.get_identity_key_pair())?;
        if existing.identity_key() != self.identity.identity_key() {
            return Err(Error::Other(
                "protocol store holds a different identity key than the mesh identity".into(),
            ));
        }
        crate::complete_now(
            parts
                .signed_pre_key
                .save_signed_pre_key(self.signed_pre_key.id()?, &self.signed_pre_key),
        )?;
        crate::complete_now(
            parts
                .kyber_pre_key
                .save_kyber_pre_key(self.kyber_pre_key.id()?, &self.kyber_pre_key),
        )?;
        Ok(())
    }

    /// Serialises the secret material. Store it like the identity key itself.
    pub fn export(&self) -> Result<Vec<u8>> {
        let mut w = Writer::new();
        w.u8(IDENTITY_VERSION)
            .bytes(&self.identity.serialize())
            .u32(self.registration_id)
            .bytes(&self.signed_pre_key.serialize()?)
            .bytes(&self.kyber_pre_key.serialize()?)
            .u64(self.card.created_at)
            .bytes(self.card.name.as_bytes());
        Ok(w.finish())
    }

    pub fn import<R: Rng + CryptoRng>(data: &[u8], csprng: &mut R) -> Result<Self> {
        let mut r = Reader::new(data);
        if r.u8()? != IDENTITY_VERSION {
            return Err(Error::Wire("unsupported identity version"));
        }
        let identity = IdentityKeyPair::try_from(r.bytes()?)?;
        let registration_id = r.u32()?;
        let signed_pre_key = SignedPreKeyRecord::deserialize(r.bytes()?)?;
        let kyber_pre_key = KyberPreKeyRecord::deserialize(r.bytes()?)?;
        let created_at = r.u64()?;
        let name = std::str::from_utf8(r.bytes()?)
            .map_err(|_| Error::Wire("name is not UTF-8"))?
            .to_owned();
        r.finish()?;
        Self::assemble(
            identity,
            registration_id,
            signed_pre_key,
            kyber_pre_key,
            &name,
            created_at,
            csprng,
        )
    }

    /// Re-issues the card with a new name (and a fresh issue time so peers
    /// accept it as newer).
    pub fn rename<R: Rng + CryptoRng>(&mut self, name: &str, csprng: &mut R) -> Result<()> {
        let now = crate::now_secs().max(self.card.created_at + 1);
        let fresh = Self::assemble(
            self.identity,
            self.registration_id,
            self.signed_pre_key.clone(),
            self.kyber_pre_key.clone(),
            name,
            now,
            csprng,
        )?;
        *self = fresh;
        Ok(())
    }

    pub fn identity_key_pair(&self) -> &IdentityKeyPair {
        &self.identity
    }

    pub fn registration_id(&self) -> u32 {
        self.registration_id
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

/// Signal-style safety number between two cards: 60 digits both people can
/// compare aloud or scan, computed only from identity keys and fingerprints.
/// The same pair of cards yields the same number on both devices, and it
/// changes if either identity key changes. This is the mesh's replacement
/// for key transparency: verification in person instead of by a server.
pub fn safety_number(mine: &ContactCard, theirs: &ContactCard) -> Result<String> {
    const VERSION: u32 = 2;
    const ITERATIONS: u32 = 5200;
    let fp = libsignal_protocol::Fingerprint::new(
        VERSION,
        ITERATIONS,
        &mine.fingerprint(),
        &mine.identity_key,
        &theirs.fingerprint(),
        &theirs.identity_key,
    )
    .map_err(|e| Error::Other(format!("safety number: {e}")))?;
    fp.display_string()
        .map_err(|e| Error::Other(format!("safety number: {e}")))
}

#[cfg(test)]
mod test {
    use libsignal_protocol::InMemSignalProtocolStore;
    use rand::TryRngCore as _;

    use super::*;

    #[test]
    fn card_round_trip_and_tamper_detection() {
        let mut rng = rand::rngs::OsRng.unwrap_err();
        let me = MeshIdentity::generate("Ada", &mut rng).unwrap();
        let card = me.card();
        let encoded = card.to_base64();
        let back = ContactCard::from_base64(&encoded).unwrap();
        assert_eq!(back.fingerprint(), me.fingerprint());
        assert_eq!(back.registration_id, card.registration_id);
        assert_eq!(back.name, "Ada");
        back.to_pre_key_bundle().unwrap();

        // Renaming without the private key breaks the card signature.
        let mut renamed = card.clone();
        renamed.name = "Mallory".into();
        assert!(matches!(renamed.verify(), Err(Error::BadCardSignature)));
        assert!(ContactCard::decode(&renamed.encode()).is_err());
        // So does moving the issue time forward (replay as "newer").
        let mut replayed = card.clone();
        replayed.created_at += 1;
        assert!(replayed.verify().is_err());
        // Flip a byte of the signed prekey: signature no longer verifies.
        let mut raw = card.encode();
        let idx = 1 + 4 + 1 + 2 + 33 + 4 + 2 + 5;
        raw[idx] ^= 0x01;
        assert!(matches!(
            ContactCard::decode(&raw),
            Err(Error::BadCardSignature) | Err(Error::Protocol(_))
        ));
        assert!(card.encode().len() <= MAX_CARD_LEN);
    }

    #[test]
    fn export_import_rename_and_install() {
        let mut rng = rand::rngs::OsRng.unwrap_err();
        let mut me = MeshIdentity::generate("Ada", &mut rng).unwrap();
        let blob = me.export().unwrap();
        let again = MeshIdentity::import(&blob, &mut rng).unwrap();
        assert_eq!(again.fingerprint(), me.fingerprint());
        assert_eq!(again.card().created_at, me.card().created_at);
        assert_eq!(again.card().signed_pre_key_id, me.card().signed_pre_key_id);
        assert!(again.card().verify().is_ok());

        let old_time = me.card().created_at;
        me.rename("Ada L.", &mut rng).unwrap();
        assert_eq!(me.card().name, "Ada L.");
        assert!(me.card().created_at > old_time);

        let mut store =
            InMemSignalProtocolStore::new(*me.identity_key_pair(), me.registration_id()).unwrap();
        me.install(&mut store).unwrap();
        me.install(&mut store).unwrap();
        let other = MeshIdentity::generate("Bob", &mut rng).unwrap();
        assert!(
            other.install(&mut store).is_err(),
            "identity mismatch is refused"
        );

        let n1 = safety_number(me.card(), other.card()).unwrap();
        let n2 = safety_number(other.card(), me.card()).unwrap();
        assert_eq!(n1, n2);
        assert_eq!(n1.len(), 60);
    }
}
