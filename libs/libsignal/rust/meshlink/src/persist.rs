//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Durable mesh state: the carry store, contacts, groups, the ids of our
//! own unacknowledged messages, the nearby table and half-finished incoming
//! attachments. Sessions are *not* here; they live in the app's Signal
//! Protocol stores.
//!
//! The node snapshots its state through [`MeshPersistence`] whenever it has
//! changed and a tick comes round, so a phone that reboots still carries what
//! it was carrying. [`FilePersistence`] writes one file atomically (write to
//! a sibling temporary file, then rename). Apps with their own database can
//! implement the trait instead.

use std::path::{Path, PathBuf};

use crate::attachment::{Incoming, MAX_COMPLETED, MAX_TRANSFERS, TransferId};
use crate::bundle::{AckCommitment, Bundle, BundleId, Fingerprint};
use crate::group::MeshGroup;
use crate::identity::ContactCard;
use crate::nearby::NearbyTable;
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

/// Version 1 ends after the outstanding acks; version 2 adds the nearby
/// table and incoming attachments. Both are read.
const SNAPSHOT_VERSION: u8 = 2;
/// A snapshot larger than this is refused on load (128 MiB).
pub const MAX_SNAPSHOT_LEN: usize = 128 << 20;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Snapshot {
    pub bundles: Vec<Bundle>,
    /// Contacts with whether they were added explicitly (pinned) rather than
    /// learned from a beacon or a card share.
    pub contacts: Vec<(ContactCard, bool)>,
    pub groups: Vec<MeshGroup>,
    /// Our own messages still awaiting an acknowledgement, with the
    /// commitment an acknowledgement must open.
    pub outstanding: Vec<(BundleId, AckCommitment)>,
    /// Encoded [`NearbyTable`] (see [`NearbyTable::encode`]).
    pub nearby: Vec<u8>,
    /// Half-finished incoming attachments.
    pub transfers: Vec<Incoming>,
    /// Delivered attachments remembered for deduplication: sender,
    /// transfer id, when.
    pub completed_transfers: Vec<(Fingerprint, TransferId, u64)>,
}

impl Snapshot {
    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u8(SNAPSHOT_VERSION).u32(self.bundles.len() as u32);
        for b in &self.bundles {
            let enc = b.encode();
            w.u16(enc.len() as u16).fixed(&enc);
        }
        w.u32(self.contacts.len() as u32);
        for (c, pinned) in &self.contacts {
            let enc = c.encode();
            w.u8(*pinned as u8).u16(enc.len() as u16).fixed(&enc);
        }
        w.u32(self.groups.len() as u32);
        for g in &self.groups {
            let enc = g.encode();
            w.u16(enc.len() as u16).fixed(&enc);
        }
        w.u32(self.outstanding.len() as u32);
        for (id, commit) in &self.outstanding {
            w.fixed(id).fixed(commit);
        }
        // Version 2 additions.
        let nearby = if self.nearby.is_empty() {
            NearbyTable::new().encode()
        } else {
            self.nearby.clone()
        };
        w.u32(nearby.len() as u32).fixed(&nearby);
        w.u32(self.transfers.len().min(MAX_TRANSFERS) as u32);
        for t in self.transfers.iter().take(MAX_TRANSFERS) {
            let enc = t.encode();
            w.u32(enc.len() as u32).fixed(&enc);
        }
        w.u32(self.completed_transfers.len().min(MAX_COMPLETED) as u32);
        for (from, transfer, at) in self.completed_transfers.iter().take(MAX_COMPLETED) {
            w.fixed(from).fixed(transfer).u64(*at);
        }
        w.finish()
    }

    /// Decodes a snapshot, skipping individual records that fail to parse
    /// or verify so one bad record cannot take the whole store with it.
    pub fn decode(data: &[u8]) -> Result<Self> {
        if data.len() > MAX_SNAPSHOT_LEN {
            return Err(Error::TooLarge(data.len(), MAX_SNAPSHOT_LEN));
        }
        let mut r = Reader::new(data);
        let version = r.u8()?;
        if version != 1 && version != SNAPSHOT_VERSION {
            return Err(Error::Wire("unsupported snapshot version"));
        }
        let mut snap = Snapshot::default();
        let n = r.u32()? as usize;
        for _ in 0..n {
            let len = r.u16()? as usize;
            let rec = r.take(len)?;
            if let Ok(b) = Bundle::decode(rec) {
                snap.bundles.push(b);
            }
        }
        let n = r.u32()? as usize;
        for _ in 0..n {
            let pinned = r.u8()? != 0;
            let len = r.u16()? as usize;
            let rec = r.take(len)?;
            if let Ok(c) = ContactCard::decode(rec) {
                snap.contacts.push((c, pinned));
            }
        }
        let n = r.u32()? as usize;
        for _ in 0..n {
            let len = r.u16()? as usize;
            let rec = r.take(len)?;
            if let Ok(g) = MeshGroup::decode(rec) {
                snap.groups.push(g);
            }
        }
        let n = r.u32()? as usize;
        for _ in 0..n {
            snap.outstanding.push((r.fixed::<16>()?, r.fixed::<16>()?));
        }
        if version == 1 {
            r.finish()?;
            return Ok(snap);
        }
        let len = r.u32()? as usize;
        let nearby = r.take(len)?;
        // Validated here so a corrupt table is dropped, not fatal; an empty
        // table stays the empty default.
        if NearbyTable::decode(nearby).is_ok_and(|t| !t.is_empty()) {
            snap.nearby = nearby.to_vec();
        }
        let n = r.u32()? as usize;
        if n > MAX_TRANSFERS {
            return Err(Error::Wire("too many transfers in snapshot"));
        }
        for _ in 0..n {
            let len = r.u32()? as usize;
            let rec = r.take(len)?;
            if let Ok(t) = Incoming::decode(rec) {
                snap.transfers.push(t);
            }
        }
        let n = r.u32()? as usize;
        if n > MAX_COMPLETED {
            return Err(Error::Wire("too many completed transfers in snapshot"));
        }
        for _ in 0..n {
            snap.completed_transfers
                .push((r.fixed::<16>()?, r.fixed::<16>()?, r.u64()?));
        }
        r.finish()?;
        Ok(snap)
    }
}

