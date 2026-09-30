//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! The v3 features end to end: attachments over a lossy LoRa-sized link,
//! call signalling, nearby discovery, encrypted backups and the loopback
//! self-test.

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use meshlink::attachment::{AttachmentKind, CHUNK_DATA};
use meshlink::envelope::{Envelope, EnvelopeKind};
use meshlink::transport::memory::MemoryLink;
use meshlink::{Error, Event, FilePersistence, LinkOptions, MeshIdentity, Node, NodeConfig};
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

fn node(name: &str) -> Node {
    Node::start(identity(name), config()).unwrap()
}

/// Connects `a` and `b` with a pipe that silently loses every `drop_every`th
/// frame in each direction: a radio with a bad day.
fn lossy_link(
    a: &Node,
    b: &Node,
    mtu: usize,
    drop_every: u64,
) -> (Vec<tokio::task::JoinHandle<()>>, Arc<AtomicU64>) {
    let ea = a.attach_link_with(LinkOptions::new(mtu));
    let eb = b.attach_link_with(LinkOptions::new(mtu));
    let dropped = Arc::new(AtomicU64::new(0));
    let mut tasks = Vec::new();
    for (mut out, inbound) in [(ea.outbound, eb.inbound), (eb.outbound, ea.inbound)] {
        let dropped = dropped.clone();
        tasks.push(tokio::spawn(async move {
            let mut n = 0u64;
            while let Some(frame) = out.recv().await {
                n += 1;
                if n.is_multiple_of(drop_every) {
                    dropped.fetch_add(1, Ordering::Relaxed);
                    continue;
                }
                if inbound.send(frame).await.is_err() {
                    break;
                }
            }
        }));
    }
    (tasks, dropped)
}

