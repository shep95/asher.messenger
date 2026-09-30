//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Groups without a group server.
//!
//! A mesh group is a name, a random id and a member list. Messages to a group
//! are sent pairwise: one Signal-encrypted bundle per member, each carrying
//! the group id inside the ciphertext (the way Signal worked before sender
//! keys). Creating a group sends every member an invite plus a *card share*
//! for every other member, so everyone can talk to everyone with no server.
//! Cost is `O(n²)` bundles per group creation and `O(n)` per message, which
//! is what a low-bandwidth mesh can afford for the group sizes it serves.

use crate::bundle::Fingerprint;
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

pub type GroupId = [u8; 16];

const GROUP_VERSION: u8 = 1;
/// Largest group; keeps a creation fan-out under 1 000 bundles.
pub const MAX_MEMBERS: usize = 32;
pub const MAX_GROUP_NAME_BYTES: usize = 64;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MeshGroup {
    pub id: GroupId,
    pub name: String,
    /// Every member including the creator; sorted, unique.
    pub members: Vec<Fingerprint>,
    pub created_by: Fingerprint,
    pub created_at: u64,
}

impl MeshGroup {
    pub fn new(name: &str, created_by: Fingerprint, mut members: Vec<Fingerprint>) -> Result<Self> {
        if name.len() > MAX_GROUP_NAME_BYTES {
            return Err(Error::Wire("group name too long"));
        }
        members.push(created_by);
        members.sort_unstable();
        members.dedup();
        if members.len() > MAX_MEMBERS {
            return Err(Error::TooLarge(members.len(), MAX_MEMBERS));
        }
        Ok(Self {
            id: rand::random(),
            name: name.to_owned(),
            members,
            created_by,
            created_at: crate::now_secs(),
        })
    }

    pub fn is_member(&self, fp: &Fingerprint) -> bool {
        self.members.binary_search(fp).is_ok()
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u8(GROUP_VERSION)
            .fixed(&self.id)
            .bytes(self.name.as_bytes())
            .fixed(&self.created_by)
            .u64(self.created_at)
            .u8(self.members.len() as u8);
        for m in &self.members {
            w.fixed(m);
        }
        w.finish()
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        let mut r = Reader::new(data);
        if r.u8()? != GROUP_VERSION {
            return Err(Error::Wire("unsupported group version"));
        }
        let id = r.fixed::<16>()?;
        let name_bytes = r.bytes()?;
        if name_bytes.len() > MAX_GROUP_NAME_BYTES {
            return Err(Error::Wire("group name too long"));
        }
        let name = std::str::from_utf8(name_bytes)
            .map_err(|_| Error::Wire("group name is not UTF-8"))?
            .to_owned();
        let created_by = r.fixed::<16>()?;
        let created_at = r.u64()?;
        let n = r.u8()? as usize;
        if n == 0 || n > MAX_MEMBERS {
            return Err(Error::Wire("group member count out of range"));
        }
        let mut members = Vec::with_capacity(n);
        for _ in 0..n {
            members.push(r.fixed::<16>()?);
        }
        r.finish()?;
        let mut sorted = members.clone();
        sorted.sort_unstable();
        sorted.dedup();
        if sorted != members || !sorted.contains(&created_by) {
            return Err(Error::Wire("group member list malformed"));
        }
        Ok(Self {
            id,
            name,
            members,
            created_by,
            created_at,
        })
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn round_trip_and_bounds() {
        let g = MeshGroup::new("crew", [1; 16], vec![[3; 16], [2; 16], [2; 16]]).unwrap();
        assert_eq!(g.members, vec![[1; 16], [2; 16], [3; 16]]);
        assert!(g.is_member(&[2; 16]));
        assert!(!g.is_member(&[9; 16]));
        let back = MeshGroup::decode(&g.encode()).unwrap();
        assert_eq!(back, g);
        let too_many: Vec<Fingerprint> = (0..40u8).map(|i| [i; 16]).collect();
        assert!(MeshGroup::new("x", [1; 16], too_many).is_err());
        let mut bad = g.encode();
        bad.truncate(bad.len() - 1);
        assert!(MeshGroup::decode(&bad).is_err());
    }
}
