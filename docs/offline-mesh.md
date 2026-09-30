# Offline transport: `meshlink`

`libs/libsignal/rust/meshlink` is a store-carry-forward transport that carries
Signal Protocol ciphertext over links that are not the internet: Bluetooth LE
between phones, LoRa radios speaking KISS (RNode firmware), serial cables, a
LAN, or a gateway with a satellite modem. It ships inside the libsignal
library the apps already bundle and is exposed to Kotlin, Swift and
TypeScript through libsignal's bridge, so it reaches devices in an ordinary
app update behind a feature flag (`mesh.transport`).

This document says exactly what it replaces, what it keeps, what is tested,
and what still has to be done on each device platform.

## 1. What Signal needs the internet for, and what meshlink does about it

| Signal function | Why it needs a server today | meshlink |
|---|---|---|
| Encrypting a message | Nothing. PQXDH + Double Ratchet run on the device (libsignal). | Unchanged. The apps encrypt with their own session store; meshlink only moves the ciphertext. |
| Starting a session with someone new | Fetch their prekey bundle from the chat server. | The bundle travels with the person as a signed **contact card** (QR code, or a broadcast **beacon**). |
| Knowing who someone is | Phone number / ACI assigned by the server. | The **device fingerprint**: 16 bytes of SHA-256 over the Signal identity key. Nobody assigns it; nobody can revoke it. |
| Verifying who someone is | Key transparency (server-audited). | A 60-digit **safety number** computed from the two cards, compared in person or by QR. |
| Delivering a message while the recipient is offline | Server-side message queue. | Every node that sees a bundle **carries** it until it expires or is acknowledged, and hands it to every new neighbour. Carry stores persist across reboots. |
| Reaching someone out of radio range | TCP to a data centre. | **Multi-hop relay** through nodes that cannot read the traffic, fragmentation for LoRa MTUs, TCP links to gateways. |
| Groups | Group server (zkgroup credentials, sender keys). | **Pairwise mesh groups**: one encrypted copy per member, invites and card shares so members can reach each other. Small groups (up to 32). |
| Storage service, contact discovery, attachments, calls | Server. | **Not provided.** |

## 2. How it maps to the research's four layers

| Research layer | Component in this repository |
|---|---|
| 1. Phone to phone (Bluetooth, hop-limited relay) | `Node` behind the bridge, driven by the platform BLE link (`BleLink` on Android, iOS and Desktop; shared GATT UUIDs `6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e1/-c0e2/-c0e3`). Default `max_hops = 7`. |
| 2. Local mesh relay (LoRa node in the neighbourhood) | The same node with an RNode attached: USB serial on Android, BLE serial on iOS, Web Serial on Desktop, or `meshlinkd` on a Raspberry Pi with the board on `/dev/ttyUSB0`. `kiss` frames; `RadioConfig` sets frequency/bandwidth/SF/CR. |
| 3. Gateway uplink (Iridium SBD, LoRa satellite ground station) | `meshlinkd` with a TCP link to whatever the modem presents (a socket, an SSH forward, a store-and-forward relay). Each bundle is at most 4 kB and self-contained, so an operator can forward it over any channel and re-inject it on the far side. |
| 4. Descent on the other side | Layers 1 and 2 again. |

A phone radio cannot reach a satellite; nothing here pretends to. The gateway
is a separate device with its own modem.

## 3. Wire design

Every field is fixed-width or 16-bit length-prefixed (`src/wire.rs`); nothing
is parsed recursively; every size is bounded before allocation.

### Bundle (`src/bundle.rs`)

```
u8  version = 1
u8  kind        1 Message, 2 Beacon, 3 Ack
16  src         fingerprint
16  dst         fingerprint, or ff..ff for broadcast
u64 created_at  seconds since epoch, rounded down to the minute
u32 ttl_secs    <= 30 days honoured
u8  hops        incremented on every relay (excluded from the id)
u8  max_hops    <= 16 honoured
u64 nonce       random
16  ack_commit  SHA-256("meshlink-ack-v1" || token)[..16]; zero for beacons and acks
u16 len + payload (<= 4096 bytes)
```

* `id = SHA-256("meshlink-bundle-v1" || every field except hops)[..16]`.
* `Message` payload is `[libsignal message type][ciphertext]` (3 = PreKey,
  2 = Whisper). The plaintext inside is an **envelope** (below).
* `Beacon` payload is the sender's contact card; accepted only if the card's
  fingerprint equals `src` and it is newer than the card already held.
