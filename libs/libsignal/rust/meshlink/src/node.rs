//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! A meshlink node: the carry store, the neighbour protocol on every attached
//! link, and the Signal sessions that turn plaintext into bundles and back.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use libsignal_protocol::{
    CiphertextMessage, CiphertextMessageType, PreKeySignalMessage, SessionStore as _,
    SignalMessage, message_decrypt, message_encrypt, process_prekey_bundle,
};
use log::{debug, info, warn};
use rand::TryRngCore as _;
use tokio::sync::{Mutex, broadcast, mpsc};

use crate::bundle::{BROADCAST, Bundle, BundleId, BundleKind, Fingerprint, fingerprint_hex};
use crate::frame::{Frame, Reassembler, chunk_ids, fragment_bundle};
use crate::identity::{ContactCard, MeshIdentity};
use crate::store::BundleStore;
use crate::transport::LinkEndpoint;
use crate::{Error, Result};

pub type LinkId = u64;

#[derive(Clone, Debug)]
pub struct NodeConfig {
    /// Hop limit for messages.
    pub max_hops: u8,
    /// Lifetime of a message bundle in the carry store.
    pub message_ttl_secs: u32,
    /// Lifetime and hop limit of contact-card beacons.
    pub beacon_ttl_secs: u32,
    pub beacon_max_hops: u8,
    /// How often the background task re-runs the neighbour exchange.
    pub anti_entropy_interval: Duration,
    /// Byte budget of the carry store.
    pub store_bytes: usize,
    /// Per-link outbound queue depth (frames).
    pub link_queue: usize,
    /// How long incomplete fragment sets are kept.
    pub reassembly_timeout_secs: u64,
}

impl Default for NodeConfig {
    fn default() -> Self {
        Self {
            max_hops: 7,
            message_ttl_secs: 7 * 24 * 3600,
            beacon_ttl_secs: 24 * 3600,
            beacon_max_hops: 3,
            anti_entropy_interval: Duration::from_secs(15),
            store_bytes: 8 * 1024 * 1024,
            link_queue: 256,
            reassembly_timeout_secs: 120,
        }
    }
}

/// What a node reports to the application.
#[derive(Clone, Debug)]
pub enum Event {
    /// A message for us was decrypted.
    Message {
        from: Fingerprint,
        bundle_id: BundleId,
        plaintext: Vec<u8>,
    },
    /// A contact card was learned (from a beacon or an explicit add).
    Contact { fingerprint: Fingerprint },
    /// A message we sent was acknowledged by its recipient.
    Delivered { bundle_id: BundleId },
    /// A neighbour identified itself on a link.
    Neighbour {
        link: LinkId,
        fingerprint: Fingerprint,
    },
}

struct LinkState {
    tx: mpsc::Sender<Vec<u8>>,
    mtu: usize,
    peer: Option<Fingerprint>,
    reassembler: Reassembler,
    /// Ids this neighbour has told us it holds (so we do not push them back).
    known: HashSet<BundleId>,
}

struct Inner {
    identity: MeshIdentity,
    config: NodeConfig,
    store: BundleStore,
    contacts: HashMap<Fingerprint, ContactCard>,
    links: HashMap<LinkId, LinkState>,
    next_link: LinkId,
    /// Ids of our own outbound messages awaiting an acknowledgement.
    outstanding: HashSet<BundleId>,
}

/// Cheaply clonable handle to a running node.
#[derive(Clone)]
pub struct Node {
    inner: Arc<Mutex<Inner>>,
    events: broadcast::Sender<Event>,
}

