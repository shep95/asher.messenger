//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Bridge-side wrappers for meshlink: a node that owns its own small tokio
//! runtime and exposes blocking, poll-style calls (the apps drive it from a
//! background thread), plus identity and contact-card handles.
//!
//! Events, prepared plaintexts, the nearby list and statistics cross the
//! bridge as compact byte strings encoded with meshlink's wire writer; the
//! platform layers decode them (see `docs/offline-mesh.md`, "Bridge").

use std::collections::HashMap;
use std::panic::RefUnwindSafe;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use meshlink::bundle::{AckCommitment, BundleId, Fingerprint};
use meshlink::wire::Writer;
use meshlink::{
    AttachmentKind, ContactCard, Event, FilePersistence, GroupId, LinkId, LinkOptions, Node,
    NodeConfig, Prepared, Result, Stats,
};
use tokio::sync::{broadcast, mpsc};

use crate::*;

pub struct MeshIdentity(pub meshlink::MeshIdentity);
pub struct MeshContactCard(pub ContactCard);

impl RefUnwindSafe for MeshIdentity {}
impl RefUnwindSafe for MeshContactCard {}

struct Link {
    inbound: mpsc::Sender<Vec<u8>>,
    outbound: Mutex<mpsc::Receiver<Vec<u8>>>,
}

pub struct MeshNode {
    runtime: tokio::runtime::Runtime,
    node: Node,
    events: Mutex<broadcast::Receiver<Event>>,
    links: Mutex<HashMap<LinkId, Arc<Link>>>,
}

impl RefUnwindSafe for MeshNode {}

/// Turns a 16-byte slice into a fixed array or fails with a wire error.
pub fn sixteen(bytes: &[u8], what: &'static str) -> Result<[u8; 16]> {
    bytes.try_into().map_err(|_| meshlink::Error::Wire(what))
}

/// Splits a concatenation of 16-byte ids.
pub fn sixteens(bytes: &[u8], what: &'static str) -> Result<Vec<[u8; 16]>> {
    if !bytes.len().is_multiple_of(16) {
        return Err(meshlink::Error::Wire(what));
    }
    Ok(bytes
        .chunks(16)
        .map(|c| c.try_into().expect("16 bytes"))
        .collect())
}

