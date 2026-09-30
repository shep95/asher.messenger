//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! End-to-end behaviour of a small mesh: multi-hop relay through a node that
//! cannot read the traffic, store-carry-forward across a gap in time, beacon
//! based contact discovery, authenticated delivery acknowledgements, groups,
//! persistence across a restart, and abuse resistance.

use std::time::Duration;

use meshlink::bundle::{BROADCAST, Bundle, BundleKind, ack_payload};
use meshlink::frame::Frame;
use meshlink::transport::memory::MemoryLink;
use meshlink::{Event, FilePersistence, MeshIdentity, Node, NodeConfig};
use rand::TryRngCore as _;

fn config() -> NodeConfig {
    NodeConfig {
        anti_entropy_interval: Duration::from_millis(50),
        ..NodeConfig::default()
    }
}

fn identity(name: &str) -> MeshIdentity {
    let mut rng = rand::rngs::OsRng.unwrap_err();
    MeshIdentity::generate(name, &mut rng).unwrap()
}

async fn node() -> Node {
    Node::start(identity("node"), config()).unwrap()
}

async fn wait_for_message(rx: &mut tokio::sync::broadcast::Receiver<Event>) -> (Vec<u8>, [u8; 16]) {
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if let Event::Message {
                plaintext, from, ..
            } = rx.recv().await.unwrap()
            {
                return (plaintext, from);
            }
        }
    })
    .await
    .expect("message should arrive")
}

