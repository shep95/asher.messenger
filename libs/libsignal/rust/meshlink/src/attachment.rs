//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Attachments (files, images, voice notes) over the mesh.
//!
//! A file is sent as one *manifest* envelope followed by N *chunk* envelopes,
//! each a separate Signal-encrypted bundle, so the existing routing,
//! carrying and acknowledgement machinery applies unchanged. The receiver
//! reassembles by transfer id and verifies the whole against the manifest's
//! SHA-256 before it tells the application anything.
//!
//! ```text
//! Manifest body   u8 version=1, 16 transfer id, u8 kind, u16-len name,
//!                 u16-len mime, u32 size, u32 chunks, 32 sha256
//! Chunk body      16 transfer id, u32 index, data (the rest)
//! ```
//!
//! The transfer id is the first 16 bytes of the SHA-256 of the data; it is
//! also the deduplication key, so the same file sent twice is delivered once.
//!
//! Everything a peer can send is bounded before it costs memory: at most
//! [`MAX_TRANSFERS_PER_SRC`] half-finished transfers per sender,
//! [`MAX_TRANSFERS`] in total, [`MAX_ATTACHMENT_BYTES`] per transfer, and a
//! transfer that does not complete within [`TRANSFER_TTL_SECS`] is dropped.

use std::collections::{BTreeMap, HashMap};

use sha2::{Digest, Sha256};

use crate::bundle::Fingerprint;
use crate::envelope::MAX_BODY;
use crate::wire::{Reader, Writer};
use crate::{Error, Result};

pub type TransferId = [u8; 16];

const MANIFEST_VERSION: u8 = 1;
/// Largest attachment the mesh carries.
pub const MAX_ATTACHMENT_BYTES: usize = 4 << 20;
/// Bytes of file data per chunk. A chunk body is `16 + 4 + CHUNK_DATA`
/// bytes, the largest envelope body that still fits one bundle when the
/// ciphertext is a PreKeySignalMessage carrying a Kyber-1024 ciphertext
/// (every message before the recipient's first reply is one).
pub const CHUNK_DATA: usize = MAX_BODY - 16 - 4;
/// Most chunks a transfer can have.
pub const MAX_CHUNKS: usize = MAX_ATTACHMENT_BYTES.div_ceil(CHUNK_DATA);
pub const MAX_NAME_BYTES: usize = 255;
pub const MAX_MIME_BYTES: usize = 127;
/// Half-finished incoming transfers kept per sender.
pub const MAX_TRANSFERS_PER_SRC: usize = 8;
/// Half-finished incoming transfers kept in total.
pub const MAX_TRANSFERS: usize = 64;
/// Bytes of half-finished transfer data kept in total.
pub const MAX_TRANSFER_BYTES: usize = 32 << 20;
/// An incomplete transfer is dropped this long after its first piece.
pub const TRANSFER_TTL_SECS: u64 = 7 * 24 * 3600;
/// Completed transfer ids remembered for deduplication.
pub const MAX_COMPLETED: usize = 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum AttachmentKind {
    File = 1,
    Image = 2,
    Voice = 3,
}

impl TryFrom<u8> for AttachmentKind {
    type Error = Error;
    fn try_from(v: u8) -> Result<Self> {
        Ok(match v {
            1 => Self::File,
            2 => Self::Image,
            3 => Self::Voice,
            _ => return Err(Error::Wire("unknown attachment kind")),
        })
    }
}

/// How many chunks a file of `size` bytes takes.
pub fn chunk_count(size: usize) -> u32 {
    u32::try_from(size.div_ceil(CHUNK_DATA)).unwrap_or(u32::MAX)
}

