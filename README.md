# Asher Messenger platform

A multi-brand, self-hostable private messaging platform built on the complete
open-source Signal stack. Every Signal component (three clients, the chat
server, its satellite services and the shared crypto/network libraries) is
vendored here as a pinned snapshot, plus a **brand overlay** that turns the
single-purpose Signal code base into a re-usable one: one JSON profile
describes a brand (name, bundle ids, domains, server keys, root CA) and
`tools/brand/apply.py` renders it into every platform.

> Signal is a trademark of Signal Messenger, LLC. This project is not
> affiliated with Signal, and brand builds must run against their **own**
> servers. Read `NOTICE.md` before shipping anything.

## Layout

| Path | Upstream | What it is |
|---|---|---|
| `clients/android` | signalapp/Signal-Android | Android app (Kotlin/Java, Gradle) |
| `clients/ios` | signalapp/Signal-iOS | iOS app (Swift, Xcode) |
| `clients/desktop` | signalapp/Signal-Desktop | Desktop app (TypeScript, Electron) |
| `services/server` | signalapp/Signal-Server | Chat server (Java, Dropwizard): accounts, messages, keys, profiles, groups credentials |
| `services/registration` | signalapp/registration-service | Phone-number verification (SMS/voice) gRPC service |
| `services/storage` | signalapp/storage-service | Encrypted contact/settings storage + Groups v2 |
| `services/cdsi` | signalapp/ContactDiscoveryService-Icelake | Private contact discovery (SGX enclave) |
| `services/svr2` | signalapp/SecureValueRecovery2 | PIN-protected key recovery (SGX/Nitro enclave) |
| `services/calling` | signalapp/Signal-Calling-Service | Group-call SFU |
| `services/tls-proxy` | signalapp/Signal-TLS-Proxy | Censorship-circumvention TLS proxy |
| `libs/libsignal` | signalapp/libsignal | Protocol, zkgroup, attestation and the network stack used by all clients |
| `libs/ringrtc` | signalapp/ringrtc | WebRTC-based calling library |
| `libs/libsignal/rust/meshlink` | — | Offline store-carry-forward transport (BLE / LoRa / serial / gateway links), see `docs/offline-mesh.md` |
| `brands/` | — | Brand profiles (`signal-upstream` parity profile, `asher`) |
| `tools/` | — | `brand/apply.py`, `sync-upstream.sh`, `fetch-submodules.sh`, `server/gen-keys.sh` |
| `deploy/server/<brand>/` | — | Generated server config skeletons |
| `docs/` | — | Architecture, self-hosting guide, per-platform override maps, list of local patches |
| `upstream/manifest.json` | — | Exact upstream commit of every vendored component |

The snapshots are imported without git history (about 500 MB of source). The
manifest records the commit, date and subject of each; `tools/sync-upstream.sh`
re-imports at the pinned commit or moves to upstream's latest.

## How "multi-use" works

Signal's code hard-codes one deployment. Making it re-usable needed changes at
three layers, all of which are in this repository:

1. **libsignal** (`libs/libsignal/rust/net/src/env/brand.rs`). The chat, contact
   discovery and SVR hosts, their pinned root CA, enclave measurements and
   key-transparency keys are compiled into libsignal and only exposed to the
   apps as `Production` / `Staging`. A set of `LIBSIGNAL_BRAND_*` build-time
   variables now retargets `Production` at a brand's infrastructure, so the
   apps' code and the Java/Swift/Node APIs are unchanged. A brand ships its own
   libsignal build.
2. **App build inputs.** Android reads a generated `brand.properties` from
   `app/build.gradle.kts`; Desktop's `package.json` and `config/production.json`
   are rewritten; iOS's project settings, `TSConstants.swift`, `Info.plist`,
   entitlements and pinned certificate are rewritten. All of it is driven by
   `brands/<id>/brand.json` through `tools/brand/apply.py`, which is
   idempotent and can move the tree from one brand to another and back.
3. **Server.** A config skeleton per brand is generated from upstream's sample
   with the brand's identifiers filled in, plus `tools/server/gen-keys.sh` to
   mint the zkgroup, generic and unidentified-delivery key material whose
   public halves go back into the brand profile.

Everything that remains Signal-specific after `apply.py` (support-article
links, in-app artwork, ~200 UI strings containing the word "Signal", the
User-Agent format the server parses) is catalogued per platform in
`docs/override-map/`.

## Quick start

```sh
# 1. pick or create a brand
cat brands/asher/brand.json

# 2. render it into the tree (re-run whenever brand.json changes)
tools/brand/apply.py --brand asher            # or --dry-run first

# 3. build libsignal for the brand, then the apps
source libs/libsignal/brand.env
(cd libs/libsignal/java && ./build_jni.sh android && ./gradlew :android:assembleRelease)   # Android AAR
(cd libs/libsignal/node && pnpm install && pnpm build)                                     # Node package
(cd libs/libsignal/swift && ./build_ffi.sh --release)                                      # iOS xcframework
(cd clients/android && ./gradlew assembleWebsiteProdRelease)
(cd clients/desktop && pnpm install && pnpm generate && pnpm build:release)

# 4. stand up the server side (docs/self-hosting.md), then put the generated
#    public parameters into brands/asher/brand.json and re-run apply.py
```

`brands/asher/brand.json` ships with `REPLACE_ME` placeholders for the values
that only exist once a server is deployed (zkgroup params, trust roots,
enclave ids, App Store id, team id, Stripe key). `apply.py` warns while any
remain.

To switch a checkout back to plain upstream Signal values run
`tools/brand/apply.py --brand signal-upstream`; that is also the state this
repository is committed in.

## Working without the internet

`libs/libsignal/rust/meshlink` carries Signal Protocol ciphertext over links
that are not the internet: phone-to-phone Bluetooth, LoRa radios (RNode/KISS),
serial cables and satellite gateways. Identity is the device's Signal identity
key fingerprint, prekeys travel as signed contact cards (QR code or beacon),
and delivery is store-carry-forward with multi-hop relay, so messages cross
gaps in coverage and time without a server. Encryption is unchanged. Groups,
storage and discovery still need the server. Design, test coverage, per-client
integration seams and limits: `docs/offline-mesh.md`.

## Maintenance

* `tools/sync-upstream.sh [--latest] [Component...]` re-vendors components and
  updates the manifest. Re-apply the local patches listed in `docs/patches.md`
  afterwards (they are small and isolated).
* `tools/fetch-submodules.sh` clones the upstream submodules that snapshots
  cannot carry (iOS `Pods`, enclave dependencies).
* Licensing: AGPL-3.0-only throughout (MIT for `services/tls-proxy`). See
  `LICENSE` and `NOTICE.md`.