async fn wait_for_delivered(rx: &mut tokio::sync::broadcast::Receiver<Event>, id: [u8; 16]) {
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if let Event::Delivered { bundle_id } = rx.recv().await.unwrap() {
                if bundle_id == id {
                    return;
                }
            }
        }
    })
    .await
    .expect("delivery ack should arrive")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn direct_message_over_one_link() {
    let (a, b) = (node().await, node().await);
    let mut b_events = b.subscribe();
    let mut a_events = a.subscribe();
    a.add_contact(b.card().await).await.unwrap();
    let _link = MemoryLink::connect(&a, &b, 1500);

    let id = a
        .send_text(b.fingerprint().await, b"hello over the air")
        .await
        .unwrap();
    let (text, from) = wait_for_message(&mut b_events).await;
    assert_eq!(text, b"hello over the air");
    assert_eq!(from, a.fingerprint().await);
    wait_for_delivered(&mut a_events, id).await;
    assert_eq!(a.stats().await.outstanding, 0);

    // A second message rides the established ratchet (a Whisper message).
    a.send_text(b.fingerprint().await, b"second").await.unwrap();
    let (text, _) = wait_for_message(&mut b_events).await;
    assert_eq!(text, b"second");

    // B never held A's card, yet the session identifies A; the event says so.
    let mut c_events = b.subscribe();
    a.send_text(b.fingerprint().await, b"third").await.unwrap();
    let known = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if let Event::Message { known_sender, .. } = c_events.recv().await.unwrap() {
                return known_sender;
            }
        }
    })
    .await
    .unwrap();
    assert!(!known);
    let n = b.safety_number(a.fingerprint().await).await;
    assert!(n.is_err(), "no card, no safety number");
    b.add_contact(a.card().await).await.unwrap();
    assert_eq!(
        a.safety_number(b.fingerprint().await).await.unwrap(),
        b.safety_number(a.fingerprint().await).await.unwrap()
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn relayed_through_a_node_that_cannot_read_it() {
    let (a, relay, c) = (node().await, node().await, node().await);
    let mut c_events = c.subscribe();
    let mut relay_events = relay.subscribe();
    a.add_contact(c.card().await).await.unwrap();
    // A <-> relay <-> C over LoRa-sized frames; the relay holds no contacts.
    let _l1 = MemoryLink::connect(&a, &relay, 200);
    let _l2 = MemoryLink::connect(&relay, &c, 200);

    a.send_text(c.fingerprint().await, b"two hops")
        .await
        .unwrap();
    let (text, from) = wait_for_message(&mut c_events).await;
    assert_eq!(text, b"two hops");
    assert_eq!(from, a.fingerprint().await);

    // The relay saw the bundle but never a plaintext.
    while let Ok(e) = relay_events.try_recv() {
        assert!(
            !matches!(e, Event::Message { .. }),
            "relay must not decrypt"
        );
    }
    // After the ack propagates, the relay drops the carried bundle (only the ack remains).
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(relay.store_len().await <= 1);
    assert_eq!(relay.stats().await.acks_verified, 1);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn store_carry_forward_across_a_gap_in_time() {
    let (a, courier, c) = (node().await, node().await, node().await);
    let mut c_events = c.subscribe();
    a.add_contact(c.card().await).await.unwrap();

    // 1. A meets the courier; C is nowhere near.
    let link = MemoryLink::connect(&a, &courier, 1500);
    a.send_text(c.fingerprint().await, b"carry me")
        .await
        .unwrap();
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(courier.store_len().await, 1, "courier holds the bundle");
    link.disconnect();

    // 2. Later, the courier meets C.
    tokio::time::sleep(Duration::from_millis(100)).await;
    let _link2 = MemoryLink::connect(&courier, &c, 1500);
    let (text, _) = wait_for_message(&mut c_events).await;
    assert_eq!(text, b"carry me");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn beacon_discovery_then_reply() {
    let (a, b) = (node().await, node().await);
    let mut a_events = a.subscribe();
    let mut b_events = b.subscribe();
    let _link = MemoryLink::connect(&a, &b, 200);

    // Nobody has exchanged cards. B broadcasts; A learns B and can write to it.
    b.broadcast_card().await.unwrap();
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if let Event::Contact { .. } = a_events.recv().await.unwrap() {
                break;
            }
        }
    })
    .await
    .unwrap();
    assert_eq!(a.contacts().await, vec![b.fingerprint().await]);
    assert_eq!(a.contact(b.fingerprint().await).await.unwrap().name, "node");

    a.send_text(b.fingerprint().await, b"found you")
        .await
        .unwrap();
    let (text, from) = wait_for_message(&mut b_events).await;
    assert_eq!(text, b"found you");
    assert_eq!(from, a.fingerprint().await);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn unknown_recipient_is_rejected_and_duplicates_are_suppressed() {
    let (a, b, c) = (node().await, node().await, node().await);
    let mut c_events = c.subscribe();
    assert!(a.send_text([7; 16], b"nobody").await.is_err());

    a.add_contact(c.card().await).await.unwrap();
    // Two parallel paths to C: the message must be delivered exactly once.
    let _l1 = MemoryLink::connect(&a, &b, 1500);
    let _l2 = MemoryLink::connect(&b, &c, 1500);
    let _l3 = MemoryLink::connect(&a, &c, 1500);
    a.send_text(c.fingerprint().await, b"once").await.unwrap();
    let (text, _) = wait_for_message(&mut c_events).await;
    assert_eq!(text, b"once");
    tokio::time::sleep(Duration::from_millis(300)).await;
    let mut extra = 0;
    while let Ok(e) = c_events.try_recv() {
        if matches!(e, Event::Message { .. }) {
            extra += 1;
        }
    }
    assert_eq!(extra, 0, "duplicate delivery");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn forged_acks_are_ignored_and_real_ones_drain_relays() {
    let (a, relay) = (node().await, node().await);
    let c = identity("c");
    let c_card = c.card().clone();
    let c_fp = c.fingerprint();
    a.add_contact(c_card).await.unwrap();
    let _l1 = MemoryLink::connect(&a, &relay, 1500);
    let id = a.send_text(c_fp, b"do not lose me").await.unwrap();
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(relay.store_len().await, 1);

    // An attacker on another link of the relay claims C received it.
    let mut attacker = relay.attach_link(1500);
    let forged = Bundle::new(
        BundleKind::Ack,
        [0xAA; 16],
        BROADCAST,
        3600,
        7,
        [0; 16],
        ack_payload(&id, &[0; 16]),
    )
    .unwrap();
    attacker
        .inbound
        .send(Frame::Bundle(forged).encode())
        .await
        .unwrap();
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(
        relay.store_len().await,
        1,
        "forged ack must not drop the bundle"
    );
    assert_eq!(relay.stats().await.acks_rejected, 1);
    assert_eq!(a.stats().await.outstanding, 1, "sender not fooled either");
    while attacker.outbound.try_recv().is_ok() {}

    // The real recipient appears; its ack opens the commitment.
    let cn = Node::start(c, config()).unwrap();
    let mut a_events = a.subscribe();
    let _l2 = MemoryLink::connect(&relay, &cn, 1500);
    wait_for_delivered(&mut a_events, id).await;
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!(relay.store_len().await <= 1);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn groups_fan_out_pairwise_and_share_cards() {
    let (a, b, c) = (node().await, node().await, node().await);
    a.add_contact(b.card().await).await.unwrap();
    a.add_contact(c.card().await).await.unwrap();
    let _l1 = MemoryLink::connect(&a, &b, 1500);
    let _l2 = MemoryLink::connect(&a, &c, 1500);
    let mut b_events = b.subscribe();
    let mut c_events = c.subscribe();

    let gid = a
        .create_group("crew", vec![b.fingerprint().await, c.fingerprint().await])
        .await
        .unwrap();
    for rx in [&mut b_events, &mut c_events] {
        tokio::time::timeout(Duration::from_secs(10), async {
            loop {
                if let Event::GroupInvite { group, .. } = rx.recv().await.unwrap() {
                    assert_eq!(group, gid);
                    break;
                }
            }
        })
        .await
        .unwrap();
    }
    tokio::time::sleep(Duration::from_millis(300)).await;
    // B learned C's card (and A's) through card shares without ever meeting C.
    let mut b_contacts = b.contacts().await;
    b_contacts.sort();
    let mut expected = vec![a.fingerprint().await, c.fingerprint().await];
    expected.sort();
    assert_eq!(b_contacts, expected);
    assert_eq!(b.group(gid).await.unwrap().members.len(), 3);

    // B writes to the group; A and C receive it (B->C travels via A, which
    // carries it without being able to read it).
    let mut a_events = a.subscribe();
    let ids = b.send_group_text(gid, b"all hands").await.unwrap();
    assert_eq!(ids.len(), 2);
    for rx in [&mut a_events, &mut c_events] {
        tokio::time::timeout(Duration::from_secs(10), async {
            loop {
                if let Event::GroupMessage {
                    group,
                    from,
                    plaintext,
                    ..
                } = rx.recv().await.unwrap()
                {
                    assert_eq!(group, gid);
                    assert_eq!(from, b.fingerprint().await);
                    assert_eq!(plaintext, b"all hands");
                    break;
                }
            }
        })
        .await
        .unwrap();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn state_survives_a_restart() {
    let dir = std::env::temp_dir().join(format!("meshlink-restart-{}", rand::random::<u64>()));
    let state = dir.join("mesh.bin");
    let courier_id = identity("courier");
    let courier_blob = courier_id.export().unwrap();
    let (a, c) = (node().await, node().await);
    a.add_contact(c.card().await).await.unwrap();

    let id;
    {
        let courier = Node::builder(courier_id)
            .config(config())
            .persistence(Box::new(FilePersistence::new(&state)))
            .start()
            .unwrap();
        courier.add_contact(a.card().await).await.unwrap();
        let link = MemoryLink::connect(&a, &courier, 1500);
        id = a
            .send_text(c.fingerprint().await, b"across a reboot")
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert_eq!(courier.store_len().await, 1);
        link.disconnect();
        courier.flush().await.unwrap();
        // courier dropped here: process "exits"
    }
    tokio::time::sleep(Duration::from_millis(100)).await;

    let mut rng = rand::rngs::OsRng.unwrap_err();
    let courier_again = MeshIdentity::import(&courier_blob, &mut rng).unwrap();
    let courier = Node::builder(courier_again)
        .config(config())
        .persistence(Box::new(FilePersistence::new(&state)))
        .start()
        .unwrap();
    assert_eq!(courier.store_len().await, 1, "carried bundle reloaded");
    assert_eq!(courier.contacts().await, vec![a.fingerprint().await]);
    let mut c_events = c.subscribe();
    let mut a_events = a.subscribe();
    let _l = MemoryLink::connect(&courier, &c, 1500);
    let (text, _) = wait_for_message(&mut c_events).await;
    assert_eq!(text, b"across a reboot");
    let _l2 = MemoryLink::connect(&courier, &a, 1500);
    wait_for_delivered(&mut a_events, id).await;
    let _ = std::fs::remove_dir_all(dir);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn floods_are_bounded_and_do_not_starve_real_traffic() {
    let relay = Node::start(
        identity("relay"),
        NodeConfig {
            store_bytes: 200_000,
            store_src_quota_bytes: 20_000,
            ..config()
        },
    )
    .unwrap();
    let mut hostile = relay.attach_link(1500);
    // 1. Bundle flood from one source: bounded by the per-source quota and rate.
    for i in 0..500u32 {
        let mut b = Bundle::new(
            BundleKind::Message,
            [0xEE; 16],
            [0x11; 16],
            3600,
            7,
            [0; 16],
            vec![0; 1000],
        )
        .unwrap();
        b.nonce = i as u64;
        hostile
            .inbound
            .send(Frame::Bundle(b).encode())
            .await
            .unwrap();
    }
    // 2. Fragment flood: hundreds of half-finished bundles.
    for i in 0..600u64 {
        let mut id = [0u8; 16];
        id[..8].copy_from_slice(&i.to_be_bytes());
        let f = Frame::Fragment {
            id,
            index: 0,
            total: 200,
            data: vec![1; 100],
        };
        hostile.inbound.send(f.encode()).await.unwrap();
    }
    // 3. Garbage and oversize frames.
    hostile.inbound.send(vec![0xFF; 9000]).await.unwrap();
    hostile.inbound.send(vec![1, 2, 3]).await.unwrap();
    // 4. Bundles from the future and with absurd lifetimes.
    let mut future = Bundle::new(
        BundleKind::Message,
        [1; 16],
        [2; 16],
        60,
        7,
        [0; 16],
        vec![0; 10],
    )
    .unwrap();
    future.created_at += 100_000;
    hostile
        .inbound
        .send(Frame::Bundle(future).encode())
        .await
        .unwrap();
    tokio::time::sleep(Duration::from_millis(500)).await;
    while hostile.outbound.try_recv().is_ok() {}

    let s = relay.stats().await;
    assert!(s.store_bytes <= 200_000);
    assert!(
        s.store_bytes <= 20_000 + 1100,
        "one source cannot exceed its quota: {}",
        s.store_bytes
    );
    assert!(s.bundles_dropped_rate + s.bundles_dropped_quota > 0);
    assert!(s.frames_dropped_invalid >= 2);
    assert!(s.bundles_dropped_invalid >= 1);

    // Real traffic still flows through the same relay.
    let (a, c) = (node().await, node().await);
    a.add_contact(c.card().await).await.unwrap();
    let _l1 = MemoryLink::connect(&a, &relay, 1500);
    let _l2 = MemoryLink::connect(&relay, &c, 1500);
    let mut c_events = c.subscribe();
    a.send_text(c.fingerprint().await, b"still works")
        .await
        .unwrap();
    let (text, _) = wait_for_message(&mut c_events).await;
    assert_eq!(text, b"still works");
}