async fn wait<T>(
    rx: &mut tokio::sync::broadcast::Receiver<Event>,
    secs: u64,
    pick: impl Fn(Event) -> Option<T>,
) -> T {
    tokio::time::timeout(Duration::from_secs(secs), async {
        loop {
            match rx.recv().await {
                Ok(e) => {
                    if let Some(v) = pick(e) {
                        return v;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(e) => panic!("{e}"),
            }
        }
    })
    .await
    .expect("event should arrive")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn attachment_round_trip_over_a_lossy_lora_link() {
    let (a, b) = (node("a"), node("b"));
    a.add_contact(b.card().await).await.unwrap();
    let mut b_events = b.subscribe();
    let mut b_progress = b.subscribe();
    let mut a_events = a.subscribe();
    let (_tasks, dropped) = lossy_link(&a, &b, 200, 9);

    let data: Vec<u8> = (0..(CHUNK_DATA * 6 + 321))
        .map(|i| u8::try_from(i * 7 % 256).expect("< 256"))
        .collect();
    let ids = a
        .send_attachment(
            b.fingerprint().await,
            AttachmentKind::Image,
            "cat.jpg",
            "image/jpeg",
            &data,
        )
        .await
        .unwrap();
    assert_eq!(ids.len(), 8, "manifest plus seven chunks");

    let (from, kind, name, mime, got) = wait(&mut b_events, 60, |e| match e {
        Event::Attachment {
            from,
            kind,
            name,
            mime,
            data,
            ..
        } => Some((from, kind, name, mime, data)),
        _ => None,
    })
    .await;
    assert_eq!(from, a.fingerprint().await);
    assert_eq!(kind, AttachmentKind::Image as u8);
    assert_eq!(name, "cat.jpg");
    assert_eq!(mime, "image/jpeg");
    assert_eq!(got, data);
    assert!(
        dropped.load(Ordering::Relaxed) > 0,
        "the link really lost frames"
    );

    // Progress was reported chunk by chunk and ends complete.
    let mut progress = Vec::new();
    while let Ok(e) = b_progress.try_recv() {
        if let Event::AttachmentProgress {
            transfer,
            received,
            total,
            ..
        } = e
        {
            assert_eq!(transfer, meshlink::attachment::transfer_id(&data));
            progress.push((received, total));
        }
    }
    assert!(progress.len() >= 7, "{progress:?}");
    assert_eq!(progress.last(), Some(&(7, 7)), "{progress:?}");
    // Every bundle of the transfer is eventually acknowledged to the sender.
    let mut pending: std::collections::HashSet<[u8; 16]> = ids.iter().copied().collect();
    tokio::time::timeout(Duration::from_secs(60), async {
        while !pending.is_empty() {
            if let Ok(Event::Delivered { bundle_id }) = a_events.recv().await {
                pending.remove(&bundle_id);
            }
        }
    })
    .await
    .expect("all pieces acknowledged");

    // The same file again is deduplicated by transfer id: acknowledged, not
    // delivered twice.
    let again = a
        .send_attachment(
            b.fingerprint().await,
            AttachmentKind::Image,
            "cat.jpg",
            "image/jpeg",
            &data,
        )
        .await
        .unwrap();
    let mut pending: std::collections::HashSet<[u8; 16]> = again.iter().copied().collect();
    tokio::time::timeout(Duration::from_secs(60), async {
        while !pending.is_empty() {
            if let Ok(Event::Delivered { bundle_id }) = a_events.recv().await {
                pending.remove(&bundle_id);
            }
        }
    })
    .await
    .expect("duplicate pieces acknowledged");
    tokio::time::sleep(Duration::from_millis(200)).await;
    let mut second = 0;
    while let Ok(e) = b_events.try_recv() {
        if matches!(e, Event::Attachment { .. }) {
            second += 1;
        }
    }
    assert_eq!(second, 0, "duplicate attachment delivered twice");

    // Limits: empty and oversized attachments are refused before anything is sent.
    assert!(
        a.send_attachment(b.fingerprint().await, AttachmentKind::File, "e", "x", &[])
            .await
            .is_err()
    );
    let huge = vec![0u8; meshlink::attachment::MAX_ATTACHMENT_BYTES + 1];
    assert!(matches!(
        a.prepare_attachment(b.fingerprint().await, AttachmentKind::File, "h", "x", &huge)
            .await,
        Err(Error::TooLarge(_, _))
    ));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn prepared_attachment_list_has_the_bridge_shape() {
    let a = Node::builder(identity("app"))
        .config(config())
        .external_crypto()
        .start()
        .unwrap();
    let b = node("b");
    a.add_contact(b.card().await).await.unwrap();
    let data = vec![5u8; CHUNK_DATA + 1];
    let prepared = a
        .prepare_attachment(
            b.fingerprint().await,
            AttachmentKind::Voice,
            "note.aac",
            "audio/aac",
            &data,
        )
        .await
        .unwrap();
    assert_eq!(prepared.len(), 3);
    let b_fp = b.fingerprint().await;
    assert!(prepared.iter().all(|p| p.to == b_fp));
    let kinds: Vec<EnvelopeKind> = prepared
        .iter()
        .map(|p| Envelope::decode(&p.plaintext).unwrap().kind)
        .collect();
    assert_eq!(
        kinds,
        vec![
            EnvelopeKind::AttachmentManifest,
            EnvelopeKind::AttachmentChunk,
            EnvelopeKind::AttachmentChunk
        ]
    );
    let manifest = meshlink::attachment::Manifest::decode(
        &Envelope::decode(&prepared[0].plaintext).unwrap().body,
    )
    .unwrap();
    assert_eq!(manifest.transfer, meshlink::attachment::transfer_id(&data));
    assert_eq!(manifest.chunks, 2);
    assert!(prepared.iter().all(|p| p.plaintext.len() <= 9 * 256));
    // Commitments are distinct per piece.
    let commits: std::collections::HashSet<_> = prepared.iter().map(|p| p.commit).collect();
    assert_eq!(commits.len(), 3);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn call_signal_round_trip_is_short_lived_and_urgent() {
    let (a, b) = (node("a"), node("b"));
    a.add_contact(b.card().await).await.unwrap();
    b.add_contact(a.card().await).await.unwrap();
    let mut b_events = b.subscribe();
    let _link = MemoryLink::connect(&a, &b, 200);

    let offer = b"{\"type\":\"offer\",\"sdp\":\"v=0 ...\"}".to_vec();
    let id = a
        .send_call_signal(b.fingerprint().await, &offer)
        .await
        .unwrap();
    let bundle = a.bundle(id).await.expect("sender holds it until acked");
    assert_eq!(bundle.ttl_secs, 90);
    assert!(bundle.is_urgent());
    let (from, bundle_id, data) = wait(&mut b_events, 10, |e| match e {
        Event::CallSignal {
            from,
            bundle_id,
            data,
        } => Some((from, bundle_id, data)),
        _ => None,
    })
    .await;
    assert_eq!(from, a.fingerprint().await);
    assert_eq!(bundle_id, id);
    assert_eq!(data, offer);

    // External-crypto path: the prepared commitment carries the urgency
    // through the app's send_ciphertext call.
    let app = Node::builder(identity("app"))
        .config(config())
        .external_crypto()
        .start()
        .unwrap();
    app.add_contact(b.card().await).await.unwrap();
    let p = app
        .prepare_call_signal(b.fingerprint().await, b"hangup")
        .await
        .unwrap();
    assert_eq!(
        Envelope::decode(&p.plaintext).unwrap().kind,
        EnvelopeKind::CallSignal
    );
    let id = app
        .send_ciphertext(p.to, p.commit, 2, &[0u8; 40])
        .await
        .unwrap();
    assert_eq!(app.bundle(id).await.unwrap().ttl_secs, 90);
    let text = app
        .prepare_text(b.fingerprint().await, b"hi")
        .await
        .unwrap();
    let id = app
        .send_ciphertext(text.to, text.commit, 2, &[0u8; 40])
        .await
        .unwrap();
    assert_eq!(
        app.bundle(id).await.unwrap().ttl_secs,
        config().message_ttl_secs
    );
    // Oversized signalling is refused.
    assert!(
        app.prepare_call_signal(b.fingerprint().await, &vec![0u8; 3000])
            .await
            .is_err()
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn nearby_lists_cards_and_neighbours_and_persists() {
    let dir = std::env::temp_dir().join(format!("meshlink-nearby-{}", rand::random::<u64>()));
    let a_id = identity("Ada");
    let a_blob = a_id.export().unwrap();
    let a = Node::builder(a_id)
        .config(config())
        .persistence(Box::new(FilePersistence::new(dir.join("a.bin"))))
        .start()
        .unwrap();
    let b = node("Bob");
    let mut a_events = a.subscribe();
    let link = MemoryLink::connect(&a, &b, 200);
    b.broadcast_card().await.unwrap();
    wait(&mut a_events, 10, |e| {
        matches!(e, Event::Contact { .. }).then_some(())
    })
    .await;
    // The beacon may overtake B's hello on the pipe; give the hello a moment.
    let near = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let near = a.nearby().await;
            if near.first().is_some_and(|n| n.direct) {
                return near;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("B becomes a neighbour");
    assert_eq!(near.len(), 1);
    assert_eq!(near[0].fingerprint, b.fingerprint().await);
    assert_eq!(near[0].name, "Bob");
    assert!(near[0].direct, "B is a neighbour right now");
    assert!(near[0].last_seen + 60 > meshlink::now_secs());
    // B heard only A's hello: listed without a name, as a neighbour.
    let near_b = b.nearby().await;
    assert_eq!(near_b.len(), 1);
    assert_eq!(near_b[0].fingerprint, a.fingerprint().await);
    assert_eq!(near_b[0].name, "");
    assert!(near_b[0].direct);

    link.disconnect();
    tokio::time::sleep(Duration::from_millis(100)).await;
    let near = a.nearby().await;
    assert_eq!(near.len(), 1);
    assert!(!near[0].direct, "no longer a neighbour, still seen today");

    // Survives a restart.
    a.flush().await.unwrap();
    drop(a);
    let mut rng = rand::rngs::OsRng.unwrap_err();
    let a2 = Node::builder(MeshIdentity::import(&a_blob, &mut rng).unwrap())
        .config(config())
        .persistence(Box::new(FilePersistence::new(dir.join("a.bin"))))
        .start()
        .unwrap();
    let near = a2.nearby().await;
    assert_eq!(near.len(), 1);
    assert_eq!(near[0].name, "Bob");
    assert!(!near[0].direct);
    let _ = std::fs::remove_dir_all(dir);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn backup_round_trip_and_wrong_passphrase() {
    let (a, b, c) = (node("a"), node("b"), node("c"));
    a.add_contact(b.card().await).await.unwrap();
    a.add_contact(c.card().await).await.unwrap();
    let gid = a
        .create_group("crew", vec![b.fingerprint().await, c.fingerprint().await])
        .await
        .unwrap();
    // A message to C that nobody has carried yet: outstanding and in the store.
    let pending = a
        .send_text(c.fingerprint().await, b"for later")
        .await
        .unwrap();
    let blob = a
        .export_backup("correct horse battery staple")
        .await
        .unwrap();
    assert_eq!(&blob[..4], b"ASHB");
    assert_eq!(blob[4], 1);

    let mut rng = rand::rngs::OsRng.unwrap_err();
    assert!(matches!(
        MeshIdentity::from_backup("wrong", &blob, &mut rng),
        Err(Error::BadPassphrase)
    ));
    let recovered =
        MeshIdentity::from_backup("correct horse battery staple", &blob, &mut rng).unwrap();
    assert_eq!(recovered.fingerprint(), a.fingerprint().await);
    assert_eq!(recovered.card().name, "a");

    // A fresh node from the recovered identity gets everything back.
    let a2 = Node::start(recovered, config()).unwrap();
    assert!(a2.contacts().await.is_empty());
    assert!(matches!(
        a2.import_backup("wrong", &blob).await,
        Err(Error::BadPassphrase)
    ));
    a2.import_backup("correct horse battery staple", &blob)
        .await
        .unwrap();
    let mut contacts = a2.contacts().await;
    contacts.sort();
    let mut expected = vec![b.fingerprint().await, c.fingerprint().await];
    expected.sort();
    assert_eq!(contacts, expected);
    assert_eq!(a2.group(gid).await.unwrap().name, "crew");
    assert!(a2.holds(pending).await, "carried bundle restored");
    assert!(a2.outstanding().await.contains(&pending));
    // ... and delivers the restored message once it meets C.
    let mut c_events = c.subscribe();
    let _l = MemoryLink::connect(&a2, &c, 1500);
    let text = wait(&mut c_events, 10, |e| match e {
        Event::Message { plaintext, .. } => Some(plaintext),
        _ => None,
    })
    .await;
    assert_eq!(text, b"for later");

    // Somebody else's backup is refused.
    assert!(matches!(
        b.import_backup("correct horse battery staple", &blob).await,
        Err(Error::IdentityMismatch)
    ));
    // Garbage is a wire error, not a panic.
    assert!(b.import_backup("x", b"not a backup at all").await.is_err());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn self_test_passes_and_leaves_no_trace() {
    // Internal-crypto node (meshlinkd style).
    let a = node("a");
    let report = a.self_test(Duration::from_secs(20)).await;
    eprintln!("{report}");
    assert!(report.contains("RESULT: PASS"), "{report}");
    assert!(!report.contains("FAIL "), "{report}");
    assert!(
        a.contacts().await.is_empty(),
        "test peer not kept as a contact"
    );
    assert!(
        a.nearby().await.is_empty(),
        "test peer not listed as nearby"
    );
    assert_eq!(a.store_len().await, 0, "no test bundles left behind");
    assert_eq!(a.stats().await.links, 0);

    // External-crypto node (the apps): relays between two throwaway peers.
    let app = Node::builder(identity("app"))
        .config(config())
        .external_crypto()
        .start()
        .unwrap();
    let report = app.self_test(Duration::from_secs(20)).await;
    eprintln!("{report}");
    assert!(report.contains("RESULT: PASS"), "{report}");
    assert!(report.contains("relayed"), "{report}");
    assert!(app.contacts().await.is_empty());
    assert!(app.nearby().await.is_empty());
    assert_eq!(app.store_len().await, 0);
}