/// Where a node keeps its mesh state between runs.
pub trait MeshPersistence: Send {
    fn load(&mut self) -> Result<Snapshot>;
    fn save(&mut self, snapshot: &Snapshot) -> Result<()>;
}

/// A single file, replaced atomically on every save.
pub struct FilePersistence {
    path: PathBuf,
}

impl FilePersistence {
    pub fn new(path: impl AsRef<Path>) -> Self {
        Self {
            path: path.as_ref().to_path_buf(),
        }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl MeshPersistence for FilePersistence {
    fn load(&mut self) -> Result<Snapshot> {
        match std::fs::read(&self.path) {
            Ok(bytes) => Snapshot::decode(&bytes),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Snapshot::default()),
            Err(e) => Err(e.into()),
        }
    }

    fn save(&mut self, snapshot: &Snapshot) -> Result<()> {
        let tmp = self.path.with_extension("tmp");
        if let Some(parent) = self.path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent)?;
            }
        }
        std::fs::write(&tmp, snapshot.encode())?;
        std::fs::rename(&tmp, &self.path)?;
        Ok(())
    }
}

/// Keeps nothing. The default for nodes that do not need to survive a restart.
pub struct NoPersistence;

impl MeshPersistence for NoPersistence {
    fn load(&mut self) -> Result<Snapshot> {
        Ok(Snapshot::default())
    }
    fn save(&mut self, _snapshot: &Snapshot) -> Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod test {
    use rand::TryRngCore as _;

    use super::*;
    use crate::bundle::BundleKind;
    use crate::identity::MeshIdentity;

    #[test]
    fn snapshot_round_trip_via_file() {
        let mut rng = rand::rngs::OsRng.unwrap_err();
        let me = MeshIdentity::generate("Ada", &mut rng).unwrap();
        let bundle = Bundle::new(
            BundleKind::Message,
            [1; 16],
            [2; 16],
            600,
            5,
            [3; 16],
            vec![9; 40],
        )
        .unwrap();
        let group = MeshGroup::new("crew", me.fingerprint(), vec![[2; 16]]).unwrap();
        let snap = Snapshot {
            bundles: vec![bundle.clone()],
            contacts: vec![(me.card().clone(), true)],
            groups: vec![group.clone()],
            outstanding: vec![(bundle.id(), [3; 16])],
            ..Snapshot::default()
        };
        let dir = std::env::temp_dir().join(format!("meshlink-persist-{}", rand::random::<u64>()));
        let mut fp = FilePersistence::new(dir.join("state.bin"));
        assert_eq!(
            fp.load().unwrap(),
            Snapshot::default(),
            "missing file is empty"
        );
        fp.save(&snap).unwrap();
        let back = fp.load().unwrap();
        assert_eq!(back.bundles, snap.bundles);
        assert_eq!(back.groups, snap.groups);
        assert_eq!(back.outstanding, snap.outstanding);
        assert_eq!(back.contacts[0].0.fingerprint(), me.fingerprint());
        assert!(back.contacts[0].1);
        let _ = std::fs::remove_dir_all(dir);

        // Version 2 sections round-trip too.
        let mut nearby = crate::nearby::NearbyTable::new();
        nearby.seen([7; 16], Some("Bob"), 42);
        let (manifest, chunks) = crate::attachment::split(
            crate::attachment::AttachmentKind::File,
            "f",
            "text/plain",
            &[1, 2, 3],
        )
        .unwrap();
        let incoming = crate::attachment::Incoming {
            from: [8; 16],
            transfer: manifest.transfer,
            manifest: None,
            parts: [(0u32, chunks[0].data.clone())].into_iter().collect(),
            started_at: 7,
        };
        let v2 = Snapshot {
            nearby: nearby.encode(),
            transfers: vec![incoming],
            completed_transfers: vec![([1; 16], [2; 16], 3)],
            ..snap.clone()
        };
        let back = Snapshot::decode(&v2.encode()).unwrap();
        assert_eq!(back.nearby, v2.nearby);
        assert_eq!(back.transfers, v2.transfers);
        assert_eq!(back.completed_transfers, v2.completed_transfers);
        // A version 1 snapshot (no trailing sections) still loads.
        let mut w = crate::wire::Writer::new();
        w.u8(1)
            .u32(0)
            .u32(0)
            .u32(0)
            .u32(1)
            .fixed(&[1; 16])
            .fixed(&[2; 16]);
        let old = Snapshot::decode(&w.finish()).unwrap();
        assert_eq!(old.outstanding, vec![([1; 16], [2; 16])]);
        assert!(old.nearby.is_empty());

        // A corrupt contact record is skipped, not fatal.
        let mut bad = snap.clone();
        bad.contacts[0].0.name = "tampered".into();
        let decoded = Snapshot::decode(&bad.encode()).unwrap();
        assert!(decoded.contacts.is_empty());
        assert_eq!(decoded.bundles.len(), 1);
    }
}
