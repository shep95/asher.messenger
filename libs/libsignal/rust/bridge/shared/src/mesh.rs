//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! meshlink for the apps: identities, contact cards and a node driven by
//! blocking poll calls from a background thread. See `docs/offline-mesh.md`.

use libsignal_bridge_macros::*;
use libsignal_bridge_types::mesh::{MeshContactCard, MeshIdentity, MeshNode, sixteen, sixteens};
use libsignal_protocol::{
    IdentityKeyPair, KyberPreKeyRecord, PreKeyBundle, PublicKey, SignedPreKeyRecord,
};
use meshlink::Result;
use rand::TryRngCore as _;

use crate::support::*;
use crate::*;

bridge_handle_fns!(MeshIdentity, clone = false);
bridge_handle_fns!(MeshContactCard, clone = false);
bridge_handle_fns!(MeshNode, clone = false);

// ---- Identity -------------------------------------------------------------

#[bridge_fn]
fn MeshIdentity_Generate(name: String) -> Result<MeshIdentity> {
    let mut rng = rand::rngs::OsRng.unwrap_err();
    Ok(MeshIdentity(meshlink::MeshIdentity::generate(
        &name, &mut rng,
    )?))
}

/// Builds the mesh identity around the app's existing Signal identity key
/// pair (serialized `IdentityKeyPair`) so the mesh fingerprint is the app's
/// identity. The app must store the returned prekey records in its own
/// stores (see `MeshIdentity_SignedPreKeyRecord` / `_KyberPreKeyRecord`).
#[bridge_fn]
fn MeshIdentity_FromIdentityKeyPair(
    key_pair: &[u8],
    registration_id: u32,
    name: String,
) -> Result<MeshIdentity> {
    let mut rng = rand::rngs::OsRng.unwrap_err();
    let ikp = IdentityKeyPair::try_from(key_pair)?;
    Ok(MeshIdentity(meshlink::MeshIdentity::from_identity(
        ikp,
        registration_id,
        &name,
        &mut rng,
    )?))
}

#[bridge_fn]
fn MeshIdentity_Import(data: &[u8]) -> Result<MeshIdentity> {
    let mut rng = rand::rngs::OsRng.unwrap_err();
    Ok(MeshIdentity(meshlink::MeshIdentity::import(
        data, &mut rng,
    )?))
}

#[bridge_fn]
fn MeshIdentity_Export(identity: &MeshIdentity) -> Result<Vec<u8>> {
    identity.0.export()
}

/// Recovers the identity from a passphrase-encrypted backup made by
/// `MeshNode_ExportBackup`; the node is then created from it and the rest of
/// the backup merged with `MeshNode_ImportBackup`.
#[bridge_fn]
fn MeshIdentity_FromBackup(passphrase: String, blob: &[u8]) -> Result<MeshIdentity> {
    let mut rng = rand::rngs::OsRng.unwrap_err();
    Ok(MeshIdentity(meshlink::MeshIdentity::from_backup(
        &passphrase,
        blob,
        &mut rng,
    )?))
}

#[bridge_fn]
fn MeshIdentity_Fingerprint(identity: &MeshIdentity) -> Vec<u8> {
    identity.0.fingerprint().to_vec()
}

#[bridge_fn]
fn MeshIdentity_Card(identity: &MeshIdentity) -> Vec<u8> {
    identity.0.card().encode()
}

#[bridge_fn]
fn MeshIdentity_SignedPreKeyRecord(identity: &MeshIdentity) -> SignedPreKeyRecord {
    identity.0.signed_pre_key_record().clone()
}

#[bridge_fn]
fn MeshIdentity_KyberPreKeyRecord(identity: &MeshIdentity) -> KyberPreKeyRecord {
    identity.0.kyber_pre_key_record().clone()
}

// ---- Contact cards --------------------------------------------------------

#[bridge_fn]
fn MeshContactCard_Decode(data: &[u8]) -> Result<MeshContactCard> {
    Ok(MeshContactCard(meshlink::ContactCard::decode(data)?))
}

#[bridge_fn]
fn MeshContactCard_FromBase64(text: String) -> Result<MeshContactCard> {
    Ok(MeshContactCard(meshlink::ContactCard::from_base64(&text)?))
}