impl MeshNode {
    pub fn new(
        identity: &meshlink::MeshIdentity,
        state_path: Option<String>,
        external_crypto: bool,
        anti_entropy_secs: u32,
    ) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("meshlink")
            .enable_all()
            .build()?;
        let mut config = NodeConfig::default();
        if anti_entropy_secs > 0 {
            config.anti_entropy_interval = Duration::from_secs(anti_entropy_secs as u64);
        }
        let mut builder = Node::builder(identity.clone()).config(config);
        if external_crypto {
            builder = builder.external_crypto();
        }
        if let Some(path) = state_path {
            builder = builder.persistence(Box::new(FilePersistence::new(path)));
        }
        let node = {
            let _guard = runtime.enter();
            builder.start()?
        };
        let events = Mutex::new(node.subscribe());
        Ok(Self {
            runtime,
            node,
            events,
            links: Mutex::new(HashMap::new()),
        })
    }

    fn block<F: std::future::Future>(&self, fut: F) -> F::Output {
        self.runtime.block_on(fut)
    }

    pub fn fingerprint(&self) -> Fingerprint {
        self.block(self.node.fingerprint())
    }

    pub fn card(&self) -> Vec<u8> {
        self.block(self.node.card()).encode()
    }

    pub fn attach_link(&self, mtu: u32, max_bytes_per_sec: u32, max_frames_per_sec: u32) -> u64 {
        let options = LinkOptions {
            mtu: mtu as usize,
            max_bytes_per_sec: max_bytes_per_sec as usize,
            max_frames_per_sec: max_frames_per_sec as usize,
        };
        let endpoint = {
            let _guard = self.runtime.enter();
            self.node.attach_link_with(options)
        };
        let id = endpoint.id;
        self.links.lock().expect("not poisoned").insert(
            id,
            Arc::new(Link {
                inbound: endpoint.inbound,
                outbound: Mutex::new(endpoint.outbound),
            }),
        );
        id
    }

    pub fn detach_link(&self, id: u64) {
        self.links.lock().expect("not poisoned").remove(&id);
        let _guard = self.runtime.enter();
        self.node.detach_link(id);
    }

    fn link(&self, id: u64) -> Option<Arc<Link>> {
        self.links.lock().expect("not poisoned").get(&id).cloned()
    }

    /// A frame arrived from the wire. Returns false if the link is gone or
    /// the node could not take it within a second (back-pressure).
    pub fn link_write(&self, id: u64, frame: &[u8]) -> bool {
        let Some(link) = self.link(id) else {
            return false;
        };
        let frame = frame.to_vec();
        self.block(async move {
            tokio::time::timeout(Duration::from_secs(1), link.inbound.send(frame))
                .await
                .map(|r| r.is_ok())
                .unwrap_or(false)
        })
    }

    /// The next frame to put on the wire, waiting up to `timeout_ms`.
    /// Empty when nothing is ready or the link is gone.
    pub fn link_read(&self, id: u64, timeout_ms: u32) -> Vec<u8> {
        let Some(link) = self.link(id) else {
            return Vec::new();
        };
        let mut rx = link.outbound.lock().expect("not poisoned");
        self.block(async {
            tokio::time::timeout(Duration::from_millis(timeout_ms as u64), rx.recv())
                .await
                .ok()
                .flatten()
                .unwrap_or_default()
        })
    }

    /// The next event, encoded; empty after `timeout_ms` with nothing new.
    pub fn next_event(&self, timeout_ms: u32) -> Vec<u8> {
        let mut rx = self.events.lock().expect("not poisoned");
        self.block(async {
            let deadline = tokio::time::Instant::now() + Duration::from_millis(timeout_ms as u64);
            loop {
                match tokio::time::timeout_at(deadline, rx.recv()).await {
                    Ok(Ok(event)) => return encode_event(&event),
                    Ok(Err(broadcast::error::RecvError::Lagged(_))) => continue,
                    Ok(Err(broadcast::error::RecvError::Closed)) | Err(_) => return Vec::new(),
                }
            }
        })
    }

    pub fn add_contact(&self, card: &[u8]) -> Result<Fingerprint> {
        let card = ContactCard::decode(card)?;
        self.block(self.node.add_contact(card))
    }

    pub fn remove_contact(&self, fp: Fingerprint) -> bool {
        self.block(self.node.remove_contact(fp))
    }

    pub fn contact(&self, fp: Fingerprint) -> Vec<u8> {
        self.block(self.node.contact(fp))
            .map(|c| c.encode())
            .unwrap_or_default()
    }

    pub fn contacts(&self) -> Vec<Vec<u8>> {
        self.block(self.node.contacts())
            .into_iter()
            .map(|fp| fp.to_vec())
            .collect()
    }

    pub fn safety_number(&self, fp: Fingerprint) -> Result<String> {
        self.block(self.node.safety_number(fp))
    }

    pub fn rename(&self, name: &str) -> Result<()> {
        self.block(self.node.rename(name))
    }

    pub fn broadcast_card(&self) -> Result<BundleId> {
        self.block(self.node.broadcast_card())
    }

    pub fn send_text(&self, to: Fingerprint, plaintext: &[u8]) -> Result<BundleId> {
        self.block(self.node.send_text(to, plaintext))
    }

    pub fn prepare_text(&self, to: Fingerprint, plaintext: &[u8]) -> Result<Vec<u8>> {
        let p = self.block(self.node.prepare_text(to, plaintext))?;
        Ok(encode_prepared(&[p]))
    }

    pub fn send_ciphertext(
        &self,
        to: Fingerprint,
        commit: AckCommitment,
        message_type: u8,
        ciphertext: &[u8],
    ) -> Result<BundleId> {
        self.block(
            self.node
                .send_ciphertext(to, commit, message_type, ciphertext),
        )
    }

    pub fn deliver_plaintext(&self, id: BundleId, plaintext: &[u8]) -> Result<()> {
        self.block(self.node.deliver_plaintext(id, plaintext))
    }

    pub fn defer(&self, id: BundleId) -> Result<()> {
        self.block(self.node.defer(id))
    }

    pub fn create_group(&self, name: &str, members: Vec<Fingerprint>) -> Result<GroupId> {
        self.block(self.node.create_group(name, members))
    }

    /// `[group id 16][prepared list]`.
    pub fn prepare_group_create(&self, name: &str, members: Vec<Fingerprint>) -> Result<Vec<u8>> {
        let (gid, prepared) = self.block(self.node.prepare_group_create(name, members))?;
        let mut out = gid.to_vec();
        out.extend(encode_prepared(&prepared));
        Ok(out)
    }

    /// Concatenated bundle ids.
    pub fn send_group_text(&self, group: GroupId, plaintext: &[u8]) -> Result<Vec<u8>> {
        let ids = self.block(self.node.send_group_text(group, plaintext))?;
        Ok(ids.concat())
    }

    pub fn prepare_group_text(&self, group: GroupId, plaintext: &[u8]) -> Result<Vec<u8>> {
        let prepared = self.block(self.node.prepare_group_text(group, plaintext))?;
        Ok(encode_prepared(&prepared))
    }

    pub fn groups(&self) -> Vec<Vec<u8>> {
        self.block(self.node.groups())
            .into_iter()
            .map(|g| g.encode())
            .collect()
    }

    pub fn group(&self, id: GroupId) -> Vec<u8> {
        self.block(self.node.group(id))
            .map(|g| g.encode())
            .unwrap_or_default()
    }

    pub fn stats(&self) -> Vec<u8> {
        encode_stats(&self.block(self.node.stats()))
    }

    pub fn flush(&self) -> Result<()> {
        self.block(self.node.flush())
    }

    /// External-crypto mode: prepared list, manifest first then the chunks.
    pub fn prepare_attachment(
        &self,
        to: Fingerprint,
        kind: u8,
        name: &str,
        mime: &str,
        data: &[u8],
    ) -> Result<Vec<u8>> {
        let kind = AttachmentKind::try_from(kind)?;
        let prepared = self.block(self.node.prepare_attachment(to, kind, name, mime, data))?;
        Ok(encode_prepared(&prepared))
    }

    /// Internal-crypto mode: concatenated bundle ids, the manifest's first.
    pub fn send_attachment(
        &self,
        to: Fingerprint,
        kind: u8,
        name: &str,
        mime: &str,
        data: &[u8],
    ) -> Result<Vec<u8>> {
        let kind = AttachmentKind::try_from(kind)?;
        let ids = self.block(self.node.send_attachment(to, kind, name, mime, data))?;
        Ok(ids.concat())
    }

    /// External-crypto mode: prepared list with one item.
    pub fn prepare_call_signal(&self, to: Fingerprint, data: &[u8]) -> Result<Vec<u8>> {
        let p = self.block(self.node.prepare_call_signal(to, data))?;
        Ok(encode_prepared(&[p]))
    }

    pub fn send_call_signal(&self, to: Fingerprint, data: &[u8]) -> Result<BundleId> {
        self.block(self.node.send_call_signal(to, data))
    }

    pub fn nearby(&self) -> Vec<u8> {
        meshlink::nearby::encode_nearby(&self.block(self.node.nearby()))
    }

    pub fn export_backup(&self, passphrase: &str) -> Result<Vec<u8>> {
        self.block(self.node.export_backup(passphrase))
    }

    pub fn import_backup(&self, passphrase: &str, blob: &[u8]) -> Result<()> {
        self.block(self.node.import_backup(passphrase, blob))
    }

    pub fn self_test(&self, timeout_ms: u32) -> String {
        self.block(
            self.node
                .self_test(Duration::from_millis(u64::from(timeout_ms))),
        )
    }
}

