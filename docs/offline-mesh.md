# Offline transport: `meshlink`

`libs/libsignal/rust/meshlink` is a store-carry-forward transport that carries
Signal Protocol ciphertext over links that are not the internet: Bluetooth LE
between phones, LoRa radios speaking KISS (RNode firmware), serial cables, a
LAN, or a gateway with a satellite modem. It is the "plug-in" that lets an
Asher Messenger build keep working when there is no server and no data.

This document says exactly what it replaces, what it keeps, what is tested,
and what still has to be done on each device platform.

## 1. What Signal needs the internet for, and what meshlink does about it

| Signal function | Why it needs a server today | meshlink |
|---|---|---|
| Encrypting a message | Nothing. PQXDH + Double Ratchet run on the device (libsignal). | Unchanged. meshlink calls the same `libsignal-protocol` code the apps use. |
| Starting a session with someone new | Fetch their prekey bundle from the chat server. | The bundle travels with the person as a signed **contact card** (QR code, or a broadcast **beacon**). |
| Knowing who someone is | Phone number / ACI assigned by the server. | The **device fingerprint**: 16 bytes of SHA-256 over the Signal identity key. Nobody assigns it; nobody can revoke it. |
| Delivering a message while the recipient is offline | Server-side message queue. | Every node that sees a bundle **carries** it until it expires or is acknowledged, and hands it to every new neighbour (epidemic routing with a hop limit). |
| Reaching someone out of radio range | TCP to a data centre. | **Multi-hop relay** through nodes that cannot read the traffic, plus **fragmentation** so a 4 kB bundle fits LoRa-sized frames. |
| Groups, storage service, key transparency, contact discovery | Server. | **Not provided.** See section 7. |

So the honest summary is: encryption and identity never needed the internet;
prekey exchange and delivery did, and meshlink moves both onto people and
radios. The server-only features stay server-only.

## 2. How it maps to the research's four layers

| Research layer | Component in this repository |
|---|---|
| 1. Phone to phone (Bluetooth, hop-limited relay) | `Node` + a platform BLE byte pipe attached with `Node::attach_link`. Default `max_hops = 7`, the same limit the research quotes for bitchat. |
| 2. Local mesh relay (LoRa node in the neighbourhood) | The same `Node` running on a phone or a small computer with an RNode attached over USB/BLE. `kiss` encodes/decodes the frames; `RadioConfig` produces the frequency/bandwidth/SF/CR commands. |
| 3. Gateway uplink (Iridium SBD, LoRa satellite ground station) | A node whose extra link is the modem. Each **bundle** is at most 4 kB and self-contained, so an operator can forward it over any store-and-forward channel (Iridium SBD messages, TinyGS-style uplinks) and re-inject it on the other side. No special code path: a gateway is a link. |
| 4. Descent on the other side | Same as layer 1 and 2 in the destination area. |

The research's point that a phone radio cannot reach a satellite is respected:
nothing here pretends to; the gateway is a separate device with its own modem.

## 3. Wire design

Every field is fixed-width or 16-bit length-prefixed (`src/wire.rs`); there is
no protobuf, no JSON, no allocation on garbage input beyond the declared
length.

### Bundle (`src/bundle.rs`)

```
u8  version = 1
u8  kind        1 = Message, 2 = Beacon, 3 = Ack
16  src         fingerprint
16  dst         fingerprint, or ff..ff for broadcast
u64 created_at  seconds since epoch
u32 ttl_secs
u8  hops        incremented on every relay (excluded from the id)
u8  max_hops
16  nonce       random
u16 len + payload (<= 4096 bytes)
```

* `id = SHA-256("meshlink-bundle-v1" || every field except hops)[..16]`.
  Two copies of the same bundle arriving over two paths have the same id and
  are delivered once.
* `Message` payload is `[libsignal message type][ciphertext]`. Type 3 is a
  PreKeySignalMessage (first message of a session), type 2 a SignalMessage.
* `Beacon` payload is the sender's encoded contact card. Beacons are only
  accepted if the card's fingerprint equals `src` (a node cannot forge someone
  else's card) and if they are newer than the card already held.
* `Ack` payload is the 16-byte id of a delivered message. Acks are broadcast
  so relays can drop what they carry; only the sender turns one into
  `Event::Delivered`.