#[bridge_fn]
fn MeshContactCard_Encode(card: &MeshContactCard) -> Vec<u8> {
    card.0.encode()
}

#[bridge_fn]
fn MeshContactCard_ToBase64(card: &MeshContactCard) -> String {
    card.0.to_base64()
}

#[bridge_fn]
fn MeshContactCard_Fingerprint(card: &MeshContactCard) -> Vec<u8> {
    card.0.fingerprint().to_vec()
}

#[bridge_fn]
fn MeshContactCard_Name(card: &MeshContactCard) -> String {
    card.0.name.clone()
}

#[bridge_fn]
fn MeshContactCard_RegistrationId(card: &MeshContactCard) -> u32 {
    card.0.registration_id
}

#[bridge_fn]
fn MeshContactCard_DeviceId(card: &MeshContactCard) -> u32 {
    card.0.device_id as u32
}

#[bridge_fn]
fn MeshContactCard_CreatedAt(card: &MeshContactCard) -> u64 {
    card.0.created_at
}

#[bridge_fn]
fn MeshContactCard_IdentityKey(card: &MeshContactCard) -> PublicKey {
    *card.0.identity_key.public_key()
}

/// The libsignal prekey bundle to start a session with this contact using
/// the app's own session builder.
#[bridge_fn]
fn MeshContactCard_PreKeyBundle(card: &MeshContactCard) -> Result<PreKeyBundle> {
    card.0.to_pre_key_bundle()
}

/// The `ProtocolAddress` name meshlink uses for this contact (its fingerprint
/// in hex); the app's session for the contact must be stored under it.
#[bridge_fn]
fn MeshContactCard_AddressName(card: &MeshContactCard) -> String {
    meshlink::bundle::fingerprint_hex(&card.0.fingerprint())
}

#[bridge_fn]
fn MeshContactCard_SafetyNumber(
    mine: &MeshContactCard,
    theirs: &MeshContactCard,
) -> Result<String> {
    meshlink::safety_number(&mine.0, &theirs.0)
}

// ---- Node -----------------------------------------------------------------

/// `state_path` empty/null: nothing persists. `external_crypto`: the app
/// encrypts and decrypts (the normal mode for the apps).
#[bridge_fn]
fn MeshNode_New(
    identity: &MeshIdentity,
    state_path: Option<String>,
    external_crypto: bool,
    anti_entropy_secs: u32,
) -> Result<MeshNode> {
    let path = state_path.filter(|p| !p.is_empty());
    MeshNode::new(&identity.0, path, external_crypto, anti_entropy_secs)
}

#[bridge_fn]
fn MeshNode_Fingerprint(node: &MeshNode) -> Vec<u8> {
    node.fingerprint().to_vec()
}

#[bridge_fn]
fn MeshNode_Card(node: &MeshNode) -> Vec<u8> {
    node.card()
}

#[bridge_fn]
fn MeshNode_AttachLink(
    node: &MeshNode,
    mtu: u32,
    max_bytes_per_sec: u32,
    max_frames_per_sec: u32,
) -> u64 {
    node.attach_link(mtu, max_bytes_per_sec, max_frames_per_sec)
}

#[bridge_fn]
fn MeshNode_DetachLink(node: &MeshNode, link: u64) {
    node.detach_link(link)
}

/// A frame received from the wire on `link`.
#[bridge_fn]
fn MeshNode_LinkWrite(node: &MeshNode, link: u64, frame: &[u8]) -> bool {
    node.link_write(link, frame)
}

/// The next frame to transmit on `link`, or empty after `timeout_ms`.
#[bridge_fn]
fn MeshNode_LinkRead(node: &MeshNode, link: u64, timeout_ms: u32) -> Vec<u8> {
    node.link_read(link, timeout_ms)
}

/// The next encoded event, or empty after `timeout_ms`.
#[bridge_fn]
fn MeshNode_NextEvent(node: &MeshNode, timeout_ms: u32) -> Vec<u8> {
    node.next_event(timeout_ms)
}