/// First 16 bytes of the SHA-256 of the data.
pub fn transfer_id(data: &[u8]) -> TransferId {
    let digest = Sha256::digest(data);
    digest[..16].try_into().expect("16 bytes")
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Manifest {
    pub transfer: TransferId,
    pub kind: AttachmentKind,
    pub name: String,
    pub mime: String,
    pub size: u32,
    pub chunks: u32,
    pub sha256: [u8; 32],
}

impl Manifest {
    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.u8(MANIFEST_VERSION)
            .fixed(&self.transfer)
            .u8(self.kind as u8)
            .bytes(self.name.as_bytes())
            .bytes(self.mime.as_bytes())
            .u32(self.size)
            .u32(self.chunks)
            .fixed(&self.sha256);
        w.finish()
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        let mut r = Reader::new(data);
        if r.u8()? != MANIFEST_VERSION {
            return Err(Error::Wire("unsupported manifest version"));
        }
        let transfer = r.fixed::<16>()?;
        let kind = AttachmentKind::try_from(r.u8()?)?;
        let name = r.bytes()?;
        if name.len() > MAX_NAME_BYTES {
            return Err(Error::Wire("attachment name too long"));
        }
        let name = std::str::from_utf8(name)
            .map_err(|_| Error::Wire("attachment name is not UTF-8"))?
            .to_owned();
        let mime = r.bytes()?;
        if mime.len() > MAX_MIME_BYTES {
            return Err(Error::Wire("attachment mime type too long"));
        }
        let mime = std::str::from_utf8(mime)
            .map_err(|_| Error::Wire("attachment mime type is not UTF-8"))?
            .to_owned();
        let size = r.u32()?;
        let chunks = r.u32()?;
        let sha256 = r.fixed::<32>()?;
        r.finish()?;
        if size == 0 || size as usize > MAX_ATTACHMENT_BYTES {
            return Err(Error::Wire("attachment size out of range"));
        }
        if chunks as usize != (size as usize).div_ceil(CHUNK_DATA) {
            return Err(Error::Wire("attachment chunk count does not match size"));
        }
        if sha256[..16] != transfer {
            return Err(Error::Wire("transfer id does not match the digest"));
        }
        Ok(Self {
            transfer,
            kind,
            name,
            mime,
            size,
            chunks,
            sha256,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Chunk {
    pub transfer: TransferId,
    pub index: u32,
    pub data: Vec<u8>,
}

impl Chunk {
    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.fixed(&self.transfer).u32(self.index).fixed(&self.data);
        w.finish()
    }

    pub fn decode(body: &[u8]) -> Result<Self> {
        if body.len() > MAX_BODY {
            return Err(Error::TooLarge(body.len(), MAX_BODY));
        }
        let mut r = Reader::new(body);
        let transfer = r.fixed::<16>()?;
        let index = r.u32()?;
        let data = r.rest();
        if data.is_empty() || data.len() > CHUNK_DATA {
            return Err(Error::Wire("chunk size out of range"));
        }
        if index as usize >= MAX_CHUNKS {
            return Err(Error::Wire("chunk index out of range"));
        }
        Ok(Self {
            transfer,
            index,
            data: data.to_vec(),
        })
    }
}

/// Splits `data` into a manifest and its chunks.
pub fn split(
    kind: AttachmentKind,
    name: &str,
    mime: &str,
    data: &[u8],
) -> Result<(Manifest, Vec<Chunk>)> {
    if data.is_empty() {
        return Err(Error::Wire("empty attachment"));
    }
    if data.len() > MAX_ATTACHMENT_BYTES {
        return Err(Error::TooLarge(data.len(), MAX_ATTACHMENT_BYTES));
    }
    if name.len() > MAX_NAME_BYTES {
        return Err(Error::TooLarge(name.len(), MAX_NAME_BYTES));
    }
    if mime.len() > MAX_MIME_BYTES {
        return Err(Error::TooLarge(mime.len(), MAX_MIME_BYTES));
    }
    let sha256: [u8; 32] = Sha256::digest(data).into();
    let transfer: TransferId = sha256[..16].try_into().expect("16 bytes");
    let chunks: Vec<Chunk> = data
        .chunks(CHUNK_DATA)
        .zip(0u32..)
        .map(|(c, index)| Chunk {
            transfer,
            index,
            data: c.to_vec(),
        })
        .collect();
    let manifest = Manifest {
        transfer,
        kind,
        name: name.to_owned(),
        mime: mime.to_owned(),
        size: u32::try_from(data.len()).expect("bounded by MAX_ATTACHMENT_BYTES"),
        chunks: chunk_count(data.len()),
        sha256,
    };
    Ok((manifest, chunks))
}

/// A complete, verified attachment.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Attachment {
    pub from: Fingerprint,
    pub transfer: TransferId,
    pub kind: AttachmentKind,
    pub name: String,
    pub mime: String,
    pub data: Vec<u8>,
}

/// One half-finished incoming transfer. Chunks may arrive before the
/// manifest (they travel as independent bundles), so both halves are
/// optional until the end.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Incoming {
    pub from: Fingerprint,
    pub transfer: TransferId,
    pub manifest: Option<Manifest>,
    pub parts: BTreeMap<u32, Vec<u8>>,
    pub started_at: u64,
}

impl Incoming {
    fn bytes(&self) -> usize {
        self.parts.values().map(Vec::len).sum()
    }

    pub fn received(&self) -> u32 {
        u32::try_from(self.parts.len()).expect("bounded by MAX_CHUNKS")
    }

    pub fn total(&self) -> u32 {
        self.manifest.as_ref().map(|m| m.chunks).unwrap_or(0)
    }

    fn is_complete(&self) -> bool {
        self.manifest
            .as_ref()
            .map(|m| self.parts.len() == m.chunks as usize)
            .unwrap_or(false)
    }

    /// Joins the parts and checks them against the manifest.
    fn finish(self) -> Result<Attachment> {
        let manifest = self.manifest.ok_or(Error::Wire("no manifest"))?;
        let mut data = Vec::with_capacity(manifest.size as usize);
        for (i, (idx, part)) in self.parts.iter().enumerate() {
            if *idx as usize != i {
                return Err(Error::Wire("missing chunk"));
            }
            data.extend_from_slice(part);
        }
        if data.len() != manifest.size as usize {
            return Err(Error::Wire("attachment size mismatch"));
        }
        let digest: [u8; 32] = Sha256::digest(&data).into();
        if digest != manifest.sha256 {
            return Err(Error::Wire("attachment digest mismatch"));
        }
        Ok(Attachment {
            from: self.from,
            transfer: self.transfer,
            kind: manifest.kind,
            name: manifest.name,
            mime: manifest.mime,
            data,
        })
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut w = Writer::new();
        w.fixed(&self.from)
            .fixed(&self.transfer)
            .u64(self.started_at);
        match &self.manifest {
            Some(m) => {
                w.bytes(&m.encode());
            }
            None => {
                w.u16(0);
            }
        }
        w.u32(self.received());
        for (idx, data) in &self.parts {
            w.u32(*idx).bytes(data);
        }
        w.finish()
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        let mut r = Reader::new(data);
        let from = r.fixed::<16>()?;
        let transfer = r.fixed::<16>()?;
        let started_at = r.u64()?;
        let m = r.bytes()?;
        let manifest = if m.is_empty() {
            None
        } else {
            let m = Manifest::decode(m)?;
            if m.transfer != transfer {
                return Err(Error::Wire("manifest transfer id mismatch"));
            }
            Some(m)
        };
        let n = r.u32()? as usize;
        if n > MAX_CHUNKS {
            return Err(Error::Wire("too many chunks"));
        }
        let mut parts = BTreeMap::new();
        let mut bytes = 0usize;
        for _ in 0..n {
            let idx = r.u32()?;
            let part = r.bytes()?;
            if idx as usize >= MAX_CHUNKS || part.is_empty() || part.len() > CHUNK_DATA {
                return Err(Error::Wire("chunk out of range"));
            }
            bytes += part.len();
            if bytes > MAX_ATTACHMENT_BYTES {
                return Err(Error::TooLarge(bytes, MAX_ATTACHMENT_BYTES));
            }
            parts.insert(idx, part.to_vec());
        }
        r.finish()?;
        Ok(Self {
            from,
            transfer,
            manifest,
            parts,
            started_at,
        })
    }
}

/// What happened when a piece of a transfer was fed in.
#[derive(Debug, PartialEq, Eq)]
pub enum Progress {
    /// Nothing new (duplicate piece, or a transfer already delivered).
    Ignored,
    /// Piece stored; `received` of `total` chunks are in (`total` is 0
    /// until the manifest arrives).
    Partial {
        transfer: TransferId,
        received: u32,
        total: u32,
    },
    /// Every chunk is in and the digest matches.
    Complete(Box<Attachment>),
}

/// Receiver-side state for all senders. Bounded in transfers per sender, in
/// transfers overall, in bytes overall, and in time.
#[derive(Default)]
pub struct Reassembly {
    incoming: HashMap<(Fingerprint, TransferId), Incoming>,
    /// Delivered transfer ids with when they finished; duplicates are dropped.
    completed: HashMap<(Fingerprint, TransferId), u64>,
}

impl Reassembly {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.incoming.len()
    }

    pub fn is_empty(&self) -> bool {
        self.incoming.is_empty()
    }

    pub fn iter(&self) -> impl Iterator<Item = &Incoming> {
        self.incoming.values()
    }

    pub fn completed(&self) -> impl Iterator<Item = (&Fingerprint, &TransferId, &u64)> {
        self.completed.iter().map(|((f, t), at)| (f, t, at))
    }

    /// Restores an incomplete transfer from a snapshot, subject to the same
    /// bounds as live traffic.
    pub fn restore(&mut self, incoming: Incoming, now: u64) {
        if now.saturating_sub(incoming.started_at) >= TRANSFER_TTL_SECS {
            return;
        }
        let key = (incoming.from, incoming.transfer);
        if self.incoming.contains_key(&key) || self.completed.contains_key(&key) {
            return;
        }
        if !self.make_room(&incoming.from, incoming.bytes()) {
            return;
        }
        self.incoming.insert(key, incoming);
    }

    pub fn restore_completed(&mut self, from: Fingerprint, transfer: TransferId, at: u64) {
        if self.completed.len() < MAX_COMPLETED {
            self.completed.insert((from, transfer), at);
        }
    }

    pub fn manifest(
        &mut self,
        from: Fingerprint,
        manifest: Manifest,
        now: u64,
    ) -> Result<Progress> {
        let key = (from, manifest.transfer);
        if self.completed.contains_key(&key) {
            return Ok(Progress::Ignored);
        }
        if !self.incoming.contains_key(&key) {
            if !self.make_room(&from, 0) {
                return Err(Error::Other("too many incoming transfers".into()));
            }
            self.incoming.insert(
                key,
                Incoming {
                    from,
                    transfer: manifest.transfer,
                    manifest: None,
                    parts: BTreeMap::new(),
                    started_at: now,
                },
            );
        }
        let entry = self.incoming.get_mut(&key).expect("inserted above");
        if entry.manifest.is_some() {
            return Ok(Progress::Ignored);
        }
        // Chunks that arrived early but do not belong are dropped now.
        entry.parts.retain(|idx, _| *idx < manifest.chunks);
        entry.manifest = Some(manifest);
        self.settle(key, now)
    }

    pub fn chunk(&mut self, from: Fingerprint, chunk: Chunk, now: u64) -> Result<Progress> {
        let key = (from, chunk.transfer);
        if self.completed.contains_key(&key) {
            return Ok(Progress::Ignored);
        }
        if !self.incoming.contains_key(&key) {
            if !self.make_room(&from, chunk.data.len()) {
                return Err(Error::Other("too many incoming transfers".into()));
            }
            self.incoming.insert(
                key,
                Incoming {
                    from,
                    transfer: chunk.transfer,
                    manifest: None,
                    parts: BTreeMap::new(),
                    started_at: now,
                },
            );
        }
        let entry = self.incoming.get_mut(&key).expect("inserted above");
        if let Some(m) = &entry.manifest
            && chunk.index >= m.chunks
        {
            return Err(Error::Wire("chunk index beyond manifest"));
        }
        if entry.parts.contains_key(&chunk.index) {
            return Ok(Progress::Ignored);
        }
        if entry.bytes() + chunk.data.len() > MAX_ATTACHMENT_BYTES {
            self.incoming.remove(&key);
            return Err(Error::TooLarge(
                MAX_ATTACHMENT_BYTES + 1,
                MAX_ATTACHMENT_BYTES,
            ));
        }
        entry.parts.insert(chunk.index, chunk.data);
        self.settle(key, now)
    }

    /// Completes a transfer if it can; a transfer that fails verification is
    /// dropped so a corrupt sender cannot pin memory.
    fn settle(&mut self, key: (Fingerprint, TransferId), now: u64) -> Result<Progress> {
        let entry = self.incoming.get(&key).expect("present");
        if !entry.is_complete() {
            return Ok(Progress::Partial {
                transfer: key.1,
                received: entry.received(),
                total: entry.total(),
            });
        }
        let entry = self.incoming.remove(&key).expect("present");
        let attachment = entry.finish()?;
        if self.completed.len() >= MAX_COMPLETED
            && let Some(oldest) = self
                .completed
                .iter()
                .min_by_key(|(_, at)| **at)
                .map(|(k, _)| *k)
        {
            self.completed.remove(&oldest);
        }
        self.completed.insert(key, now);
        Ok(Progress::Complete(Box::new(attachment)))
    }

    /// Makes room for a new transfer from `from` adding `bytes`: drops the
    /// oldest transfer of that sender when it has too many, the oldest
    /// overall when the table is full, and the oldest overall while the byte
    /// budget is exceeded. Returns false only if room cannot be made.
    fn make_room(&mut self, from: &Fingerprint, bytes: usize) -> bool {
        if bytes > MAX_ATTACHMENT_BYTES {
            return false;
        }
        let per_src = self.incoming.values().filter(|t| t.from == *from).count();
        if per_src >= MAX_TRANSFERS_PER_SRC {
            self.drop_oldest(Some(from));
        }
        if self.incoming.len() >= MAX_TRANSFERS {
            self.drop_oldest(None);
        }
        let mut total: usize = self.incoming.values().map(Incoming::bytes).sum();
        while total + bytes > MAX_TRANSFER_BYTES && !self.incoming.is_empty() {
            total -= self.drop_oldest(None);
        }
        total + bytes <= MAX_TRANSFER_BYTES
    }

    fn drop_oldest(&mut self, from: Option<&Fingerprint>) -> usize {
        let victim = self
            .incoming
            .iter()
            .filter(|(_, t)| from.map(|f| t.from == *f).unwrap_or(true))
            .min_by_key(|(_, t)| t.started_at)
            .map(|(k, _)| *k);
        match victim {
            Some(k) => self.incoming.remove(&k).map(|t| t.bytes()).unwrap_or(0),
            None => 0,
        }
    }

    /// Drops transfers older than [`TRANSFER_TTL_SECS`] and forgets
    /// completed ids after the same time. Returns how many were dropped.
    pub fn expire(&mut self, now: u64) -> usize {
        let before = self.incoming.len();
        self.incoming
            .retain(|_, t| now.saturating_sub(t.started_at) < TRANSFER_TTL_SECS);
        self.completed
            .retain(|_, at| now.saturating_sub(*at) < TRANSFER_TTL_SECS);
        before - self.incoming.len()
    }

    /// Forgets everything from `from` (used when a self-test peer goes away).
    pub fn forget(&mut self, from: &Fingerprint) {
        self.incoming.retain(|(f, _), _| f != from);
        self.completed.retain(|(f, _), _| f != from);
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn split_and_reassemble_in_any_order() {
        let data: Vec<u8> = (0..(CHUNK_DATA * 2 + 100))
            .map(|i| u8::try_from(i % 251).expect("< 251"))
            .collect();
        let (m, chunks) = split(AttachmentKind::Image, "cat.jpg", "image/jpeg", &data).unwrap();
        assert_eq!(chunks.len(), 3);
        assert_eq!(m.chunks, 3);
        assert_eq!(Manifest::decode(&m.encode()).unwrap(), m);
        assert_eq!(Chunk::decode(&chunks[0].encode()).unwrap(), chunks[0]);
        assert!(chunks[0].encode().len() <= MAX_BODY);

        let mut r = Reassembly::new();
        let from = [1; 16];
        // Chunks first, manifest last.
        let partial = |received, total| Progress::Partial {
            transfer: m.transfer,
            received,
            total,
        };
        assert_eq!(r.chunk(from, chunks[2].clone(), 10).unwrap(), partial(1, 0));
        assert_eq!(r.chunk(from, chunks[0].clone(), 11).unwrap(), partial(2, 0));
        assert_eq!(
            r.chunk(from, chunks[0].clone(), 11).unwrap(),
            Progress::Ignored
        );
        assert_eq!(r.manifest(from, m.clone(), 12).unwrap(), partial(2, 3));
        match r.chunk(from, chunks[1].clone(), 13).unwrap() {
            Progress::Complete(a) => {
                assert_eq!(a.data, data);
                assert_eq!(a.name, "cat.jpg");
                assert_eq!(a.kind, AttachmentKind::Image);
            }
            other => panic!("{other:?}"),
        }
        assert!(r.is_empty());
        // Same transfer again is deduplicated.
        assert_eq!(r.manifest(from, m.clone(), 14).unwrap(), Progress::Ignored);
        assert_eq!(
            r.chunk(from, chunks[1].clone(), 14).unwrap(),
            Progress::Ignored
        );
    }

    #[test]
    fn corrupt_data_is_rejected_and_bounds_hold() {
        let data = vec![7u8; 500];
        let (m, mut chunks) = split(
            AttachmentKind::File,
            "a.bin",
            "application/octet-stream",
            &data,
        )
        .unwrap();
        chunks[0].data[3] ^= 1;
        let mut r = Reassembly::new();
        r.manifest([2; 16], m, 0).unwrap();
        assert!(r.chunk([2; 16], chunks[0].clone(), 0).is_err());
        assert!(r.is_empty(), "failed transfer is dropped");

        assert!(split(AttachmentKind::File, "x", "y", &[]).is_err());
        assert!(matches!(
            split(
                AttachmentKind::File,
                "x",
                "y",
                &vec![0; MAX_ATTACHMENT_BYTES + 1]
            ),
            Err(Error::TooLarge(_, _))
        ));
        assert!(split(AttachmentKind::File, &"n".repeat(300), "y", &[1]).is_err());

        // Per-source and global caps.
        let mut r = Reassembly::new();
        let per_src = u8::try_from(MAX_TRANSFERS_PER_SRC).unwrap();
        for i in 0..(per_src + 4) {
            let c = Chunk {
                transfer: [i; 16],
                index: 0,
                data: vec![1; 10],
            };
            r.chunk([9; 16], c, i as u64).unwrap();
        }
        assert_eq!(r.len(), MAX_TRANSFERS_PER_SRC);
        let total = u8::try_from(MAX_TRANSFERS).unwrap();
        for i in 0..(total + 10) {
            let c = Chunk {
                transfer: [i; 16],
                index: 0,
                data: vec![1; 10],
            };
            r.chunk([i; 16], c, 100 + i as u64).unwrap();
        }
        assert!(r.len() <= MAX_TRANSFERS);
        // A chunk beyond the manifest's count is refused.
        let (m, _) = split(AttachmentKind::File, "s", "t", &[1, 2, 3]).unwrap();
        r.manifest([5; 16], m.clone(), 0).unwrap();
        let bad = Chunk {
            transfer: m.transfer,
            index: 7,
            data: vec![1],
        };
        assert!(r.chunk([5; 16], bad, 0).is_err());
        // Expiry.
        assert!(r.expire(TRANSFER_TTL_SECS + 200) > 0);
        assert!(r.is_empty());
    }

    #[test]
    fn incoming_round_trips() {
        let data = vec![3u8; CHUNK_DATA + 5];
        let (m, chunks) = split(AttachmentKind::Voice, "v.aac", "audio/aac", &data).unwrap();
        let mut parts = BTreeMap::new();
        parts.insert(1, chunks[1].data.clone());
        let inc = Incoming {
            from: [4; 16],
            transfer: m.transfer,
            manifest: Some(m),
            parts,
            started_at: 99,
        };
        assert_eq!(Incoming::decode(&inc.encode()).unwrap(), inc);
        let no_manifest = Incoming {
            manifest: None,
            ..inc.clone()
        };
        assert_eq!(
            Incoming::decode(&no_manifest.encode()).unwrap(),
            no_manifest
        );
        let mut r = Reassembly::new();
        r.restore(inc.clone(), 100);
        assert_eq!(r.len(), 1);
        r.restore(inc, 100 + TRANSFER_TTL_SECS);
        assert_eq!(r.len(), 1, "expired restore ignored, existing kept");
    }
}