Fingerprint: `SHA-256("meshlink-fingerprint-v1" || identity_key_bytes)[..16]`,
shown as 32 hex characters. It is the address of a device, not a person.

### Contact card (`src/identity.rs`)

Version byte, registration id, device id, identity key, signed prekey id +
key + signature, Kyber-1024 prekey id + key + signature, `created_at`. Both
signatures are checked on decode; a tampered card is rejected before it
reaches the protocol code. Exported as URL-safe base64 for QR codes.

Trade-off, stated plainly: there is no one-time prekey. Like Signal's
last-resort Kyber prekey, the card's keys can be used by many initiators. That
is the price of not having a server hand out single-use keys. Forward secrecy
after the first ratchet step is unaffected.

### Frames (`src/frame.rs`)

Link-level protocol between two neighbours:

```
Hello    { fingerprint }         sent on link-up
Summary  { ids[] }               "these are the bundles I hold"
Want     { ids[] }               "send me these"
Bundle   ( bundle )              whole bundle, when it fits the MTU
Fragment { id, index, total, data }
```

Anti-entropy: on link-up and every `anti_entropy_interval` a node sends its
summary; the neighbour asks only for ids it has not seen; new bundles are
pushed immediately to every other link. Summaries and wants are chunked to the
MTU; bundles larger than the MTU are fragmented (22 bytes overhead per
fragment) and reassembled with a timeout. Minimum supported MTU is 64 bytes;
tests run at 200 bytes, which is LoRa territory.

### KISS / RNode (`src/kiss.rs`)

Standard KISS framing (FEND/FESC escaping) with a streaming decoder that copes
with frames split across arbitrary read boundaries and with line noise between
frames. The RNode command numbers (frequency, bandwidth, TX power, spreading
factor, coding rate, radio state, detect, ready) are constants; the code does
not open a serial port itself. The platform hands meshlink a byte pipe, which
keeps the crate free of any device access and keeps hardware handling in the
app where permissions live.

`RadioConfig::EU_LONG_RANGE` is 868.0 MHz, 125 kHz, SF 10, CR 4/5, 14 dBm as a
starting point; the legal band and power are the operator's responsibility and
must be set per region.

## 4. Node behaviour (`src/node.rs`)

```
Node::start(identity, config)      -> Node        (spawns the anti-entropy tick)
node.attach_link(mtu)              -> LinkEndpoint (inbound sender, outbound receiver)
node.add_contact(card)             -> fingerprint  (from a QR code)
node.broadcast_card()              -> bundle id    (beacon)
node.send_text(fingerprint, bytes) -> bundle id    (encrypts, stores, pushes)
node.subscribe()                   -> Event stream: Message / Contact / Delivered / Neighbour
```

* A node that receives a message addressed to itself decrypts it with its own
  libsignal store, emits `Event::Message`, and broadcasts an `Ack`.
* A node that receives anything else stores it (deduplicated, TTL-bounded,
  byte-budgeted with nearest-expiry eviction), bumps `hops`, and forwards it
  if `hops < max_hops`.
* Relays never hold key material for traffic that is not theirs. The test
  `relayed_through_a_node_that_cannot_read_it` asserts the relay emits no
  plaintext event and drops the bundle once the ack arrives.

Defaults (`NodeConfig`): 7 hops, 7-day message TTL, 24-hour beacon TTL with 3
hops, 15 s anti-entropy interval, 8 MiB carry store, 256-frame link queues,
120 s reassembly timeout.

## 5. What is tested

`cargo test -p meshlink` (18 tests, all passing in this environment):

| Test | Proves |
|---|---|
| `direct_message_over_one_link` | PQXDH session start from a card, decrypt on the far side, ack back to the sender, second message on the established ratchet |
| `relayed_through_a_node_that_cannot_read_it` | Two hops over 200-byte frames; relay never decrypts; relay store drains after the ack |
| `store_carry_forward_across_a_gap_in_time` | Courier receives a bundle, loses the sender, later meets the recipient, delivers |
| `beacon_discovery_then_reply` | No prior card exchange; beacon over a 200-byte link; recipient learns the card and replies |
| `unknown_recipient_is_rejected_and_duplicates_are_suppressed` | Sending to a fingerprint without a card fails; three parallel paths deliver exactly once |
| unit tests | wire round trips, bundle id stability and expiry, size limits, fragmentation/reassembly and timeouts, KISS escaping across chunk boundaries and noise, radio config frames, store dedup/expiry/eviction, card tamper detection |