#[bridge_fn]
fn MeshNode_AddContact(node: &MeshNode, card: &[u8]) -> Result<Vec<u8>> {
    Ok(node.add_contact(card)?.to_vec())
}

#[bridge_fn]
fn MeshNode_RemoveContact(node: &MeshNode, fingerprint: &[u8]) -> Result<bool> {
    Ok(node.remove_contact(sixteen(fingerprint, "fingerprint must be 16 bytes")?))
}

/// The contact's encoded card, or empty if unknown.
#[bridge_fn]
fn MeshNode_Contact(node: &MeshNode, fingerprint: &[u8]) -> Result<Vec<u8>> {
    Ok(node.contact(sixteen(fingerprint, "fingerprint must be 16 bytes")?))
}

#[bridge_fn]
fn MeshNode_Contacts(node: &MeshNode) -> Box<[Vec<u8>]> {
    node.contacts().into_boxed_slice()
}

#[bridge_fn]
fn MeshNode_SafetyNumber(node: &MeshNode, fingerprint: &[u8]) -> Result<String> {
    node.safety_number(sixteen(fingerprint, "fingerprint must be 16 bytes")?)
}

#[bridge_fn]
fn MeshNode_Rename(node: &MeshNode, name: String) -> Result<()> {
    node.rename(&name)
}

#[bridge_fn]
fn MeshNode_BroadcastCard(node: &MeshNode) -> Result<Vec<u8>> {
    Ok(node.broadcast_card()?.to_vec())
}

/// Internal-crypto mode only.
#[bridge_fn]
fn MeshNode_SendText(node: &MeshNode, to: &[u8], plaintext: &[u8]) -> Result<Vec<u8>> {
    Ok(node
        .send_text(sixteen(to, "fingerprint must be 16 bytes")?, plaintext)?
        .to_vec())
}

/// External-crypto mode: encoded prepared list (one item).
#[bridge_fn]
fn MeshNode_PrepareText(node: &MeshNode, to: &[u8], plaintext: &[u8]) -> Result<Vec<u8>> {
    node.prepare_text(sixteen(to, "fingerprint must be 16 bytes")?, plaintext)
}

/// External-crypto mode: `message_type` is the libsignal ciphertext type.
#[bridge_fn]
fn MeshNode_SendCiphertext(
    node: &MeshNode,
    to: &[u8],
    commit: &[u8],
    message_type: u32,
    ciphertext: &[u8],
) -> Result<Vec<u8>> {
    let message_type =
        u8::try_from(message_type).map_err(|_| meshlink::Error::Wire("bad message type"))?;
    Ok(node
        .send_ciphertext(
            sixteen(to, "fingerprint must be 16 bytes")?,
            sixteen(commit, "commitment must be 16 bytes")?,
            message_type,
            ciphertext,
        )?
        .to_vec())
}

#[bridge_fn]
fn MeshNode_DeliverPlaintext(node: &MeshNode, bundle_id: &[u8], plaintext: &[u8]) -> Result<()> {
    node.deliver_plaintext(sixteen(bundle_id, "bundle id must be 16 bytes")?, plaintext)
}

#[bridge_fn]
fn MeshNode_Defer(node: &MeshNode, bundle_id: &[u8]) -> Result<()> {
    node.defer(sixteen(bundle_id, "bundle id must be 16 bytes")?)
}

/// `members`: concatenated 16-byte fingerprints. Internal-crypto mode.
#[bridge_fn]
fn MeshNode_CreateGroup(node: &MeshNode, name: String, members: &[u8]) -> Result<Vec<u8>> {
    Ok(node
        .create_group(
            &name,
            sixteens(members, "members must be 16-byte fingerprints")?,
        )?
        .to_vec())
}

/// External-crypto mode: `[group id 16][prepared list]`.
#[bridge_fn]
fn MeshNode_PrepareGroupCreate(node: &MeshNode, name: String, members: &[u8]) -> Result<Vec<u8>> {
    node.prepare_group_create(
        &name,
        sixteens(members, "members must be 16-byte fingerprints")?,
    )
}

/// Internal-crypto mode: concatenated bundle ids.
#[bridge_fn]
fn MeshNode_SendGroupText(node: &MeshNode, group: &[u8], plaintext: &[u8]) -> Result<Vec<u8>> {
    node.send_group_text(sixteen(group, "group id must be 16 bytes")?, plaintext)
}

