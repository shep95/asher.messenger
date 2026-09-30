//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Durable mesh state: the carry store, contacts, groups and the ids of our
//! own unacknowledged messages. Sessions are *not* here; they live in the
//! app's Signal Protocol stores.
//!
//! The node snapshots its state through [`MeshPersistence`] whenever it has
//! changed and a tick comes round, so a phone that reboots still carries what
//! it was carrying. [`FilePersistence`] writes one file atomically (write to
//! a sibling temporary file, then rename). Apps with their own database can
//! implement the trait instead.

use std::path::{Path, PathBuf};

use crate::bundle::{AckCommitment, Bundle, BundleId};
use crate::group::MeshGroup;
use crate::identity::ContactCard;
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

const SNAPSHOT_VERSION: u8 = 1;
/// A snapshot larger than this is refused on load (64 MiB).
pub const MAX_SNAPSHOT_LEN: usize = 64 << 20;

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
        w.finish()
    }

    /// Decodes a snapshot, skipping individual records that fail to parse
    /// or verify so one bad record cannot take the whole store with it.
    pub fn decode(data: &[u8]) -> Result<Self> {
        if data.len() > MAX_SNAPSHOT_LEN {
            return Err(Error::TooLarge(data.len(), MAX_SNAPSHOT_LEN));
        }
        let mut r = Reader::new(data);
        if r.u8()? != SNAPSHOT_VERSION {
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

        // A corrupt contact record is skipped, not fatal.
        let mut bad = snap.clone();
        bad.contacts[0].0.name = "tampered".into();
        let decoded = Snapshot::decode(&bad.encode()).unwrap();
        assert!(decoded.contacts.is_empty());
        assert_eq!(decoded.bundles.len(), 1);
    }
}
