//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! End-to-end behaviour of a small mesh: multi-hop relay through a node that
//! cannot read the traffic, store-carry-forward across a gap in time, beacon
//! based contact discovery, delivery acknowledgements and small-MTU links.

use std::time::Duration;

use meshlink::transport::memory::MemoryLink;
use meshlink::{Event, MeshIdentity, Node, NodeConfig};
use rand::TryRngCore as _;

async fn node() -> Node {
    let mut rng = rand::rngs::OsRng.unwrap_err();
    let identity = MeshIdentity::generate(&mut rng).unwrap();
    Node::start(
        identity,
        NodeConfig {
            anti_entropy_interval: Duration::from_millis(50),
            ..NodeConfig::default()
        },
    )
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

    // A second message rides the established ratchet (a Whisper message).
    a.send_text(b.fingerprint().await, b"second").await.unwrap();
    let (text, _) = wait_for_message(&mut b_events).await;
    assert_eq!(text, b"second");
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