* `Ack` payload is `[acked bundle id][token]`. A relay honours it only if
  `ack_commit(token)` matches the message it holds; the sender only if it
  matches the commitment it recorded. Nobody who cannot read the message can
  make the network drop it.

### Envelope (`src/envelope.rs`), inside the ciphertext

```
u8  version = 1
u8  kind   1 text, 2 group text ([group id 16][text]), 3 group invite, 4 card share
16  ack token
u16 len + body (<= 2048)
zero padding to a multiple of 256 bytes
```

### Contact card (`src/identity.rs`)

Registration id, device id, identity key, signed prekey (+ signature), Kyber-1024
prekey (+ signature), issue time, display name (<= 64 bytes), and a signature by
the identity key over all of it. Tampering with the name or the issue time
breaks the card. No one-time prekey: like Signal's last-resort Kyber prekey,
the card's keys may be reused by many initiators; that is the price of having
no server to hand out single-use keys.

### Frames (`src/frame.rs`)

```
Hello    { fingerprint }     on link-up (unauthenticated; informational)
Summary  { ids[] }           "I hold these"  (incremental; full every 4 ticks)
Want     { ids[] }           "send me these"
Bundle   ( bundle )          whole bundle when it fits the MTU
Fragment { id, index, total, data }
```

Minimum MTU 64 bytes; tests run at 200. A relay always delivers to a
neighbour that identifies as the destination, even when the hop budget is
spent (a false claim earns only ciphertext).

### KISS / RNode (`src/kiss.rs`), TCP (`src/transport/tcp.rs`)