/// Event tags on the bridge.
pub mod event_tag {
    pub const CIPHERTEXT: u8 = 1;
    pub const MESSAGE: u8 = 2;
    pub const GROUP_MESSAGE: u8 = 3;
    pub const GROUP_INVITE: u8 = 4;
    pub const CONTACT: u8 = 5;
    pub const DELIVERED: u8 = 6;
    pub const NEIGHBOUR: u8 = 7;
    pub const LINK_CLOSED: u8 = 8;
    pub const ATTACHMENT_PROGRESS: u8 = 9;
    pub const ATTACHMENT: u8 = 10;
    pub const CALL_SIGNAL: u8 = 11;
}

/// `[tag u8]` then tag-specific fixed fields; variable fields are u16
/// length-prefixed.
pub fn encode_event(event: &Event) -> Vec<u8> {
    let mut w = Writer::new();
    match event {
        Event::Ciphertext {
            from,
            bundle_id,
            ack_commit,
            message_type,
            ciphertext,
        } => {
            w.u8(event_tag::CIPHERTEXT)
                .fixed(from)
                .fixed(bundle_id)
                .fixed(ack_commit)
                .u8(*message_type)
                .bytes(ciphertext);
        }
        Event::Message {
            from,
            bundle_id,
            plaintext,
            known_sender,
        } => {
            w.u8(event_tag::MESSAGE)
                .fixed(from)
                .fixed(bundle_id)
                .u8(*known_sender as u8)
                .bytes(plaintext);
        }
        Event::GroupMessage {
            group,
            from,
            bundle_id,
            plaintext,
        } => {
            w.u8(event_tag::GROUP_MESSAGE)
                .fixed(group)
                .fixed(from)
                .fixed(bundle_id)
                .bytes(plaintext);
        }
        Event::GroupInvite { group, from } => {
            w.u8(event_tag::GROUP_INVITE).fixed(group).fixed(from);
        }
        Event::Contact { fingerprint } => {
            w.u8(event_tag::CONTACT).fixed(fingerprint);
        }
        Event::Delivered { bundle_id } => {
            w.u8(event_tag::DELIVERED).fixed(bundle_id);
        }
        Event::Neighbour { link, fingerprint } => {
            w.u8(event_tag::NEIGHBOUR).u64(*link).fixed(fingerprint);
        }
        Event::LinkClosed { link } => {
            w.u8(event_tag::LINK_CLOSED).u64(*link);
        }
        Event::AttachmentProgress {
            from,
            transfer,
            received,
            total,
        } => {
            w.u8(event_tag::ATTACHMENT_PROGRESS)
                .fixed(from)
                .fixed(transfer)
                .u32(*received)
                .u32(*total);
        }
        Event::Attachment {
            from,
            transfer,
            kind,
            name,
            mime,
            data,
        } => {
            w.u8(event_tag::ATTACHMENT)
                .fixed(from)
                .fixed(transfer)
                .u8(*kind)
                .bytes(name.as_bytes())
                .bytes(mime.as_bytes())
                .u32(u32::try_from(data.len()).expect("bounded by MAX_ATTACHMENT_BYTES"))
                .fixed(data);
        }
        Event::CallSignal {
            from,
            bundle_id,
            data,
        } => {
            w.u8(event_tag::CALL_SIGNAL)
                .fixed(from)
                .fixed(bundle_id)
                .bytes(data);
        }
    }
    w.finish()
}

