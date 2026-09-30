//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Many nodes, random topology, LoRa-sized frames: every message must reach
//! its recipient exactly once, and the per-node cost must stay small. There
//! is no central component in a mesh, so "scale" means each node handles its
//! own neighbourhood within bounded memory and time; this test measures that.

use std::time::{Duration, Instant};

use meshlink::transport::memory::MemoryLink;
use meshlink::{Event, MeshIdentity, Node, NodeConfig};
use rand::seq::SliceRandom as _;
use rand::{Rng as _, SeedableRng as _, TryRngCore as _};

fn node(name: &str) -> Node {
    let mut rng = rand::rngs::OsRng.unwrap_err();
    Node::start(
        MeshIdentity::generate(name, &mut rng).unwrap(),
        NodeConfig {
            anti_entropy_interval: Duration::from_millis(100),
            ingest_per_src: (50.0, 500.0),
            max_hops: 10,
            ..NodeConfig::default()
        },
    )
    .unwrap()
}

async fn run(n_nodes: usize, n_messages: usize, mtu: usize) {
    let mut rng = rand::rngs::StdRng::seed_from_u64(7);
    let nodes: Vec<Node> = (0..n_nodes).map(|i| node(&format!("n{i}"))).collect();
    let mut fps = Vec::with_capacity(n_nodes);
    let mut cards = Vec::with_capacity(n_nodes);
    for n in &nodes {
        fps.push(n.fingerprint().await);
        cards.push(n.card().await);
    }
    // Ring plus random chords: connected, sparse, several hops across.
    let mut links = Vec::new();
    for i in 0..n_nodes {
        links.push(MemoryLink::connect(
            &nodes[i],
            &nodes[(i + 1) % n_nodes],
            mtu,
        ));
    }
    for _ in 0..n_nodes {
        let (i, j) = (rng.random_range(0..n_nodes), rng.random_range(0..n_nodes));
        if i != j {
            links.push(MemoryLink::connect(&nodes[i], &nodes[j], mtu));
        }
    }
    let mut receivers: Vec<_> = nodes.iter().map(|n| n.subscribe()).collect();

    let started = Instant::now();
    let mut expected: Vec<(usize, usize, Vec<u8>, [u8; 16])> = Vec::new();
    for k in 0..n_messages {
        let mut idx: Vec<usize> = (0..n_nodes).collect();
        idx.shuffle(&mut rng);
        let (from, to) = (idx[0], idx[1]);
        nodes[from].add_contact(cards[to].clone()).await.unwrap();
        let text = format!("msg-{k}-{from}-{to}").into_bytes();
        let id = nodes[from].send_text(fps[to], &text).await.unwrap();
        expected.push((from, to, text, id));
    }
    let send_elapsed = started.elapsed();

    let deadline = Instant::now() + Duration::from_secs(60);
    let mut got: Vec<Vec<Vec<u8>>> = vec![Vec::new(); n_nodes];
    let mut remaining = n_messages;
    while remaining > 0 && Instant::now() < deadline {
        let mut progressed = false;
        for (i, rx) in receivers.iter_mut().enumerate() {
            while let Ok(e) = rx.try_recv() {
                if let Event::Message { plaintext, .. } = e {
                    got[i].push(plaintext);
                    remaining -= 1;
                    progressed = true;
                }
            }
        }
        if !progressed {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
    let total = started.elapsed();
    let mut failures = 0;
    for (from, to, text, id) in &expected {
        let count = got[*to].iter().filter(|t| *t == text).count();
        if count != 1 {
            failures += 1;
            let mut holders = Vec::new();
            for (i, n) in nodes.iter().enumerate() {
                if n.holds(*id).await {
                    holders.push(i);
                }
            }
            let rs = nodes[*to].stats().await;
            eprintln!(
                "LOST {:?} (id {}): delivered {count}x; held by {holders:?}; recipient seen={} \
                 deferred={} undecryptable={} delivered_total={}; sender outstanding={}",
                String::from_utf8_lossy(text),
                hex::encode(id),
                nodes[*to].has_seen(*id).await,
                rs.messages_deferred,
                rs.messages_undecryptable,
                rs.messages_delivered,
                nodes[*from].outstanding().await.contains(id),
            );
        }
    }
    let mut totals = meshlink::Stats::default();
    for n in &nodes {
        let s = n.stats().await;
        totals.frames_dropped_rate += s.frames_dropped_rate;
        totals.bundles_dropped_rate += s.bundles_dropped_rate;
        totals.bundles_dropped_quota += s.bundles_dropped_quota;
        totals.messages_undecryptable += s.messages_undecryptable;
        totals.acks_rejected += s.acks_rejected;
    }
    eprintln!("aggregate drops: {totals:?}");
    assert_eq!(failures, 0, "{failures} message(s) lost");
    let mut max_store = 0;
    for n in &nodes {
        max_store = max_store.max(n.stats().await.store_bytes);
    }
    eprintln!(
        "{n_nodes} nodes, {n_messages} messages, mtu {mtu}: encrypt+send {:?} total, \
         all delivered in {:?}, largest carry store {max_store} bytes, {} links",
        send_elapsed,
        total,
        links.len()
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn sixty_four_nodes_lora_frames() {
    run(64, 128, 200).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
async fn ingest_throughput_single_node() {
    // How fast can one node take bundles off a link? This is the number that
    // bounds what a relay can do per second, independent of the radio.
    use meshlink::bundle::{Bundle, BundleKind};
    use meshlink::frame::Frame;
    let relay = Node::start(
        {
            let mut rng = rand::rngs::OsRng.unwrap_err();
            MeshIdentity::generate("relay", &mut rng).unwrap()
        },
        NodeConfig {
            store_bytes: 64 << 20,
            store_src_quota_bytes: 64 << 20,
            ingest_per_src: (1e9, 1e9),
            ..NodeConfig::default()
        },
    )
    .unwrap();
    let link = relay.attach_link_with(meshlink::LinkOptions {
        mtu: 1500,
        max_bytes_per_sec: usize::MAX / 4,
        max_frames_per_sec: usize::MAX / 4,
    });
    let n = 20_000u64;
    let frames: Vec<Vec<u8>> = (0..n)
        .map(|i| {
            let mut b = Bundle::new(
                BundleKind::Message,
                [(i % 251) as u8; 16],
                [9; 16],
                3600,
                7,
                [0; 16],
                vec![0; 200],
            )
            .unwrap();
            b.nonce = i;
            Frame::Bundle(b).encode()
        })
        .collect();
    let started = Instant::now();
    for f in frames {
        link.inbound.send(f).await.unwrap();
    }
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        let s = relay.stats().await;
        if s.bundles_in >= n || Instant::now() > deadline {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let elapsed = started.elapsed();
    let s = relay.stats().await;
    assert_eq!(s.bundles_in, n);
    assert_eq!(s.store_bundles as u64, n);
    eprintln!(
        "ingested {n} bundles in {:?} ({:.0} bundles/s), store {} bytes",
        elapsed,
        n as f64 / elapsed.as_secs_f64(),
        s.store_bytes
    );
    assert!(elapsed < Duration::from_secs(30));
}