impl Node {
    /// Starts a node. The returned handle owns a background task that runs
    /// expiry and the periodic neighbour exchange.
    pub fn start(identity: MeshIdentity, config: NodeConfig) -> Self {
        let (events, _) = broadcast::channel(256);
        let store = BundleStore::new(config.store_bytes);
        let interval = config.anti_entropy_interval;
        let node = Self {
            inner: Arc::new(Mutex::new(Inner {
                identity,
                config,
                store,
                contacts: HashMap::new(),
                links: HashMap::new(),
                next_link: 1,
                outstanding: HashSet::new(),
            })),
            events,
        };
        let weak = Arc::downgrade(&node.inner);
        let events = node.events.clone();
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            loop {
                ticker.tick().await;
                let Some(inner) = weak.upgrade() else { break };
                let node = Node {
                    inner,
                    events: events.clone(),
                };
                node.tick(crate::now_secs()).await;
            }
        });
        node
    }

    pub async fn fingerprint(&self) -> Fingerprint {
        self.inner.lock().await.identity.fingerprint()
    }

    pub async fn card(&self) -> ContactCard {
        self.inner.lock().await.identity.card().clone()
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Event> {
        self.events.subscribe()
    }

    /// Learns a contact from a card obtained out of band (QR code, paste).
    pub async fn add_contact(&self, card: ContactCard) -> Result<Fingerprint> {
        card.verify()?;
        let fp = card.fingerprint();
        let mut inner = self.inner.lock().await;
        inner.contacts.insert(fp, card);
        drop(inner);
        let _ = self.events.send(Event::Contact { fingerprint: fp });
        Ok(fp)
    }

    pub async fn contacts(&self) -> Vec<Fingerprint> {
        self.inner.lock().await.contacts.keys().copied().collect()
    }

    pub async fn store_len(&self) -> usize {
        self.inner.lock().await.store.len()
    }

    /// Attaches a link with the given frame MTU. The transport moves bytes
    /// between the returned endpoint and the wire.
    pub fn attach_link(&self, mtu: usize) -> LinkEndpoint {
        let (to_wire_tx, to_wire_rx) = mpsc::channel::<Vec<u8>>(256);
        let (from_wire_tx, mut from_wire_rx) = mpsc::channel::<Vec<u8>>(256);
        let id = {
            // attach_link is synchronous so transports can call it from
            // non-async contexts; the lock is uncontended at this point.
            let mut inner = self.inner.blocking_lock_or_spin();
            let id = inner.next_link;
            inner.next_link += 1;
            let timeout = inner.config.reassembly_timeout_secs;
            inner.links.insert(
                id,
                LinkState {
                    tx: to_wire_tx,
                    mtu: mtu.max(crate::frame::MIN_MTU),
                    peer: None,
                    reassembler: Reassembler::new(timeout),
                    known: HashSet::new(),
                },
            );
            id
        };
        let node = self.clone();
        tokio::spawn(async move {
            // Say hello and offer what we hold as soon as the link exists.
            node.on_link_up(id).await;
            while let Some(bytes) = from_wire_rx.recv().await {
                if let Err(e) = node.handle_frame(id, &bytes).await {
                    debug!("link {id}: dropping frame: {e}");
                }
            }
            node.detach_link(id);
        });
        LinkEndpoint {
            id,
            inbound: from_wire_tx,
            outbound: to_wire_rx,
        }
    }

    pub fn detach_link(&self, id: LinkId) {
        let mut inner = self.inner.blocking_lock_or_spin();
        inner.links.remove(&id);
    }

    /// Encrypts `plaintext` for `to` and hands the bundle to the mesh.
    /// Returns the bundle id, which is echoed in [`Event::Delivered`].
    pub async fn send_text(&self, to: Fingerprint, plaintext: &[u8]) -> Result<BundleId> {
        let mut rng = rand::rngs::OsRng.unwrap_err();
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        let card = inner
            .contacts
            .get(&to)
            .ok_or_else(|| Error::UnknownContact(fingerprint_hex(&to)))?
            .clone();
        let remote = card.address()?;
        let local = inner.identity.address();
        let store = &mut inner.identity.store;

        if crate::complete_now(store.session_store.load_session(&remote))?.is_none() {
            crate::complete_now(process_prekey_bundle(
                &remote,
                &local,
                &mut store.session_store,
                &mut store.identity_store,
                &card.to_pre_key_bundle()?,
                SystemTime::now(),
                &mut rng,
            ))?;
        }
        let ct = crate::complete_now(message_encrypt(
            plaintext,
            &remote,
            &local,
            &mut store.session_store,
            &mut store.identity_store,
            SystemTime::now(),
            &mut rng,
        ))?;
        let mut payload = Vec::with_capacity(ct.serialize().len() + 1);
        payload.push(ct.message_type() as u8);
        payload.extend_from_slice(ct.serialize());

        let bundle = Bundle::new(
            BundleKind::Message,
            inner.identity.fingerprint(),
            to,
            inner.config.message_ttl_secs,
            inner.config.max_hops,
            payload,
        )?;
        let id = bundle.id();
        inner.outstanding.insert(id);
        let now = crate::now_secs();
        inner.store.insert(bundle.clone(), now);
        Self::push_to_links(inner, &bundle, None);
        Ok(id)
    }

    /// Broadcasts our contact card so nearby nodes can message us without an
    /// out-of-band exchange.
    pub async fn broadcast_card(&self) -> Result<BundleId> {
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        let bundle = Bundle::new(
            BundleKind::Beacon,
            inner.identity.fingerprint(),
            BROADCAST,
            inner.config.beacon_ttl_secs,
            inner.config.beacon_max_hops,
            inner.identity.card().encode(),
        )?;
        let id = bundle.id();
        inner.store.insert(bundle.clone(), crate::now_secs());
        Self::push_to_links(inner, &bundle, None);
        Ok(id)
    }

    /// Runs expiry and re-offers the carry store to every neighbour. Called
    /// periodically by the background task; public so tests can drive it.
    pub async fn tick(&self, now: u64) {
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        let dropped = inner.store.expire(now);
        if dropped > 0 {
            debug!("expired {dropped} bundle(s)");
        }
        for link in inner.links.values_mut() {
            link.reassembler.expire(now);
        }
        let ids = inner.store.forwardable_ids(now);
        let links: Vec<LinkId> = inner.links.keys().copied().collect();
        for id in links {
            Self::send_summary(inner, id, &ids);
        }
    }

    async fn on_link_up(&self, id: LinkId) {
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        let hello = Frame::Hello {
            fingerprint: inner.identity.fingerprint(),
        };
        Self::send_frame(inner, id, &hello);
        let ids = inner.store.forwardable_ids(crate::now_secs());
        Self::send_summary(inner, id, &ids);
    }

    async fn handle_frame(&self, link_id: LinkId, bytes: &[u8]) -> Result<()> {
        let frame = Frame::decode(bytes)?;
        let now = crate::now_secs();
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        if !inner.links.contains_key(&link_id) {
            return Err(Error::LinkClosed(link_id));
        }
        match frame {
            Frame::Hello { fingerprint } => {
                if let Some(l) = inner.links.get_mut(&link_id) {
                    l.peer = Some(fingerprint);
                }
                let _ = self.events.send(Event::Neighbour {
                    link: link_id,
                    fingerprint,
                });
            }
            Frame::Summary { ids } => {
                let missing: Vec<BundleId> = ids
                    .iter()
                    .filter(|id| !inner.store.has_seen(id))
                    .copied()
                    .collect();
                if let Some(l) = inner.links.get_mut(&link_id) {
                    l.known.extend(ids.iter().copied());
                }
                if !missing.is_empty() {
                    let mtu = inner.links[&link_id].mtu;
                    for f in chunk_ids(&missing, mtu, true) {
                        Self::send_frame(inner, link_id, &f);
                    }
                }
            }
            Frame::Want { ids } => {
                for id in ids {
                    if let Some(b) = inner.store.get(&id).cloned() {
                        if b.is_forwardable(now) {
                            Self::send_bundle(inner, link_id, &b);
                        }
                    }
                }
            }
            Frame::Bundle(bundle) => {
                self.ingest(inner, bundle, Some(link_id), now)?;
            }
            Frame::Fragment {
                id,
                index,
                total,
                data,
            } => {
                let complete = inner
                    .links
                    .get_mut(&link_id)
                    .expect("checked above")
                    .reassembler
                    .push(id, index, total, data, now)?;
                if let Some(bundle) = complete {
                    self.ingest(inner, bundle, Some(link_id), now)?;
                }
            }
        }
        Ok(())
    }

    /// Accepts a bundle from a link: dedup, store, deliver if ours, forward
    /// onward.
    fn ingest(
        &self,
        inner: &mut Inner,
        mut bundle: Bundle,
        from: Option<LinkId>,
        now: u64,
    ) -> Result<()> {
        let id = bundle.id();
        if let Some(l) = from.and_then(|f| inner.links.get_mut(&f)) {
            l.known.insert(id);
        }
        if inner.store.has_seen(&id) || bundle.is_expired(now) {
            return Ok(());
        }
        // Count the hop we just travelled.
        if from.is_some() {
            bundle.hops = bundle.hops.saturating_add(1);
        }
        let me = inner.identity.fingerprint();

        match bundle.kind {
            BundleKind::Message if bundle.dst == me => {
                inner.store.mark_seen(id, now);
                match Self::decrypt_message(inner, &bundle) {
                    Ok(plaintext) => {
                        info!(
                            "delivered {} from {}",
                            hex::encode(id),
                            fingerprint_hex(&bundle.src)
                        );
                        let _ = self.events.send(Event::Message {
                            from: bundle.src,
                            bundle_id: id,
                            plaintext,
                        });
                        // Tell the network the bundle can be dropped.
                        let ack = Bundle::new(
                            BundleKind::Ack,
                            me,
                            BROADCAST,
                            inner.config.message_ttl_secs,
                            inner.config.max_hops,
                            id.to_vec(),
                        )?;
                        inner.store.insert(ack.clone(), now);
                        Self::push_to_links(inner, &ack, None);
                    }
                    Err(e) => warn!("could not decrypt {}: {e}", hex::encode(id)),
                }
                return Ok(());
            }
            BundleKind::Message => {
                // Not for us: carry it.
                if inner.store.insert(bundle.clone(), now) && bundle.is_forwardable(now) {
                    Self::push_to_links(inner, &bundle, from);
                }
            }
            BundleKind::Beacon => {
                if bundle.src != me {
                    match ContactCard::decode(&bundle.payload) {
                        Ok(card) if card.fingerprint() == bundle.src => {
                            let fp = card.fingerprint();
                            let is_new = inner
                                .contacts
                                .get(&fp)
                                .map(|old| old.created_at < card.created_at)
                                .unwrap_or(true);
                            if is_new {
                                inner.contacts.insert(fp, card);
                                let _ = self.events.send(Event::Contact { fingerprint: fp });
                            }
                        }
                        Ok(_) => warn!("beacon source does not match card; ignoring"),
                        Err(e) => warn!("bad beacon: {e}"),
                    }
                }
                if inner.store.insert(bundle.clone(), now) && bundle.is_forwardable(now) {
                    Self::push_to_links(inner, &bundle, from);
                }
            }
            BundleKind::Ack => {
                if bundle.payload.len() == 16 {
                    let acked: BundleId = bundle.payload[..16].try_into().expect("16 bytes");
                    inner.store.remove(&acked);
                    inner.store.mark_seen(acked, now);
                    if inner.outstanding.remove(&acked) {
                        let _ = self.events.send(Event::Delivered { bundle_id: acked });
                    }
                }
                if inner.store.insert(bundle.clone(), now) && bundle.is_forwardable(now) {
                    Self::push_to_links(inner, &bundle, from);
                }
            }
        }
        Ok(())
    }

    fn decrypt_message(inner: &mut Inner, bundle: &Bundle) -> Result<Vec<u8>> {
        let (type_byte, ct_bytes) = bundle
            .payload
            .split_first()
            .ok_or(Error::Wire("empty message payload"))?;
        let ciphertext = match *type_byte {
            t if t == CiphertextMessageType::PreKey as u8 => {
                CiphertextMessage::PreKeySignalMessage(PreKeySignalMessage::try_from(ct_bytes)?)
            }
            t if t == CiphertextMessageType::Whisper as u8 => {
                CiphertextMessage::SignalMessage(SignalMessage::try_from(ct_bytes)?)
            }
            _ => return Err(Error::Wire("unsupported ciphertext type")),
        };
        // The sender's device id is 1 unless we hold a card saying otherwise.
        let device_id = inner
            .contacts
            .get(&bundle.src)
            .map(|c| c.device_id)
            .unwrap_or(1);
        let remote = libsignal_protocol::ProtocolAddress::new(
            fingerprint_hex(&bundle.src),
            libsignal_protocol::DeviceId::new(device_id)
                .map_err(|_| Error::Wire("invalid device id"))?,
        );
        let local = inner.identity.address();
        let store = &mut inner.identity.store;
        let mut rng = rand::rngs::OsRng.unwrap_err();
        Ok(crate::complete_now(message_decrypt(
            &ciphertext,
            &remote,
            &local,
            &mut store.session_store,
            &mut store.identity_store,
            &mut store.pre_key_store,
            &store.signed_pre_key_store,
            &mut store.kyber_pre_key_store,
            &mut rng,
        ))?)
    }

    fn push_to_links(inner: &mut Inner, bundle: &Bundle, except: Option<LinkId>) {
        let id = bundle.id();
        let targets: Vec<LinkId> = inner
            .links
            .iter()
            .filter(|(lid, l)| Some(**lid) != except && !l.known.contains(&id))
            .map(|(lid, _)| *lid)
            .collect();
        for lid in targets {
            Self::send_bundle(inner, lid, bundle);
        }
    }

    fn send_bundle(inner: &mut Inner, link: LinkId, bundle: &Bundle) {
        let Some(l) = inner.links.get_mut(&link) else {
            return;
        };
        l.known.insert(bundle.id());
        match fragment_bundle(bundle, l.mtu) {
            Ok(frames) => {
                for f in frames {
                    Self::send_frame(inner, link, &f);
                }
            }
            Err(e) => warn!("cannot fragment bundle for link {link}: {e}"),
        }
    }

    fn send_summary(inner: &mut Inner, link: LinkId, ids: &[BundleId]) {
        if ids.is_empty() {
            return;
        }
        let Some(l) = inner.links.get(&link) else {
            return;
        };
        for f in chunk_ids(ids, l.mtu, false) {
            Self::send_frame(inner, link, &f);
        }
    }

    fn send_frame(inner: &mut Inner, link: LinkId, frame: &Frame) {
        if let Some(l) = inner.links.get(&link) {
            if l.tx.try_send(frame.encode()).is_err() {
                debug!("link {link}: outbound queue full or closed; frame dropped");
            }
        }
    }
}

/// Helper so synchronous callers (transports) can take the async mutex.
trait BlockingLock<T> {
    fn blocking_lock_or_spin(&self) -> tokio::sync::MutexGuard<'_, T>;
}

impl<T> BlockingLock<T> for Mutex<T> {
    fn blocking_lock_or_spin(&self) -> tokio::sync::MutexGuard<'_, T> {
        loop {
            if let Ok(g) = self.try_lock() {
                return g;
            }
            std::thread::yield_now();
        }
    }
}
