//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! A meshlink node: the carry store, the neighbour protocol on every attached
//! link, and the Signal sessions that turn plaintext into bundles and back.
//!
//! Everything a neighbour can send is bounded before it is trusted: frame
//! size and rate per link, bundle header sanity, bytes per source in the
//! carry store, half-assembled fragments per link, beacons per source,
//! decryption attempts per source, contacts learned from the air. An
//! acknowledgement is only honoured if it opens the commitment in the message
//! it claims to acknowledge, which only the recipient can do.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use libsignal_protocol::{
    CiphertextMessage, CiphertextMessageType, InMemSignalProtocolStore, PreKeySignalMessage,
    SignalMessage, message_decrypt, message_encrypt, process_prekey_bundle,
};
use log::{debug, info, warn};
use rand::TryRngCore as _;
use tokio::sync::{Mutex, broadcast, mpsc};

use crate::bundle::{
    AckCommitment, BROADCAST, Bundle, BundleId, BundleKind, Fingerprint, ack_commitment, ack_opens,
    ack_payload, fingerprint_hex, parse_ack,
};
use crate::envelope::{Envelope, EnvelopeKind};
use crate::frame::{Frame, Reassembler, chunk_ids, fragment_bundle};
use crate::group::{GroupId, MeshGroup};
use crate::identity::{ContactCard, MeshIdentity, safety_number};
use crate::limits::{Bucket, KeyedLimiter};
use crate::persist::{MeshPersistence, NoPersistence, Snapshot};
use crate::store::BundleStore;
use crate::stores::ProtocolStores;
use crate::transport::{LinkEndpoint, LinkOptions};
use crate::{Error, Result};

pub type LinkId = u64;

/// Largest frame accepted from a link before decoding.
pub const MAX_FRAME_LEN: usize = 8192;
/// Ids a link's "known" set may hold before it is reset.
const MAX_KNOWN_PER_LINK: usize = 8192;
/// Every this many ticks a node re-offers its whole store to each neighbour
/// (in between it only offers what the neighbour has not been told about),
/// so a frame lost on the air is recovered without constant chatter.
const FULL_SUMMARY_EVERY: u64 = 4;
/// Messages for us that could not be decrypted yet (their session-starting
/// message is still on its way) are kept for retry, this many per sender and
/// in total.
const MAX_DEFERRED_PER_SRC: usize = 32;
const MAX_DEFERRED: usize = 512;

#[derive(Clone, Debug)]
pub struct NodeConfig {
    /// Hop limit for messages we originate.
    pub max_hops: u8,
    /// Lifetime of a message bundle in the carry store.
    pub message_ttl_secs: u32,
    /// Lifetime of acknowledgements.
    pub ack_ttl_secs: u32,
    /// Lifetime and hop limit of contact-card beacons.
    pub beacon_ttl_secs: u32,
    pub beacon_max_hops: u8,
    /// How often the background task re-runs the neighbour exchange, expiry
    /// and persistence.
    pub anti_entropy_interval: Duration,
    /// Byte budget of the carry store.
    pub store_bytes: usize,
    /// Largest share of the carry store one foreign source may occupy.
    pub store_src_quota_bytes: usize,
    /// Per-link outbound queue depth (frames).
    pub link_queue: usize,
    /// How long incomplete fragment sets are kept.
    pub reassembly_timeout_secs: u64,
    /// New bundles accepted per source per second (sustained / burst).
    pub ingest_per_src: (f64, f64),
    /// Decryption attempts per source per second (sustained / burst).
    pub decrypt_per_src: (f64, f64),
    /// Beacons accepted per source per second (sustained / burst).
    pub beacons_per_src: (f64, f64),
    /// Most contacts learned from the air (explicit contacts are exempt).
    pub max_learned_contacts: usize,
    pub max_groups: usize,
    /// Deliver messages from senders whose card we do not hold (Signal's
    /// "message request" behaviour). The session is still authenticated.
    pub accept_unknown_senders: bool,
}

impl Default for NodeConfig {
    fn default() -> Self {
        Self {
            max_hops: 7,
            message_ttl_secs: 7 * 24 * 3600,
            ack_ttl_secs: 24 * 3600,
            beacon_ttl_secs: 24 * 3600,
            beacon_max_hops: 3,
            anti_entropy_interval: Duration::from_secs(15),
            store_bytes: 8 * 1024 * 1024,
            store_src_quota_bytes: 1024 * 1024,
            link_queue: 1024,
            reassembly_timeout_secs: 120,
            ingest_per_src: (10.0, 600.0),
            decrypt_per_src: (5.0, 200.0),
            beacons_per_src: (0.05, 3.0),
            max_learned_contacts: 10_000,
            max_groups: 1_000,
            accept_unknown_senders: true,
        }
    }
}