KISS framing with a streaming decoder and the RNode command set; a
`RadioConfig` (868.0 MHz, 125 kHz, SF10, CR 4/5, 14 dBm as an EU starting
point; legal band and power are the operator's responsibility). TCP carries
`u16 length + frame`.

## 4. Node behaviour (`src/node.rs`)

```
Node::builder(identity).config(c).protocol_stores(s).persistence(p).start()
Node::builder(identity).external_crypto().persistence(p).start()   // app bridge
node.attach_link_with(LinkOptions{mtu, max_bytes_per_sec, max_frames_per_sec}) -> LinkEndpoint
node.add_contact(card) / contact(fp) / contacts() / safety_number(fp) / rename(name)
node.broadcast_card()
node.send_text(fp, bytes)                       // internal crypto
node.prepare_text(fp, bytes) -> Prepared{to, commit, plaintext}; node.send_ciphertext(to, commit, type, ct)
node.create_group(name, members) / send_group_text(gid, bytes)      (+ prepare_* twins)
node.deliver_plaintext(bundle_id, plaintext) / defer(bundle_id)      // external crypto
node.subscribe() -> Event::{Ciphertext, Message, GroupMessage, GroupInvite, Contact, Delivered, Neighbour, LinkClosed}
node.stats() / flush()
```

Defaults (`NodeConfig`): 7 hops, 7-day message TTL, 24-hour acks and beacons
(beacons 3 hops), 15 s anti-entropy interval, 8 MiB carry store with a 1 MiB
per-source quota, 1024-frame link queues, 120 s reassembly timeout, 10 new
bundles/s per source (burst 600), 5 decrypts/s per source (burst 200), 1
beacon per 20 s per source (burst 3), 10 000 learned contacts, 1 000 groups.

Messages for us that cannot be decrypted yet (a ratchet message that overtook
its session-starting message on another path) are deferred and retried when a
session from that sender appears and on every tick; they are never lost.

## 5. Threat model and what bounds it

| Pattern | Countermeasure |
|---|---|
| Forged acknowledgement to suppress delivery | Ack must open the message's commitment; forged acks are counted and dropped. |
| Renamed or replayed contact card | Whole-card signature including name and issue time; only newer cards replace older ones. |
| Beacon flood / contact-table exhaustion | Per-source beacon rate limit; learned contacts capped with oldest-first eviction; explicit contacts pinned. |
| Carry-store exhaustion by one sender | Per-source byte quota, heaviest-source-first eviction, our own bundles exempt. |
| Fragment bombs | Per-link cap on half-assembled bundles, per-fragment and per-bundle size caps, timeout, duplicate fragments free. |
| Summary/Want floods, frame floods | Per-link inbound frame rate and outbound byte budget; 8 KiB frame cap; bounded known-id sets. |
| Decryption CPU exhaustion (garbage PreKey messages) | Per-source decrypt rate limit; Kyber decapsulation only after the limit. |
| Future timestamps / infinite lifetimes / hop games | Clock skew <= 5 min, TTL <= 30 days, hops <= 16, validated before any work. |
| Seen-set growth | Hard cap (200k ids) with ordered expiry. |
| Traffic analysis | Padded envelopes (256-byte blocks), minute-granular timestamps, identical-looking bundles for text/group/invite/share. Source and destination fingerprints remain visible to relays (needed for routing); per-conversation pseudonyms are the next step. |
| Malicious relay dropping traffic (black hole) | Epidemic multi-path delivery; acks let the sender see what arrived. |
| Identity substitution (MITM on first contact) | Same as Signal: trust on first use plus safety-number verification in person. |
| Post-quantum | PQXDH with Kyber-1024 in every card; nothing new to add here. |
| Code download / remote update | None. The transport ships in the app; there is no mechanism to fetch code. |

Not covered: jamming, RF fingerprinting of radios, physical seizure of a
relay (it holds only ciphertext and its own keys).

## 6. Performance

There is no central component, so "how many users" is the wrong question:
each node handles its own neighbourhood in bounded memory. Measured here in
unoptimised debug builds:

| Measurement | Result |
|---|---|
| Single node ingesting bundles from one link | about 15 000 bundles/s |
| 64 nodes, ring + 64 random chords, 200-byte frames, 128 messages | all delivered, zero duplicates, largest carry store 20 kB |
| Per-bundle state | 74-byte header + payload in the store; 24 bytes per remembered id |

The radio, not the node, is the limit: LoRa at SF10 carries a few hundred
bytes per second; BLE tens of kB/s. Text works; attachments do not (4 kB
bundle cap, deliberate).

## 7. What is tested

`cargo test -p meshlink` (unit + end-to-end + stress, all passing here):

| Test | Proves |
|---|---|
| `direct_message_over_one_link` | PQXDH session from a card, decrypt, ack, ratchet continues, unknown-sender flag, safety numbers agree |
| `relayed_through_a_node_that_cannot_read_it` | Two hops over 200-byte frames; relay never decrypts; relay drains after the ack |
| `store_carry_forward_across_a_gap_in_time` | Courier receives, loses the sender, later meets the recipient |
| `beacon_discovery_then_reply` | No prior exchange; beacon over 200-byte frames; reply |
| `unknown_recipient_is_rejected_and_duplicates_are_suppressed` | Three parallel paths deliver once |
| `forged_acks_are_ignored_and_real_ones_drain_relays` | Forged ack rejected at relay and sender; real ack honoured |
| `groups_fan_out_pairwise_and_share_cards` | Invite + card shares; a member who never met another can write to it; group text fans out via a relay |
| `state_survives_a_restart` | Courier restarts from its persisted identity and carry store, then delivers |
| `floods_are_bounded_and_do_not_starve_real_traffic` | Bundle, fragment, garbage and future-timestamp floods stay within quota; real traffic still flows |
| `external_crypto_mode_round_trip_with_app_side_sessions` | The bridge flow with app-side stores, including defer and re-announce |
| `tcp_transport_between_two_nodes` | TCP link end to end |
| `sixty_four_nodes_lora_frames`, `ingest_throughput_single_node` | Section 6 |
| unit tests | wire, bundle validation and commitments, envelope padding, fragmentation and flood bounds, KISS, rate limiters, store quotas and caps, card tamper detection, identity export/import/install, groups, snapshots |

## 8. Bridge (apps)

Functions in `rust/bridge/shared/src/mesh.rs` appear as
`Native.MeshNode_*` (Kotlin, TypeScript) and `signal_mesh_node_*` (Swift).
The node runs its own two-thread tokio runtime; the app drives it from a
background thread with blocking polls:

```
MeshNode_New(identity, statePath, externalCrypto=true, antiEntropySecs)
MeshNode_AttachLink(mtu, bytesPerSec, framesPerSec) -> link
MeshNode_LinkWrite(link, frameFromWire); MeshNode_LinkRead(link, timeoutMs) -> frameToWire
MeshNode_NextEvent(timeoutMs) -> encoded event
MeshNode_PrepareText / SendCiphertext / DeliverPlaintext / Defer
MeshNode_AddContact / Contact / Contacts / SafetyNumber / BroadcastCard / Rename
MeshNode_PrepareGroupCreate / PrepareGroupText / Groups / Group
MeshNode_Stats / Flush
MeshIdentity_FromIdentityKeyPair / Export / Import / Card / SignedPreKeyRecord / KyberPreKeyRecord
MeshContactCard_Decode / FromBase64 / PreKeyBundle / AddressName / SafetyNumber / ...
```

Encodings (`rust/bridge/shared/types/src/mesh.rs`): an event is a tag byte
(1 Ciphertext, 2 Message, 3 GroupMessage, 4 GroupInvite, 5 Contact,
6 Delivered, 7 Neighbour, 8 LinkClosed) followed by its fixed fields, with
variable fields u16-length-prefixed; a prepared list is `u16 count` then
`[to 16][commit 16][plaintext u16-len]`; stats are eighteen big-endian u64s in
the order of the `Stats` fields.

The app's session for a mesh contact is stored under
`ProtocolAddress(name = fingerprint hex, deviceId = 1)`, and the mesh prekey
records returned by `MeshIdentity_*PreKeyRecord` must be saved in the app's
own prekey stores so first messages decrypt.

## 9. Platform status

| Piece | Android | iOS | Desktop |
|---|---|---|---|
| Bridge declarations | generated `Native.kt` | generated `signal_ffi.h` | generated `Native.d.ts` |
| Identity from the app's ACI key, prekeys installed | written, uncompiled | written, uncompiled | written, standalone type-check |
| Mesh contacts table + recipient convention (fingerprint bytes as UUID) | written | written | written |
| Encrypt/decrypt with the app's stores | written | written | written |
| Event pump, incoming insert, outbox hook in the send path | written | written | written |
| BLE link (shared GATT UUIDs) | GATT server + client | CoreBluetooth both roles | Web Bluetooth |
| Radio link | USB CDC-ACM + KISS | RNode BLE serial + KISS | Web Serial + KISS |
| Gateway link | (via radio/BLE) | (via radio/BLE) | TCP to `meshlinkd` |
| Scene indicator states Mesh/Carrying | wired | wired | wired |
| Settings (toggle, my QR, scan/paste card, contacts, stats) | written | written | written |

Two conventions the platform code settled on, both local to a device and
invisible on the wire: (1) the app's Signal session for a mesh contact is
keyed by the contact's fingerprint-derived service id (Android, iOS) or that
id with the UUID version/variant bits forced to v4 (Desktop, whose
conversation validator demands it), not by the bare hex name; (2) the
fingerprint-to-contact mapping always goes through the mesh contacts table,
never by parsing the id back. Desktop's Web Bluetooth is central-only, so a
Desktop and a phone connect with the phone as peripheral.

None of the platform code could be compiled in this environment (no Android
SDK, no Xcode, no Desktop dependency install beyond a standalone type-check of
the new TypeScript, which passed, and 16 unit tests for its decoders). It is written against the
bridge names above and must be built and run on real devices; the first
build will surface mismatches. See `docs/patches.md` for the file lists.

## 10. Gateway daemon

```
meshlinkd --state /var/lib/meshlink --name "hut gateway" \
          --listen 0.0.0.0:7788 --connect 203.0.113.9:7788 \
          --serial /dev/ttyUSB0 --lora-eu --beacon 600 --print-card
```

Identity and carry store persist under `--state`; `--print-card` prints the
base64 card for QR import. Set the serial speed first
(`stty -F /dev/ttyUSB0 115200 raw -echo`). Peers on TCP are ordinary relays;
put the daemon behind whatever the satellite terminal or uplink presents.

## 11. Limits, stated plainly

* No storage service, contact discovery, attachments or calls without a
  server. Groups are pairwise and small.
* Bandwidth is the radio's. The 4 kB bundle cap is deliberate.
* Epidemic routing floods; quotas and hop limits bound it, but dense
  deployments want smarter routing (PRoPHET/gradient) inside
  `Node::push_to_links`; the wire format does not need to change.
* Relays see source and destination fingerprints.
* Hello frames are unauthenticated; they only name a neighbour.
* No radio, phone or satellite hardware was available here. Everything above
  the byte pipe is tested; the byte pipes themselves are not.

## 12. Files

```
libs/libsignal/rust/meshlink/
  src/lib.rs bundle.rs envelope.rs wire.rs        formats
  src/identity.rs stores.rs                       cards, identity, protocol-store seam
  src/store.rs limits.rs frame.rs kiss.rs         carry store, rate limits, link layer, KISS
  src/group.rs persist.rs                         groups, snapshots
  src/node.rs                                     the node
  src/transport/{mod,memory,tcp}.rs               links
  src/bin/meshlinkd.rs                            gateway daemon
  tests/mesh.rs tests/stress.rs
libs/libsignal/rust/bridge/shared/src/mesh.rs, shared/types/src/mesh.rs   bridge
```