/// `[count u16]` then per item `[to 16][commit 16][plaintext u16-len]`.
pub fn encode_prepared(items: &[Prepared]) -> Vec<u8> {
    let mut w = Writer::new();
    w.u16(items.len() as u16);
    for p in items {
        w.fixed(&p.to).fixed(&p.commit).bytes(&p.plaintext);
    }
    w.finish()
}

/// Eighteen big-endian u64 counters in the order of the `Stats` fields.
pub fn encode_stats(s: &Stats) -> Vec<u8> {
    let mut w = Writer::new();
    for v in [
        s.frames_in,
        s.frames_dropped_rate,
        s.frames_dropped_invalid,
        s.bundles_in,
        s.bundles_dropped_invalid,
        s.bundles_dropped_rate,
        s.bundles_dropped_quota,
        s.bundles_forwarded,
        s.messages_delivered,
        s.messages_undecryptable,
        s.messages_deferred as u64,
        s.acks_rejected,
        s.acks_verified,
        s.bytes_out,
        s.links as u64,
        s.store_bundles as u64,
        s.store_bytes as u64,
        s.outstanding as u64,
    ] {
        w.u64(v);
    }
    w.finish()
}

bridge_as_handle!(MeshIdentity);
bridge_as_handle!(MeshContactCard);
bridge_as_handle!(MeshNode);