Everything above runs over the in-memory link (`transport/memory.rs`), which
is a pair of byte pipes with an MTU. That exercises every layer of meshlink;
what it cannot exercise is a real radio.

## 6. "Plug-in and play": how it reaches existing phones

Phones cannot download and run new code outside an app update; both app
stores forbid it and the apps' own integrity checks would reject it. So
"plug-in and play" means:

1. meshlink ships **inside the normal libsignal build** the apps already
   bundle (it is a workspace member; the bridge layers can expose it to
   Java/Swift/Node the same way the other crates are exposed).
2. The transport is **behind a feature flag** (`mesh.transport`), off by
   default, so an app update that carries it changes nothing until the user
   turns it on or the app detects it has no connectivity.
3. Radios are **hardware the user pairs**, not software the user installs:
   an RNode over USB serial or BLE, or another phone over BLE. The app opens
   the pipe and hands the bytes to meshlink.

Nothing new has to be bought to use layer 1 (phone to phone). A LoRa board
is needed for layers 2 and 3, and a satellite modem or ground station for
layer 3, exactly as the research states.

### Integration seams per client (not yet written)

| Client | Send side | Receive side | Link |
|---|---|---|---|
| Android | `SignalServiceMessageSender`: when the chat socket is down and the recipient has a mesh fingerprint, encrypt via the existing session store and hand the ciphertext to meshlink instead of the REST send | Feed decrypted bytes from `Event::Message` into the existing message processing pipeline as if they arrived in an envelope | BLE GATT service (one write characteristic in, one notify characteristic out, 20-512 byte MTU), USB serial for RNode |
| iOS | `MessageSender` | `MessageReceiver` | CoreBluetooth peripheral + central roles; ExternalAccessory for serial |
| Desktop | `ts/textsecure/SendMessage.ts` | `ts/textsecure/MessageReceiver.ts` | Web Bluetooth is not available in Electron main; use the Node serial port from the main process and pass frames over IPC (the IPC guard from the security audit applies) |

The Rust node uses libsignal's in-memory protocol store. For the apps, the
node must be constructed over the app's own persistent stores so that mesh
sessions and internet sessions with the same contact are one ratchet, not
two. That is a constructor change, not a protocol change.

## 7. Limits, stated plainly

* **No groups, no storage service, no key transparency, no contact discovery.**
  These are server functions and stay off when the server is unreachable.
  Group messaging over mesh would be pairwise fan-out, which is what Signal
  did before sender keys.
* **Bandwidth.** LoRa at SF 10 carries a few hundred bytes per second at best.
  Text works; attachments do not. The 4 kB bundle limit is deliberate.
* **Epidemic routing floods.** With many nodes and long TTLs the carry stores
  fill with other people's traffic. The byte budget and hop limit bound it,
  but a dense deployment wants smarter routing (PRoPHET, gradient), which can
  be added inside `Node::push_to_links` without changing the wire format.
* **Metadata on the air.** Source and destination fingerprints are visible to
  every relay (they have to be, for routing). Content is not. Pseudonymous
  per-conversation addresses would be the next privacy step.
* **Beacons are unauthenticated as to freshness.** A replayed old beacon is
  rejected only if the node already holds a newer card.
* **No hardware was available here.** BLE, serial and satellite links are
  byte pipes the platforms must provide; the protocol above them is what is
  tested.
* **The dependency the auto-mode review declined** was a serial-port crate
  and a command-line binary in this crate. Both are left out on purpose;
  the platform apps own device access.

## 8. Files

```
libs/libsignal/rust/meshlink/
  Cargo.toml
  src/lib.rs          crate docs, Error, complete_now()
  src/wire.rs         fixed-width reader/writer
  src/bundle.rs       Bundle, ids, fingerprints
  src/identity.rs     ContactCard, MeshIdentity
  src/store.rs        BundleStore (carry store)
  src/frame.rs        link frames, fragmentation, Reassembler
  src/kiss.rs         KISS codec, RNode commands, RadioConfig
  src/node.rs         Node, NodeConfig, Event
  src/transport/      LinkEndpoint, MemoryLink (tests)
  tests/mesh.rs       end-to-end mesh scenarios
```