/// What a node reports to the application.
#[derive(Clone, Debug)]
pub enum Event {
    /// A one-to-one message for us was decrypted.
    Message {
        from: Fingerprint,
        bundle_id: BundleId,
        plaintext: Vec<u8>,
        /// Whether we hold the sender's card.
        known_sender: bool,
    },
    /// A group message for us was decrypted.
    GroupMessage {
        group: GroupId,
        from: Fingerprint,
        bundle_id: BundleId,
        plaintext: Vec<u8>,
    },
    /// We were added to a group.
    GroupInvite { group: GroupId, from: Fingerprint },
    /// A contact card was learned (from a beacon, a share or an explicit add).
    Contact { fingerprint: Fingerprint },
    /// A message we sent was acknowledged by its recipient.
    Delivered { bundle_id: BundleId },
    /// A neighbour identified itself on a link (unauthenticated; informational).
    Neighbour {
        link: LinkId,
        fingerprint: Fingerprint,
    },
    /// A link went away.
    LinkClosed { link: LinkId },
}

/// Counters for diagnostics and the scene indicator.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Stats {
    pub frames_in: u64,
    pub frames_dropped_rate: u64,
    pub frames_dropped_invalid: u64,
    pub bundles_in: u64,
    pub bundles_dropped_invalid: u64,
    pub bundles_dropped_rate: u64,
    pub bundles_dropped_quota: u64,
    pub bundles_forwarded: u64,
    pub messages_delivered: u64,
    pub messages_undecryptable: u64,
    /// Messages for us waiting for their session to arrive.
    pub messages_deferred: usize,
    pub acks_rejected: u64,
    pub acks_verified: u64,
    pub bytes_out: u64,
    pub links: usize,
    pub store_bundles: usize,
    pub store_bytes: usize,
    pub outstanding: usize,
}

struct LinkState {
    tx: mpsc::Sender<Vec<u8>>,
    mtu: usize,
    peer: Option<Fingerprint>,
    reassembler: Reassembler,
    /// Ids this neighbour has told us it holds (so we do not push them back).
    known: HashSet<BundleId>,
    out_bytes: Bucket,
    in_frames: Bucket,
}

struct Contact {
    card: ContactCard,
    pinned: bool,
    learned_at: u64,
}

struct Inner {
    identity: MeshIdentity,
    stores: Box<dyn ProtocolStores>,
    persistence: Box<dyn MeshPersistence>,
    dirty: bool,
    config: NodeConfig,
    store: BundleStore,
    contacts: HashMap<Fingerprint, Contact>,
    groups: HashMap<GroupId, MeshGroup>,
    links: HashMap<LinkId, LinkState>,
    next_link: LinkId,
    /// Our own outbound messages awaiting an acknowledgement.
    outstanding: HashMap<BundleId, AckCommitment>,
    ingest_limit: KeyedLimiter<Fingerprint>,
    decrypt_limit: KeyedLimiter<Fingerprint>,
    beacon_limit: KeyedLimiter<Fingerprint>,
    stats: Stats,
    ticks: u64,
    /// Messages for us awaiting a retry, by sender.
    deferred: HashMap<Fingerprint, Vec<Bundle>>,
}

/// Cheaply clonable handle to a running node.
#[derive(Clone)]
pub struct Node {
    inner: Arc<Mutex<Inner>>,
    events: broadcast::Sender<Event>,
}

/// Assembles a node from its parts.
pub struct NodeBuilder {
    identity: MeshIdentity,
    config: NodeConfig,
    stores: Option<Box<dyn ProtocolStores>>,
    persistence: Option<Box<dyn MeshPersistence>>,
}

impl NodeBuilder {
    pub fn config(mut self, config: NodeConfig) -> Self {
        self.config = config;
        self
    }

    /// The app's Signal Protocol stores. Defaults to a fresh in-memory store
    /// holding only this identity.
    pub fn protocol_stores(mut self, stores: Box<dyn ProtocolStores>) -> Self {
        self.stores = Some(stores);
        self
    }

    /// Where carry store, contacts and groups survive a restart. Defaults to
    /// nowhere.
    pub fn persistence(mut self, persistence: Box<dyn MeshPersistence>) -> Self {
        self.persistence = Some(persistence);
        self
    }

    pub fn start(self) -> Result<Node> {
        let NodeBuilder {
            identity,
            config,
            stores,
            persistence,
        } = self;
        let mut stores = match stores {
            Some(s) => s,
            None => Box::new(InMemSignalProtocolStore::new(
                *identity.identity_key_pair(),
                identity.registration_id(),
            )?),
        };
        identity.install(stores.as_mut())?;
        let mut persistence = persistence.unwrap_or_else(|| Box::new(NoPersistence));
        let snapshot = persistence.load()?;

        let me = identity.fingerprint();
        let now = crate::now_secs();
        let mut store =
            BundleStore::with_quota(config.store_bytes, config.store_src_quota_bytes, Some(me));
        let mut deferred: HashMap<Fingerprint, Vec<Bundle>> = HashMap::new();
        for b in snapshot.bundles {
            if b.kind == BundleKind::Message && b.dst == me {
                store.mark_seen(b.id(), now);
                deferred.entry(b.src).or_default().push(b);
            } else {
                store.insert(b, now);
            }
        }
        let mut contacts = HashMap::new();
        for (card, pinned) in snapshot.contacts {
            if card.fingerprint() != me {
                contacts.insert(
                    card.fingerprint(),
                    Contact {
                        card,
                        pinned,
                        learned_at: now,
                    },
                );
            }
        }
        let groups = snapshot.groups.into_iter().map(|g| (g.id, g)).collect();
        let outstanding = snapshot.outstanding.into_iter().collect();

        let (events, _) = broadcast::channel(1024);
        let interval = config.anti_entropy_interval;
        let inner = Inner {
            identity,
            stores,
            persistence,
            dirty: false,
            store,
            contacts,
            groups,
            links: HashMap::new(),
            next_link: 1,
            outstanding,
            ingest_limit: KeyedLimiter::new(config.ingest_per_src.0, config.ingest_per_src.1, 4096),
            decrypt_limit: KeyedLimiter::new(
                config.decrypt_per_src.0,
                config.decrypt_per_src.1,
                4096,
            ),
            beacon_limit: KeyedLimiter::new(
                config.beacons_per_src.0,
                config.beacons_per_src.1,
                4096,
            ),
            stats: Stats::default(),
            ticks: 0,
            deferred,
            config,
        };
        let node = Node {
            inner: Arc::new(Mutex::new(inner)),
            events,
        };
        let weak = Arc::downgrade(&node.inner);
        let events = node.events.clone();
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
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
        Ok(node)
    }
}

