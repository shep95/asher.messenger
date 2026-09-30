//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Every decoder must survive arbitrary bytes: truncated, bit-flipped and
//! random inputs may be rejected but may never panic or allocate without
//! bound. This is the cheap half of fuzzing, run on every test pass.

use meshlink::bundle::{Bundle, BundleKind};
use meshlink::envelope::{Envelope, EnvelopeKind};
use meshlink::frame::{Frame, Reassembler};
use meshlink::group::MeshGroup;
use meshlink::identity::ContactCard;
use meshlink::persist::Snapshot;
use meshlink::{MeshIdentity, kiss};
use rand::{Rng as _, SeedableRng as _, TryRngCore as _};

fn mutations(valid: &[u8], rng: &mut rand::rngs::StdRng) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    for cut in 0..valid.len().min(64) {
        out.push(valid[..cut].to_vec());
    }
    for _ in 0..200 {
        let mut m = valid.to_vec();
        let flips = rng.random_range(1..4);
        for _ in 0..flips {
            let i = rng.random_range(0..m.len());
            m[i] ^= 1 << rng.random_range(0..8);
        }
        out.push(m);
    }
    for _ in 0..100 {
        let n = rng.random_range(0..600);
        out.push((0..n).map(|_| rng.random()).collect());
    }
    let mut long = valid.to_vec();
    long.extend(std::iter::repeat_n(0xFF, 10_000));
    out.push(long);
    out
}

#[test]
fn decoders_never_panic() {
    let mut rng = rand::rngs::StdRng::seed_from_u64(42);
    let mut os = rand::rngs::OsRng.unwrap_err();
    let me = MeshIdentity::generate("Ada", &mut os).unwrap();

    let bundle = Bundle::new(
        BundleKind::Message,
        [1; 16],
        [2; 16],
        600,
        5,
        [3; 16],
        vec![9; 300],
    )
    .unwrap();
    for m in mutations(&bundle.encode(), &mut rng) {
        let _ = Bundle::decode(&m).map(|b| b.validate(b.created_at));
    }
    for m in mutations(&Frame::Bundle(bundle.clone()).encode(), &mut rng) {
        let _ = Frame::decode(&m);
    }
    for m in mutations(
        &Frame::Summary {
            ids: vec![[7; 16]; 5],
        }
        .encode(),
        &mut rng,
    ) {
        let _ = Frame::decode(&m);
    }
    for m in mutations(&me.card().encode(), &mut rng) {
        let _ = ContactCard::decode(&m);
    }
    let env = Envelope::new(EnvelopeKind::GroupText, vec![1; 40]).unwrap();
    for m in mutations(&env.encode(), &mut rng) {
        let _ = Envelope::decode(&m);
    }
    let group = MeshGroup::new("crew", me.fingerprint(), vec![[5; 16], [6; 16]]).unwrap();
    for m in mutations(&group.encode(), &mut rng) {
        let _ = MeshGroup::decode(&m);
    }
    let snap = Snapshot {
        bundles: vec![bundle.clone()],
        contacts: vec![(me.card().clone(), true)],
        groups: vec![group],
        outstanding: vec![(bundle.id(), [3; 16])],
    };
    for m in mutations(&snap.encode(), &mut rng) {
        let _ = Snapshot::decode(&m);
    }
    for m in mutations(&me.export().unwrap(), &mut rng) {
        let _ = MeshIdentity::import(&m, &mut os);
    }
    // KISS decoder: arbitrary byte soup in arbitrary chunk sizes.
    let mut dec = kiss::Decoder::new();
    for _ in 0..500 {
        let n = rng.random_range(0..300);
        let chunk: Vec<u8> = (0..n).map(|_| rng.random()).collect();
        for (_, payload) in dec.feed(&chunk) {
            assert!(payload.len() <= 8192 + 16, "kiss payload unbounded");
        }
    }
    // Reassembler: random fragments never grow past its caps.
    let mut r = Reassembler::new(60);
    for i in 0..5000u64 {
        let mut id = [0u8; 16];
        id[..8].copy_from_slice(&(i % 300).to_be_bytes());
        let total = rng.random_range(0..=255u8);
        let index = rng.random_range(0..=255u8);
        let data: Vec<u8> = (0..rng.random_range(0..2100))
            .map(|_| rng.random())
            .collect();
        let _ = r.push(id, index, total, data, i);
        assert!(r.pending() <= meshlink::frame::MAX_PARTIALS);
    }
}
