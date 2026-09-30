//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! # meshlink
//!
//! A store-carry-forward ("delay tolerant") transport that carries Signal
//! Protocol ciphertext over links that are not the internet: Bluetooth LE
//! between phones, LoRa radios speaking KISS (RNode firmware), serial cables,
//! or a LAN. It is the offline transport plugin for the Asher Messenger
//! clients.
//!
//! What it does and does not change about Signal:
//!
//! * **Encryption is unchanged.** Messages are encrypted with the same
//!   libsignal sessions (PQXDH + Double Ratchet) the apps already use. meshlink
//!   only moves the ciphertext.
//! * **Identity is the device key.** A node's address is the 16-byte
//!   fingerprint of its Signal identity key. There is no account, phone number
//!   or server-assigned id.
//! * **Prekeys travel with people, not servers.** A node publishes a signed
//!   [`ContactCard`] (its prekey bundle) as a QR code or as a broadcast beacon;
//!   anyone holding the card can start a session with no server round trip.
//! * **Delivery is store-carry-forward.** Bundles are held by every node that
//!   sees them until they expire or are acknowledged, and are exchanged with
//!   each neighbour through summary vectors (epidemic routing with a hop
//!   limit). A node can therefore carry a message across a gap and deliver it
//!   hours later.
//!
//! * **Attachments, call signalling, nearby discovery and encrypted backups**
//!   ride the same bundles: files are split into chunks that each fit one
//!   bundle, call signalling messages are short-lived urgent bundles, every
//!   card seen on the air is remembered for a day, and the whole state can be
//!   sealed under a passphrase ([`backup`]).
//!
//! What it does not do: reach a satellite from a phone radio, or replace the
//! server for groups. Gateways (a node with an Iridium
//! SBD modem or a LoRa satellite ground station) attach as ordinary links; see
//! `docs/offline-mesh.md`.

pub mod attachment;
pub mod backup;
pub mod bundle;
pub mod envelope;
pub mod frame;
pub mod group;
pub mod identity;
pub mod kiss;
pub mod limits;
pub mod nearby;
pub mod node;
pub mod persist;
pub mod store;
pub mod stores;
pub mod transport;
pub mod wire;

pub use attachment::{AttachmentKind, TransferId};
pub use bundle::{BROADCAST, Bundle, BundleId, BundleKind, Fingerprint};
pub use group::{GroupId, MeshGroup};
pub use identity::{ContactCard, MeshIdentity, safety_number};
pub use nearby::Nearby;
pub use node::{Crypto, Event, LinkId, Node, NodeBuilder, NodeConfig, Prepared, Stats};
pub use persist::{FilePersistence, MeshPersistence, NoPersistence, Snapshot};
pub use store::BundleStore;
pub use stores::{ProtocolStores, SeparateStores, StoreParts};
pub use transport::{LinkEndpoint, LinkOptions};

/// Errors surfaced by meshlink.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("malformed wire data: {0}")]
    Wire(&'static str),
    #[error("unknown contact {0}; no contact card held for this fingerprint")]
    UnknownContact(String),
    #[error("contact card signature is invalid")]
    BadCardSignature,
    #[error("payload of {0} bytes exceeds the {1} byte limit")]
    TooLarge(usize, usize),
    #[error("link {0} is gone")]
    LinkClosed(u64),
    #[error("wrong passphrase or corrupt backup")]
    BadPassphrase,
    #[error("the backup belongs to a different identity")]
    IdentityMismatch,
    #[error(transparent)]
    Protocol(#[from] libsignal_protocol::SignalProtocolError),
    #[error(transparent)]
    Curve(#[from] libsignal_core::curve::CurveError),
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("{0}")]
    Other(String),
}

pub type Result<T> = std::result::Result<T, Error>;

/// Drives a future that never actually suspends (libsignal's in-memory store
/// operations) to completion without an executor. libsignal's store futures
/// are `?Send`, so awaiting them inside a spawned task is not possible; this
/// keeps the node's tasks `Send` while still using the real protocol code.
pub(crate) fn complete_now<F: std::future::Future>(fut: F) -> F::Output {
    let mut fut = std::pin::pin!(fut);
    let waker = std::task::Waker::noop();
    let mut cx = std::task::Context::from_waker(waker);
    loop {
        match fut.as_mut().poll(&mut cx) {
            std::task::Poll::Ready(v) => return v,
            std::task::Poll::Pending => std::thread::yield_now(),
        }
    }
}

/// Milliseconds since the Unix epoch.
pub fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Seconds since the Unix epoch.
pub fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