impl Node {
    pub fn builder(identity: MeshIdentity) -> NodeBuilder {
        NodeBuilder {
            identity,
            config: NodeConfig::default(),
            stores: None,
            persistence: None,
        }
    }

    /// Starts a node with in-memory sessions and no persistence.
    pub fn start(identity: MeshIdentity, config: NodeConfig) -> Result<Self> {
        Self::builder(identity).config(config).start()
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

    pub async fn stats(&self) -> Stats {
        let inner = self.inner.lock().await;
        let mut s = inner.stats.clone();
        s.links = inner.links.len();
        s.store_bundles = inner.store.len();
        s.store_bytes = inner.store.bytes();
        s.outstanding = inner.outstanding.len();
        s.messages_deferred = inner.deferred.values().map(Vec::len).sum();
        s
    }

    /// Learns a contact from a card obtained out of band (QR code, paste).
    /// Explicit contacts are never evicted to make room for beacons.
    pub async fn add_contact(&self, card: ContactCard) -> Result<Fingerprint> {
        card.verify()?;
        let fp = card.fingerprint();
        let mut inner = self.inner.lock().await;
        if fp == inner.identity.fingerprint() {
            return Err(Error::Other("cannot add yourself".into()));
        }
        Self::learn_contact(&mut inner, card, true, crate::now_secs());
        drop(inner);
        let _ = self.events.send(Event::Contact { fingerprint: fp });
        Ok(fp)
    }

    pub async fn remove_contact(&self, fp: Fingerprint) -> bool {
        let mut inner = self.inner.lock().await;
        let removed = inner.contacts.remove(&fp).is_some();
        inner.dirty |= removed;
        removed
    }

    pub async fn contact(&self, fp: Fingerprint) -> Option<ContactCard> {
        self.inner
            .lock()
            .await
            .contacts
            .get(&fp)
            .map(|c| c.card.clone())
    }

    pub async fn contacts(&self) -> Vec<Fingerprint> {
        self.inner.lock().await.contacts.keys().copied().collect()
    }

    /// 60-digit safety number between us and `with`, for in-person checks.
    pub async fn safety_number(&self, with: Fingerprint) -> Result<String> {
        let inner = self.inner.lock().await;
        let theirs = inner
            .contacts
            .get(&with)
            .ok_or_else(|| Error::UnknownContact(fingerprint_hex(&with)))?;
        safety_number(inner.identity.card(), &theirs.card)
    }

    /// Changes our display name; the next beacon carries the new card.
    pub async fn rename(&self, name: &str) -> Result<()> {
        let mut rng = rand::rngs::OsRng.unwrap_err();
        let mut inner = self.inner.lock().await;
        inner.identity.rename(name, &mut rng)?;
        inner.dirty = true;
        Ok(())
    }

    pub async fn store_len(&self) -> usize {
        self.inner.lock().await.store.len()
    }

    /// Whether the carry store currently holds `id`.
    pub async fn holds(&self, id: BundleId) -> bool {
        self.inner.lock().await.store.contains(&id)
    }

    /// Whether `id` has been processed (delivered, carried or acknowledged).
    pub async fn has_seen(&self, id: BundleId) -> bool {
        self.inner.lock().await.store.has_seen(&id)
    }

    /// Ids of messages we sent that have not been acknowledged.
    pub async fn outstanding(&self) -> Vec<BundleId> {
        self.inner
            .lock()
            .await
            .outstanding
            .keys()
            .copied()
            .collect()
    }

    pub async fn groups(&self) -> Vec<MeshGroup> {
        self.inner.lock().await.groups.values().cloned().collect()
    }

    pub async fn group(&self, id: GroupId) -> Option<MeshGroup> {
        self.inner.lock().await.groups.get(&id).cloned()
    }

    /// Attaches a link with default pacing for the given MTU.
    pub fn attach_link(&self, mtu: usize) -> LinkEndpoint {
        self.attach_link_with(LinkOptions::new(mtu))
    }

    /// Attaches a link. The transport moves bytes between the returned
    /// endpoint and the wire.
    pub fn attach_link_with(&self, options: LinkOptions) -> LinkEndpoint {
        let (id, queue) = {
            // Synchronous so transports can call it from non-async contexts.
            let mut inner = self.inner.blocking_lock_or_spin();
            let id = inner.next_link;
            inner.next_link += 1;
            let timeout = inner.config.reassembly_timeout_secs;
            let queue = inner.config.link_queue;
            let bps = options.max_bytes_per_sec.max(64) as f64;
            let fps = options.max_frames_per_sec.max(1) as f64;
            let (to_wire_tx, to_wire_rx) = mpsc::channel::<Vec<u8>>(queue);
            inner.links.insert(
                id,
                LinkState {
                    tx: to_wire_tx,
                    mtu: options.mtu.max(crate::frame::MIN_MTU),
                    peer: None,
                    reassembler: Reassembler::new(timeout),
                    known: HashSet::new(),
                    out_bytes: Bucket::new(bps, bps * 2.0),
                    in_frames: Bucket::new(fps, fps * 4.0),
                },
            );
            (id, to_wire_rx)
        };
        let (from_wire_tx, mut from_wire_rx) = mpsc::channel::<Vec<u8>>(256);
        let node = self.clone();
        tokio::spawn(async move {
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
            outbound: queue,
        }
    }

    pub fn detach_link(&self, id: LinkId) {
        let mut inner = self.inner.blocking_lock_or_spin();
        if inner.links.remove(&id).is_some() {
            let _ = self.events.send(Event::LinkClosed { link: id });
        }
    }

    /// Encrypts `plaintext` for `to` and hands the bundle to the mesh.
    /// Returns the bundle id, which is echoed in [`Event::Delivered`].
    pub async fn send_text(&self, to: Fingerprint, plaintext: &[u8]) -> Result<BundleId> {
        let envelope = Envelope::new(EnvelopeKind::Text, plaintext.to_vec())?;
        let mut inner = self.inner.lock().await;
        Self::send_envelope(&mut inner, to, &envelope)
    }

    /// Creates a group and tells every member about it and about each other.
    pub async fn create_group(&self, name: &str, members: Vec<Fingerprint>) -> Result<GroupId> {
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        let me = inner.identity.fingerprint();
        let group = MeshGroup::new(name, me, members)?;
        for m in group.members.iter().filter(|m| **m != me) {
            if !inner.contacts.contains_key(m) {
                return Err(Error::UnknownContact(fingerprint_hex(m)));
            }
        }
        if inner.groups.len() >= inner.config.max_groups {
            return Err(Error::Other("too many groups".into()));
        }
        let gid = group.id;
        let others: Vec<Fingerprint> = group.members.iter().copied().filter(|m| *m != me).collect();
        for to in &others {
            let invite = Envelope::new(EnvelopeKind::GroupInvite, group.encode())?;
            Self::send_envelope(inner, *to, &invite)?;
            // Everyone needs everyone else's card (mine they get from the
            // PreKey message's identity key plus my own card share).
            let mut cards: Vec<ContactCard> = vec![inner.identity.card().clone()];
            for other in others.iter().filter(|o| *o != to) {
                cards.push(inner.contacts[other].card.clone());
            }
            for card in cards {
                let share = Envelope::new(EnvelopeKind::CardShare, card.encode())?;
                Self::send_envelope(inner, *to, &share)?;
            }
        }
        inner.groups.insert(gid, group);
        inner.dirty = true;
        Ok(gid)
    }

    /// Sends to every other member of a group; returns one bundle id per copy.
    pub async fn send_group_text(&self, group: GroupId, plaintext: &[u8]) -> Result<Vec<BundleId>> {
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        let me = inner.identity.fingerprint();
        let g = inner
            .groups
            .get(&group)
            .ok_or_else(|| Error::Other("unknown group".into()))?
            .clone();
        let mut body = Vec::with_capacity(16 + plaintext.len());
        body.extend_from_slice(&group);
        body.extend_from_slice(plaintext);
        let mut ids = Vec::new();
        for m in g.members.iter().filter(|m| **m != me) {
            let env = Envelope::new(EnvelopeKind::GroupText, body.clone())?;
            match Self::send_envelope(inner, *m, &env) {
                Ok(id) => ids.push(id),
                Err(Error::UnknownContact(fp)) => {
                    warn!("group {}: no card for {fp}; skipping", hex::encode(group))
                }
                Err(e) => return Err(e),
            }
        }
        Ok(ids)
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
            [0; 16],
            inner.identity.card().encode(),
        )?;
        let id = bundle.id();
        inner.store.insert(bundle.clone(), crate::now_secs());
        Self::push_to_links(inner, &bundle, None);
        inner.dirty = true;
        Ok(id)
    }

    /// Writes the current state through the persistence layer now.
    pub async fn flush(&self) -> Result<()> {
        let mut inner = self.inner.lock().await;
        Self::persist(&mut inner)
    }

    /// Runs expiry, persistence and re-offers the carry store to every
    /// neighbour. Called periodically by the background task; public so
    /// tests can drive it.
    pub async fn tick(&self, now: u64) {
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        let dropped = inner.store.expire(now);
        if dropped > 0 {
            debug!("expired {dropped} bundle(s)");
            inner.dirty = true;
        }
        for link in inner.links.values_mut() {
            link.reassembler.expire(now);
        }
        inner.ticks += 1;
        self.retry_deferred(inner, None, now);
        let full = inner.ticks % FULL_SUMMARY_EVERY == 0;
        let links: Vec<LinkId> = inner.links.keys().copied().collect();
        for id in links {
            let ids = Self::offer_ids(inner, id, now);
            Self::send_summary(inner, id, &ids, full);
        }
        if let Err(e) = Self::persist(inner) {
            warn!("persistence failed: {e}");
        }
    }

    fn persist(inner: &mut Inner) -> Result<()> {
        if !inner.dirty {
            return Ok(());
        }
        let snapshot = Snapshot {
            bundles: inner
                .store
                .iter()
                .cloned()
                .chain(inner.deferred.values().flatten().cloned())
                .collect(),
            contacts: inner
                .contacts
                .values()
                .map(|c| (c.card.clone(), c.pinned))
                .collect(),
            groups: inner.groups.values().cloned().collect(),
            outstanding: inner.outstanding.iter().map(|(k, v)| (*k, *v)).collect(),
        };
        inner.persistence.save(&snapshot)?;
        inner.dirty = false;
        Ok(())
    }

    fn send_envelope(inner: &mut Inner, to: Fingerprint, envelope: &Envelope) -> Result<BundleId> {
        let mut rng = rand::rngs::OsRng.unwrap_err();
        let card = inner
            .contacts
            .get(&to)
            .ok_or_else(|| Error::UnknownContact(fingerprint_hex(&to)))?
            .card
            .clone();
        let remote = card.address()?;
        let local = inner.identity.address();
        let parts = inner.stores.parts();

        if crate::complete_now(parts.session.load_session(&remote))?.is_none() {
            crate::complete_now(process_prekey_bundle(
                &remote,
                &local,
                parts.session,
                parts.identity,
                &card.to_pre_key_bundle()?,
                SystemTime::now(),
                &mut rng,
            ))?;
        }
        let ct = crate::complete_now(message_encrypt(
            &envelope.encode(),
            &remote,
            &local,
            parts.session,
            parts.identity,
            SystemTime::now(),
            &mut rng,
        ))?;
        let mut payload = Vec::with_capacity(ct.serialize().len() + 1);
        payload.push(ct.message_type() as u8);
        payload.extend_from_slice(ct.serialize());

        let commit = ack_commitment(&envelope.ack_token);
        let bundle = Bundle::new(
            BundleKind::Message,
            inner.identity.fingerprint(),
            to,
            inner.config.message_ttl_secs,
            inner.config.max_hops,
            commit,
            payload,
        )?;
        let id = bundle.id();
        inner.outstanding.insert(id, commit);
        let now = crate::now_secs();
        inner.store.insert(bundle.clone(), now);
        Self::push_to_links(inner, &bundle, None);
        inner.dirty = true;
        Ok(id)
    }

    async fn on_link_up(&self, id: LinkId) {
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        let hello = Frame::Hello {
            fingerprint: inner.identity.fingerprint(),
        };
        Self::send_frame(inner, id, &hello);
        let ids = Self::offer_ids(inner, id, crate::now_secs());
        Self::send_summary(inner, id, &ids, true);
    }

    /// What we may offer a neighbour: everything still forwardable, plus
    /// bundles addressed to that neighbour even when their hop budget is
    /// spent (the last hop is always allowed; a false claim to be the
    /// destination earns only ciphertext).
    fn offer_ids(inner: &Inner, link: LinkId, now: u64) -> Vec<BundleId> {
        let mut ids = inner.store.forwardable_ids(now);
        if let Some(peer) = inner.links.get(&link).and_then(|l| l.peer) {
            ids.extend(
                inner
                    .store
                    .iter()
                    .filter(|b| b.dst == peer && !b.is_forwardable(now) && !b.is_expired(now))
                    .map(Bundle::id),
            );
        }
        ids
    }

    /// Whether `bundle` may be sent on `link` now.
    fn may_send(inner: &Inner, link: LinkId, bundle: &Bundle, now: u64) -> bool {
        if bundle.is_expired(now) {
            return false;
        }
        bundle.is_forwardable(now)
            || inner.links.get(&link).and_then(|l| l.peer) == Some(bundle.dst)
    }

    async fn handle_frame(&self, link_id: LinkId, bytes: &[u8]) -> Result<()> {
        let now = crate::now_secs();
        let now_ms = crate::now_millis();
        let mut inner = self.inner.lock().await;
        let inner = &mut *inner;
        inner.stats.frames_in += 1;
        let Some(link) = inner.links.get_mut(&link_id) else {
            return Err(Error::LinkClosed(link_id));
        };
        if !link.in_frames.take(1.0, now_ms) {
            inner.stats.frames_dropped_rate += 1;
            return Err(Error::Other("link frame rate exceeded".into()));
        }
        if bytes.len() > MAX_FRAME_LEN {
            inner.stats.frames_dropped_invalid += 1;
            return Err(Error::TooLarge(bytes.len(), MAX_FRAME_LEN));
        }
        let frame = match Frame::decode(bytes) {
            Ok(f) => f,
            Err(e) => {
                inner.stats.frames_dropped_invalid += 1;
                return Err(e);
            }
        };
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
                    if l.known.len() + ids.len() > MAX_KNOWN_PER_LINK {
                        l.known.clear();
                    }
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
                        if Self::may_send(inner, link_id, &b, now) {
                            Self::send_bundle(inner, link_id, &b);
                        }
                    }
                }
            }
            Frame::Bundle(bundle) => {
                self.ingest(inner, bundle, Some(link_id), now, now_ms)?;
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
                    self.ingest(inner, bundle, Some(link_id), now, now_ms)?;
                }
            }
        }
        Ok(())
    }

    /// Accepts a bundle from a link: validate, rate-limit, dedup, store,
    /// deliver if ours, forward onward.
    fn ingest(
        &self,
        inner: &mut Inner,
        mut bundle: Bundle,
        from: Option<LinkId>,
        now: u64,
        now_ms: u64,
    ) -> Result<()> {
        inner.stats.bundles_in += 1;
        if let Err(e) = bundle.validate(now) {
            inner.stats.bundles_dropped_invalid += 1;
            return Err(e);
        }
        let id = bundle.id();
        if let Some(l) = from.and_then(|f| inner.links.get_mut(&f)) {
            if l.known.len() >= MAX_KNOWN_PER_LINK {
                l.known.clear();
            }
            l.known.insert(id);
        }
        if inner.store.has_seen(&id) || bundle.is_expired(now) {
            return Ok(());
        }
        let me = inner.identity.fingerprint();
        if bundle.src == me {
            // Our own bundle echoed back; nothing to learn.
            return Ok(());
        }
        if !inner.ingest_limit.take(&bundle.src, 1.0, now_ms) {
            inner.stats.bundles_dropped_rate += 1;
            return Err(Error::Other("source rate exceeded".into()));
        }
        // Count the hop we just travelled.
        if from.is_some() {
            bundle.hops = bundle.hops.saturating_add(1);
        }

        match bundle.kind {
            BundleKind::Message if bundle.dst == me => {
                inner.store.mark_seen(id, now);
                if !inner.decrypt_limit.take(&bundle.src, 1.0, now_ms) {
                    Self::defer(inner, bundle);
                    return Ok(());
                }
                let src = bundle.src;
                match self.deliver(inner, &bundle, now) {
                    Ok(()) => self.retry_deferred(inner, Some(src), now),
                    Err(e) => {
                        debug!("deferring {}: {e}", hex::encode(id));
                        Self::defer(inner, bundle);
                    }
                }
                return Ok(());
            }
            BundleKind::Message => {
                // Not for us: carry it.
                Self::carry(inner, bundle, from, now);
            }
            BundleKind::Beacon => {
                if inner.beacon_limit.take(&bundle.src, 1.0, now_ms) {
                    match ContactCard::decode(&bundle.payload) {
                        Ok(card)
                            if card.fingerprint() == bundle.src
                                && card.created_at <= now + crate::bundle::MAX_CLOCK_SKEW_SECS =>
                        {
                            if Self::learn_contact(inner, card, false, now) {
                                let _ = self.events.send(Event::Contact {
                                    fingerprint: bundle.src,
                                });
                            }
                        }
                        Ok(_) => warn!("beacon source does not match card; ignoring"),
                        Err(e) => warn!("bad beacon: {e}"),
                    }
                } else {
                    inner.stats.bundles_dropped_rate += 1;
                }
                Self::carry(inner, bundle, from, now);
            }
            BundleKind::Ack => {
                let (acked, token) = parse_ack(&bundle.payload)?;
                let verified = if let Some(target) = inner.store.get(&acked) {
                    ack_opens(&target.ack_commit, &token)
                } else if let Some(commit) = inner.outstanding.get(&acked) {
                    ack_opens(commit, &token)
                } else {
                    // We never saw the message; we cannot judge the ack, and
                    // relays that hold the message will. Carry it as-is.
                    Self::carry(inner, bundle, from, now);
                    return Ok(());
                };
                if !verified {
                    inner.stats.acks_rejected += 1;
                    return Err(Error::Other("ack does not open the commitment".into()));
                }
                inner.stats.acks_verified += 1;
                inner.store.remove(&acked);
                inner.store.mark_seen(acked, now);
                if inner.outstanding.remove(&acked).is_some() {
                    let _ = self.events.send(Event::Delivered { bundle_id: acked });
                }
                inner.dirty = true;
                Self::carry(inner, bundle, from, now);
            }
        }
        Ok(())
    }

    /// Decrypts a message for us, hands it to the application and
    /// acknowledges it. Errors mean "not yet": the caller defers the bundle.
    fn deliver(&self, inner: &mut Inner, bundle: &Bundle, now: u64) -> Result<()> {
        let id = bundle.id();
        let me = inner.identity.fingerprint();
        let plaintext = Self::decrypt_message(inner, bundle)?;
        let envelope = Envelope::decode(&plaintext)?;
        if !ack_opens(&bundle.ack_commit, &envelope.ack_token) {
            warn!(
                "{}: sender's ack commitment does not match; delivering anyway, but relays \
                 will not honour our ack",
                hex::encode(id)
            );
        }
        inner.stats.messages_delivered += 1;
        info!(
            "delivered {} from {}",
            hex::encode(id),
            fingerprint_hex(&bundle.src)
        );
        self.dispatch(inner, bundle, id, envelope.kind, envelope.body, now);
        // Tell the network the bundle can be dropped.
        let ack = Bundle::new(
            BundleKind::Ack,
            me,
            BROADCAST,
            inner.config.ack_ttl_secs,
            inner.config.max_hops,
            [0; 16],
            ack_payload(&id, &envelope.ack_token),
        )?;
        inner.store.insert(ack.clone(), now);
        Self::push_to_links(inner, &ack, None);
        inner.dirty = true;
        Ok(())
    }

    /// Keeps a message for us that cannot be decrypted yet (most often a
    /// ratchet message that overtook the session-starting one on another
    /// path). Bounded per sender and in total; oldest dropped first.
    fn defer(inner: &mut Inner, bundle: Bundle) {
        let total: usize = inner.deferred.values().map(Vec::len).sum();
        if total >= MAX_DEFERRED {
            if let Some(src) = inner
                .deferred
                .iter()
                .max_by_key(|(_, v)| v.len())
                .map(|(k, _)| *k)
            {
                if let Some(v) = inner.deferred.get_mut(&src) {
                    v.remove(0);
                }
            }
        }
        let list = inner.deferred.entry(bundle.src).or_default();
        if list.len() >= MAX_DEFERRED_PER_SRC {
            list.remove(0);
            inner.stats.messages_undecryptable += 1;
        }
        list.push(bundle);
        inner.dirty = true;
    }

    /// Retries deferred messages from `src` (or everyone) until no progress.
    fn retry_deferred(&self, inner: &mut Inner, src: Option<Fingerprint>, now: u64) {
        let sources: Vec<Fingerprint> = match src {
            Some(s) => vec![s],
            None => inner.deferred.keys().copied().collect(),
        };
        for src in sources {
            loop {
                let Some(list) = inner.deferred.get_mut(&src) else {
                    break;
                };
                list.retain(|b| {
                    let keep = !b.is_expired(now);
                    if !keep {
                        inner.stats.messages_undecryptable += 1;
                    }
                    keep
                });
                if list.is_empty() {
                    inner.deferred.remove(&src);
                    inner.dirty = true;
                    break;
                }
                let candidates = list.clone();
                let mut progressed = false;
                for b in candidates {
                    if self.deliver(inner, &b, now).is_ok() {
                        if let Some(list) = inner.deferred.get_mut(&src) {
                            list.retain(|x| x.id() != b.id());
                        }
                        progressed = true;
                    }
                }
                if !progressed {
                    break;
                }
            }
        }
    }

    /// Hands a decrypted envelope to the application.
    fn dispatch(
        &self,
        inner: &mut Inner,
        bundle: &Bundle,
        id: BundleId,
        kind: EnvelopeKind,
        body: Vec<u8>,
        now: u64,
    ) {
        let from = bundle.src;
        let known_sender = inner.contacts.contains_key(&from);
        match kind {
            EnvelopeKind::Text => {
                if known_sender || inner.config.accept_unknown_senders {
                    let _ = self.events.send(Event::Message {
                        from,
                        bundle_id: id,
                        plaintext: body,
                        known_sender,
                    });
                }
            }
            EnvelopeKind::GroupText => {
                if body.len() < 16 {
                    warn!("group text without a group id");
                    return;
                }
                let group: GroupId = body[..16].try_into().expect("16");
                let member = inner
                    .groups
                    .get(&group)
                    .map(|g| g.is_member(&from))
                    .unwrap_or(true); // invite may still be in flight
                if member {
                    let _ = self.events.send(Event::GroupMessage {
                        group,
                        from,
                        bundle_id: id,
                        plaintext: body[16..].to_vec(),
                    });
                }
            }
            EnvelopeKind::GroupInvite => match MeshGroup::decode(&body) {
                Ok(g) if g.is_member(&inner.identity.fingerprint()) && g.is_member(&from) => {
                    let replace = match inner.groups.get(&g.id) {
                        None => inner.groups.len() < inner.config.max_groups,
                        Some(old) => {
                            old.created_by == g.created_by && old.created_at < g.created_at
                        }
                    };
                    if replace {
                        let gid = g.id;
                        inner.groups.insert(gid, g);
                        inner.dirty = true;
                        let _ = self.events.send(Event::GroupInvite { group: gid, from });
                    }
                }
                Ok(_) => warn!("group invite that does not include both parties; ignoring"),
                Err(e) => warn!("bad group invite: {e}"),
            },
            EnvelopeKind::CardShare => match ContactCard::decode(&body) {
                Ok(card) if card.fingerprint() != inner.identity.fingerprint() => {
                    let fp = card.fingerprint();
                    if Self::learn_contact(inner, card, false, now) {
                        let _ = self.events.send(Event::Contact { fingerprint: fp });
                    }
                }
                Ok(_) => {}
                Err(e) => warn!("bad card share: {e}"),
            },
        }
    }

    /// Stores and forwards a bundle that is not (or not only) for us.
    fn carry(inner: &mut Inner, bundle: Bundle, from: Option<LinkId>, now: u64) {
        if !inner.store.within_quota(&bundle.src, bundle.wire_len()) {
            inner.stats.bundles_dropped_quota += 1;
            return;
        }
        if inner.store.insert(bundle.clone(), now) {
            inner.dirty = true;
            inner.stats.bundles_forwarded += 1;
            Self::push_to_links(inner, &bundle, from);
        }
    }

    /// Records a verified card. Returns whether anything changed. Learned
    /// (unpinned) contacts are capped; the oldest is evicted for a new one.
    fn learn_contact(inner: &mut Inner, card: ContactCard, pinned: bool, now: u64) -> bool {
        let fp = card.fingerprint();
        match inner.contacts.get_mut(&fp) {
            Some(existing) => {
                let newer = existing.card.created_at < card.created_at;
                if newer {
                    existing.card = card;
                }
                if pinned && !existing.pinned {
                    existing.pinned = true;
                    inner.dirty = true;
                }
                inner.dirty |= newer;
                newer
            }
            None => {
                if !pinned {
                    let learned = inner.contacts.values().filter(|c| !c.pinned).count();
                    if learned >= inner.config.max_learned_contacts {
                        if let Some(victim) = inner
                            .contacts
                            .iter()
                            .filter(|(_, c)| !c.pinned)
                            .min_by_key(|(_, c)| c.learned_at)
                            .map(|(k, _)| *k)
                        {
                            inner.contacts.remove(&victim);
                        }
                    }
                }
                inner.contacts.insert(
                    fp,
                    Contact {
                        card,
                        pinned,
                        learned_at: now,
                    },
                );
                inner.dirty = true;
                true
            }
        }
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
            .map(|c| c.card.device_id)
            .unwrap_or(1);
        let remote = libsignal_protocol::ProtocolAddress::new(
            fingerprint_hex(&bundle.src),
            libsignal_protocol::DeviceId::new(device_id)
                .map_err(|_| Error::Wire("invalid device id"))?,
        );
        let local = inner.identity.address();
        let parts = inner.stores.parts();
        let mut rng = rand::rngs::OsRng.unwrap_err();
        Ok(crate::complete_now(message_decrypt(
            &ciphertext,
            &remote,
            &local,
            parts.session,
            parts.identity,
            parts.pre_key,
            parts.signed_pre_key,
            parts.kyber_pre_key,
            &mut rng,
        ))?)
    }

    fn push_to_links(inner: &mut Inner, bundle: &Bundle, except: Option<LinkId>) {
        let id = bundle.id();
        let now = crate::now_secs();
        let targets: Vec<LinkId> = inner
            .links
            .iter()
            .filter(|(lid, l)| Some(**lid) != except && !l.known.contains(&id))
            .map(|(lid, _)| *lid)
            .filter(|lid| Self::may_send(inner, *lid, bundle, now))
            .collect();
        for lid in targets {
            Self::send_bundle(inner, lid, bundle);
        }
    }

    /// Sends a bundle on a link if the link's byte budget allows it now;
    /// otherwise leaves it for the next summary exchange.
    fn send_bundle(inner: &mut Inner, link: LinkId, bundle: &Bundle) {
        let now_ms = crate::now_millis();
        let Some(l) = inner.links.get_mut(&link) else {
            return;
        };
        let size = bundle.wire_len();
        if !l.out_bytes.take(size as f64, now_ms) {
            debug!("link {link}: pacing; deferring {} bytes", size);
            return;
        }
        if l.known.len() >= MAX_KNOWN_PER_LINK {
            l.known.clear();
        }
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

    /// Offers ids to a neighbour. A full summary offers everything; an
    /// incremental one only what this neighbour has not been told about.
    fn send_summary(inner: &mut Inner, link: LinkId, ids: &[BundleId], full: bool) {
        let Some(l) = inner.links.get_mut(&link) else {
            return;
        };
        let offer: Vec<BundleId> = if full {
            ids.to_vec()
        } else {
            ids.iter()
                .filter(|id| !l.known.contains(*id))
                .copied()
                .collect()
        };
        if offer.is_empty() {
            return;
        }
        if l.known.len() + offer.len() > MAX_KNOWN_PER_LINK {
            l.known.clear();
        }
        l.known.extend(offer.iter().copied());
        let mtu = l.mtu;
        for f in chunk_ids(&offer, mtu, false) {
            Self::send_frame(inner, link, &f);
        }
    }

    fn send_frame(inner: &mut Inner, link: LinkId, frame: &Frame) {
        if let Some(l) = inner.links.get(&link) {
            let bytes = frame.encode();
            let n = bytes.len() as u64;
            if l.tx.try_send(bytes).is_err() {
                debug!("link {link}: outbound queue full or closed; frame dropped");
            } else {
                inner.stats.bytes_out += n;
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