/// External-crypto mode: prepared list.
#[bridge_fn]
fn MeshNode_PrepareGroupText(node: &MeshNode, group: &[u8], plaintext: &[u8]) -> Result<Vec<u8>> {
    node.prepare_group_text(sixteen(group, "group id must be 16 bytes")?, plaintext)
}

#[bridge_fn]
fn MeshNode_Groups(node: &MeshNode) -> Box<[Vec<u8>]> {
    node.groups().into_boxed_slice()
}

#[bridge_fn]
fn MeshNode_Group(node: &MeshNode, group: &[u8]) -> Result<Vec<u8>> {
    Ok(node.group(sixteen(group, "group id must be 16 bytes")?))
}

#[bridge_fn]
fn MeshNode_Stats(node: &MeshNode) -> Vec<u8> {
    node.stats()
}

#[bridge_fn]
fn MeshNode_Flush(node: &MeshNode) -> Result<()> {
    node.flush()
}

/// External-crypto mode: prepared list (manifest first, then the chunks; the
/// app encrypts and sends every entry in order). `kind`: 1 file, 2 image,
/// 3 voice note. `data` at most 4 MiB.
#[bridge_fn]
fn MeshNode_PrepareAttachment(
    node: &MeshNode,
    to: &[u8],
    kind: u8,
    name: String,
    mime: String,
    data: &[u8],
) -> Result<Vec<u8>> {
    node.prepare_attachment(
        sixteen(to, "fingerprint must be 16 bytes")?,
        kind,
        &name,
        &mime,
        data,
    )
}

/// Internal-crypto mode: concatenated bundle ids, the manifest's first.
#[bridge_fn]
fn MeshNode_SendAttachment(
    node: &MeshNode,
    to: &[u8],
    kind: u8,
    name: String,
    mime: String,
    data: &[u8],
) -> Result<Vec<u8>> {
    node.send_attachment(
        sixteen(to, "fingerprint must be 16 bytes")?,
        kind,
        &name,
        &mime,
        data,
    )
}

/// External-crypto mode: prepared list (one item) for an opaque call
/// signalling message; sent with a 90 s lifetime ahead of other traffic.
#[bridge_fn]
fn MeshNode_PrepareCallSignal(node: &MeshNode, to: &[u8], data: &[u8]) -> Result<Vec<u8>> {
    node.prepare_call_signal(sixteen(to, "fingerprint must be 16 bytes")?, data)
}

/// Internal-crypto mode.
#[bridge_fn]
fn MeshNode_SendCallSignal(node: &MeshNode, to: &[u8], data: &[u8]) -> Result<Vec<u8>> {
    Ok(node
        .send_call_signal(sixteen(to, "fingerprint must be 16 bytes")?, data)?
        .to_vec())
}

/// Everyone seen on the mesh in the last day: `u16 count` then per entry
/// `[fingerprint 16][name u16-len][lastSeenSecs u64][direct u8]`, most
/// recent first.
#[bridge_fn]
fn MeshNode_Nearby(node: &MeshNode) -> Vec<u8> {
    node.nearby()
}

/// Identity plus the whole mesh state, sealed under `passphrase`
/// (`"ASHB"`, version, salt, nonce, AES-256-GCM-SIV ciphertext; Argon2id key).
#[bridge_fn]
fn MeshNode_ExportBackup(node: &MeshNode, passphrase: String) -> Result<Vec<u8>> {
    node.export_backup(&passphrase)
}

/// Merges a backup of this same identity into the running node.
#[bridge_fn]
fn MeshNode_ImportBackup(node: &MeshNode, passphrase: String, blob: &[u8]) -> Result<()> {
    node.import_backup(&passphrase, blob)
}

/// Loopback end-to-end test against throwaway in-process peers; returns a
/// multi-line "PASS ..."/"FAIL ..." report and never throws for a failure.
#[bridge_fn]
fn MeshNode_SelfTest(node: &MeshNode, timeout_ms: u32) -> String {
    node.self_test(timeout_ms)
}
