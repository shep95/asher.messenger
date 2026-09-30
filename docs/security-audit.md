# Security audit (2026-09-30)

Scope: every vendored component (`clients/*`, `services/*`, `libs/*`) at the
commits in `upstream/manifest.json`, plus this repository's own additions.
Method: automated dependency, secret and configuration scanning; manual review
of the authentication, authorization, transport-security, IPC, file-handling,
attestation and remote-control surfaces of each component. Findings are
evidence-based (file and line) and the verification status of every fix is
stated. Items marked *deferred* could not be verified in this environment and
are listed for a build machine with the platform SDKs.

A note on intent. The request was to find and remove "back-door" access by
governments, corporations or agencies. Nothing in this code base resembles a
covert access mechanism, and this audit does not claim otherwise. What it
*does* enumerate, in section 4, is every mechanism by which a party other
than the user and the operator can influence security behaviour or receive
data: hard-coded trust anchors (Intel, AWS, Signal), remote-configuration
flags served by the server, third-party SDKs and services, and telemetry or
log-upload paths. Each is a documented dependency, not a hidden one, and
each is either removed for brand builds, made operator-controlled, or listed
with the reason it must remain.

## 1. Dependency vulnerabilities

Tools: `cargo audit` (RustSec), `pnpm audit` (npm), Trivy 0.74 offline
(Go, CocoaPods, Bundler, pnpm), and an offline copy of the GitHub Advisory
Database matched against Maven runtime dependency trees resolved with Maven
(`tools/security/osvmatch.py`). Gradle (Android) coordinates were taken from
the version catalog because no Android SDK is available here.

| Component | Ecosystem | Before | Action | After | Verified by |
|---|---|---|---|---|---|
| libs/libsignal | Rust | 0 | — | 0 | cargo audit |
| services/calling (SFU) | Rust | 0 | — | 0 | cargo audit, Trivy |
| libs/ringrtc | Rust | 2 (h2 RUSTSEC-2026-0258, rustls RUSTSEC-2026-0285) | lockfile bump h2 0.4.16, rustls 0.23.45 | 0 | cargo check + cargo audit |
| services/svr2 host/rustclient | Rust | 1 (bytes RUSTSEC-2026-0007) | lockfile bump bytes 1.11.1 | 0 (1 low unsound note: rand) | cargo check + cargo audit |
| services/svr2 host (Go) | Go | 8 (x/crypto ssh auth bypass CVE-2026-56854, grpc-go ×4, otel) | go.mod bumps x/crypto 0.56.0, grpc 1.83.2, otel/sdk 1.45.0 | see status below | go build / vet / test |
| services/server | Maven | 2 (Jetty 12.1.9: GHSA-2fvj-hgj9-j2gr digest-auth bypass [digest auth not used], GHSA-f4v5-65jj-pcr2 trailer leakage) | `jetty.version` 12.1.10 | 0 | dependency resolution; compile see status below |
| services/registration | Maven | 38 (Netty 4.2.15 incl. CVE-2026-75595 SNI bypass, Jackson 2.21.2 ×12, Micrometer ×2, httpcore5 ×2, httpclient5, plexus-utils traversal, logback ×2, otel) | property overrides over micronaut-parent: netty 4.2.17.Final, jackson 2.21.6, micrometer 1.15.12, httpclient5 5.6.3, httpcore5 5.4.3, plexus-utils 3.6.1, logback 1.5.34 | 1 deferred (opentelemetry-api 1.54.1 → 1.62.0 needs a matching instrumentation bump) | JDK 26 compile + re-resolution |
| services/storage | Maven | not scanned | — | — | depends on `org.signal:libsignal-server` from Signal's private repository (403); build it from `libs/libsignal/java` (`gradlew -PskipAndroid :server:publishToMavenLocal`) |
| clients/android | Gradle | 14 declared (jackson-databind 2.12.0 ×13, shipped via `lib/libsignal-service`; handlebars 4.0.7 in the `spinner` debug tool) | version catalog: jackson 2.18.10, handlebars 4.5.2 | 0 declared | **deferred**: no Android SDK here; build before release |
| clients/desktop | npm (prod) | 11 (react-router 6.10 ×6 in `sticker-creator`, uuid ×2, brace-expansion ×3 via a formatting helper in the mock server) | lockfile-only bumps where pnpm allowed | see status below | pnpm audit --prod |
| clients/desktop | npm (dev) | 94 | none: build tooling (storybook, webpack-dev-middleware, danger, react-devtools). The shipped Electron is 44.2.0; the flagged 39.8.10 is a transitive dev copy. | — | — |
| clients/ios | CocoaPods | 0 | — | 0 | Trivy |
| clients/ios | Bundler (fastlane tooling) | 6 (concurrent-ruby, activesupport) | none: release tooling, not shipped | — | listed |

## 2. Secrets and credentials in the tree

gitleaks 8.30 over the whole tree, non-test paths reviewed by hand:

* No private keys, cloud credentials or live tokens. The only secret-shaped
  strings are public client API keys (Giphy, Google Maps, Firebase project
  ids), published sticker-pack keys, test vectors, and a randomly generated
  shared secret in `services/cdsi/src/main/resources/application-dev.yml`
  that is only loaded by the `dev` profile.
* `services/calling/frontend/src/config.rs:125` hard-codes an
  `authentication_key` and zk params, but only inside
  `#[cfg(test)] default_test_config()`; the production `Config` is a clap
  parser with a required `--authentication-key`.
* Public client keys (Maps, Giphy, Stripe publishable) are brand values and
  are replaced by `tools/brand/apply.py`; the upstream ones only remain in
  the `signal-upstream` parity profile.


## 3. Code review findings and fixes

Severity reflects impact on a self-hosted brand deployment. "Fixed" means the
change is in this repository; the verification column says how far it was
tested here. Items marked *documented* are real but were left for the
operator, with the reason.

### 3.1 Signal-Server (`services/server`)

| # | Finding | Severity | Status |
|---|---|---|---|
| S1 | `externalRequestFilter.paths` ("internal only" REST paths) was enforced by a servlet filter, which requests tunnelled over the authenticated websocket never traverse. Any client could reach those paths from any network. | Medium | **Fixed**: `filters/ExternalRequestPathFilter.java` (Jersey filter registered on the websocket environment, same address ranges, fail-closed when the remote address is unknown) + unit test. |
| S2 | The omnibus port honoured a PROXY-protocol header from any peer, so a client that reaches the port directly can claim any source address and bypass per-IP rate limits and internal-range checks. | Medium | **Fixed**: `grpc.acceptProxyProtocol` (default `false`); the handler is only installed when enabled. Enable it only behind a trusted load balancer. |
| S3 | The registration service's gRPC API has no caller authentication in code; upstream relies on a cloud identity-aware proxy. Anyone who can reach it can mark any phone number as verified. | High (deployment-dependent) | **Fixed**: registration service gains `rpc.authentication.shared-secret` + `SharedSecretAuthenticationInterceptor` (constant-time bearer check, highest precedence); the chat server gains a `type: shared-secret` registration client (`SharedSecretRegistrationServiceConfiguration`, `StaticBearerTokenCallCredentials`). Both sides have unit tests. Still put the service on a private network. |
| S4 | TUS upload JWT had no expiry (GCS and S3 credentials expire in 25 h / 30 min). | Low | **Fixed**: `JwtGenerator` adds `exp` = issued + 24 h. Enforce `exp` on the TUS server as well. |
| S5 | Generated `deploy/server/<brand>/secrets-bundle.yml` copied upstream's public sample secrets; deploying it unedited would let anyone mint CDSI/SVR/TUS credentials. | Medium | **Fixed**: `apply.py` now writes `CHANGE_ME` for every value; the server's `check` command rejects the file until filled. |
| S6 | Websocket traffic reaches Jetty over loopback; without `useForwardedHeaders` every websocket client is `127.0.0.1` to per-IP limiters and internal-range checks, and with it Jetty trusts `X-Forwarded-For` from anyone who can reach port 8080. | Medium | *Documented* in `docs/self-hosting.md`: bind 8080 to loopback, enable forwarded headers, firewall the admin port (8081, unauthenticated runtime toggles and metrics). |
| S7 | Story sends need no access key and fan out to 5000 recipients; with the open-source no-op spam filter this is bandwidth and push abuse. | Medium | *Documented*: keep an edge rate limiter; tune `limits.stories` in dynamic config. |
| S8 | Registration captcha is a no-op without the private spam-filter jar (`captcha: "noop.noop.registration.x"` passes). | Medium | *Documented*: ship a captcha provider through the `SpamFilter` SPI or rate-limit `/v1/verification/*` at the edge. |
| S9 | `dynamicConfig` (unsigned YAML in S3) controls rate limits, client blocking, message-delivery kill switch, captcha, HLR lookups; `remoteConfig` (DynamoDB) controls client feature flags. | Info | *Documented*: restrict IAM on both, enable versioning and access logs. |

Verified as sound: basic-auth HKDF tokens with constant-time compare, websocket principal fixed at upgrade, anonymous-access checks (no existence oracle, PNI refused), group-send endorsement verification, IDOR checks on device/key/account controllers, registration-lock flow, link-device token TTL/single use, message and frame size limits, no credential logging, secrets only via the bundle, test-only server excluded from production.

### 3.2 Registration service (`services/registration`)

| # | Finding | Severity | Status |
|---|---|---|---|
| R1 | No caller authentication (see S3). | High | **Fixed** (see S3). Compiled with JDK 26; 4298 unit tests pass, 4 need Docker (testcontainers). |
| R2 | Dependencies (see section 1). | High | **Fixed** via property overrides; only `opentelemetry-api` left (needs a matching instrumentation bump). |

### 3.3 Signal-Android (`clients/android`)

| # | Finding | Severity | Status |
|---|---|---|---|
| A1 | Censorship circumvention (domain fronting through Google and Fastly reflectors that forward to Signal's servers) is on by default for nine country codes. A brand build would send those users' credentials to a third party and could never connect. | High (brand) | **Fixed**: new `CENSORSHIP_CIRCUMVENTION_AVAILABLE` BuildConfig flag gates the fronting configuration and `isCensored()`; `apply.py` sets it from `android.censorship_circumvention` (off for brands, on for `signal-upstream`). |
| A2 | Hard-coded `updates2.signal.org` (release notes, megaphones, emoji, fonts) and `debuglogs.org` survived the brand overlay. | Medium (brand) | **Fixed**: `STATIC_ASSETS_HOST` and `DEBUG_LOGS_URL` BuildConfig values from the profile. |
| A3 | `Arrays.equals` MAC comparison in legacy `MasterSecretUtil` / `MasterCipher`. | Low | **Fixed**: `MessageDigest.isEqual`. |
| A4 | Username-link regex was unanchored and the dot unescaped, so a link on another host could be parsed as a username link. | Low | **Fixed**: anchored, escaped, `sgnl://` accepted explicitly. |
| A5 | `allowBackup="true"` with a key/value agent but no `dataExtractionRules`: device-to-device transfer copied the whole data directory. | Medium-low | **Fixed**: `res/xml/data_extraction_rules.xml` excludes databases, preferences, files and external storage for cloud backup and device transfer. |
| A6 | Third-party app can pre-select the share recipient via `EXTRA_SHORTCUT_ID`; `AppSettingsActivity` honours external `START_ROUTE` below API 34; payment WebViews load server-supplied URLs with JavaScript; KeyStore key not auth-bound; SQLCipher v3 compatibility; mutable PendingIntents by default; broad FileProvider paths; `-dontobfuscate`. | Low each | *Documented* (product decisions or need device testing). |
| A7 | Website-flavour APK self-update fetches an unsigned manifest over system-trusted TLS; integrity rests on Android's same-signer check. | Medium-low | *Documented*: sign the manifest or pin the host before enabling that flavour. |

All Android changes are minimal and compile-safe by inspection, but no Android SDK is available here: run `./gradlew assembleWebsiteProdRelease` (or your flavour) before release.

### 3.4 Signal-iOS (`clients/ios`)

| # | Finding | Severity | Status |
|---|---|---|---|
| I1 | Checked-in `FeatureBuild.current` was `.internal` for release, shipping the Internal Settings screen (database and key export, plaintext-proxy links, verbose logging) in any archive built from the tree. | High | **Fixed**: `.production` for non-DEBUG builds. |
| I2 | Device transfer restore used manifest `identifier`/`relativePath` from the peer verbatim (path traversal into the app container). | Medium | **Fixed**: `DeviceTransferRestore` rejects absolute paths, `..` segments and NUL, and checks the resolved source and destination stay inside their directories. |
| I3 | ATS exception allowing plaintext HTTP to `*.signal.org` in the app, NSE and share extension; no `http://` URL exists in the code. | Low | **Fixed**: exception removed from all three Info.plists. |
| I4 | New device accepts the first Multipeer inviter without verifying the old device's certificate (only the old device verifies). | Medium | *Documented*: needs a protocol change (both hashes in the QR or a PAKE). |
| I5 | Debug-log upload target comes from the `debuglogs.org` response with no host allow-list; pasteboard writes are not local-only; link previews, CallKit recents and P2P calls on by default; keychain/file protection "after first unlock". | Low-Medium | *Documented*. |

Swift changes are syntax-checked by inspection only; build with Xcode before release.

### 3.5 Signal-Desktop (`clients/desktop`)

| # | Finding | Severity | Status |
|---|---|---|---|
| D1 | The `file://` handler allowed the whole `userData` directory, i.e. `config.json` (which can hold the plaintext SQLCipher key), the database and logs, to any renderer that can issue a fetch. | Medium (conditional) | **Fixed**: `protocol_filter.node.ts` denies `config.json`, `ephemeral.json`, `sql/`, `logs/`, `crashes/` before the allow-list. |
| D2 | No sender validation on `ipcMain` handlers: the seven sandboxed helper windows could drop the database, erase the key, wipe attachment directories, resolve arbitrary hostnames. | Medium | **Fixed**: `ipcSenderGuard.main.ts`; the main window registers its WebContents and the destructive channels (`sql-channel:remove-db`, `erase-sql-key`, pause/resume writes, `erase-*`, `net.resolveHost`, `show-item-in-folder`) reject other senders. |
| D3 | `show-message-box` passed an unvalidated `type` to Electron. | Low | **Fixed**: enum-checked. |
| D4 | Link previews could target `localhost`, `.local` names and bare IP literals (LAN probing from a typed link). | Low | **Fixed**: `isLocalOrLiteralAddressHost` in `shouldPreviewHref`, with unit tests. |
| D5 | `--enable-dev-tools` opened DevTools on the Node-enabled main-window realm in packaged production builds. | Low | **Fixed**: flag removed for packaged production builds; development and pre-release builds unchanged. |
| D6 | Main window runs with `sandbox:false` and the app in the preload realm; plaintext SQLCipher key fallback on Linux without a keyring; update metadata unsigned (server can freeze or replay signed releases); `connect-src https:` CSP; screen-share auto-grant; pnpm store integrity disabled in CI; no ASAR integrity on Linux. | Medium/Low | *Documented*: architectural, upstream design decisions. |
| D7 | A packaged brand build with `buildExpiration: 0` is expired on first launch. | Operational | *Documented* in `brands/README.md`: run `scripts/get-expire-time.mjs` (`pnpm generate`) as part of every build. |

### 3.6 libsignal, ringrtc and enclave services

| # | Finding | Severity | Status |
|---|---|---|---|
| L1 | **In this repository's own brand override**: an empty auditor list made the key-transparency verifier skip tree-head signature verification entirely, because the log signature is only checked alongside each auditor key. | High | **Fixed**: brand builds require `LIBSIGNAL_BRAND_KEYTRANS_AUDITOR_KEY_1..3` with the KT keys (compile-time assertion); the verifier now errors on an auditing mode with zero keys; unit test. |
| L2 | **Brand override**: a custom SVR2 MRENCLAVE hit `expect("SVR2 enclave has a known group id")` in the bridge because the group-id table lives in the `attest` crate. | High (availability) | **Fixed**: `attest/src/brand.rs` mirrors the two variables and `lookup_groupid` / `new_handshake_with_raft_config_lookup` consult it; MRENCLAVE now requires the group id at compile time. Signal's "previous" SVR2/SVR-B endpoints are dropped when a brand host is set. |
| L3 | **Brand override**: a brand host without `LIBSIGNAL_BRAND_ROOT_CA_DER` silently trusted the platform CA store. | Medium | **Fixed**: `build.rs` fails unless a CA is given or `LIBSIGNAL_BRAND_ALLOW_PLATFORM_ROOTS=1` is set explicitly; `apply.py` refuses a profile without `crypto.root_ca`. |
| L4 | `tools/fetch-submodules.sh` cloned enclave dependencies (BoringSSL, libsodium, noise-c, ...) at upstream HEAD. | Medium | **Fixed**: `upstream/submodules.lock.json` records the exact gitlink commits from the upstream superprojects; the script fetches those commits and refuses unpinned ones; `sync-upstream.sh` refreshes the lock. |
| L5 | SVR2 host auth tokens valid ±120 days by default; websocket size limits at tungstenite defaults; backup stream parsed before HMAC (streaming design); non-constant-time compare in incremental MAC; unpinned build-time downloads in source-build scripts. | Low | *Documented*. |

Verified as sound: Intel DCAP policy (debug enclaves rejected, only `UpToDate`/`SWHardeningNeeded` with two fixed advisories accepted, TCB minimum enforced), Noise handshakes bound to attested keys, `OsRng` everywhere, constant-time MAC compares in the protocol, Argon2 PIN parameters, sealed-sender certificate validation, ringrtc 1:1 SRTP keys from identity-bound DH, no telemetry egress in ringrtc, CDSI/SVR2/calling service auth with constant-time compares.

## 4. Who else can influence or observe a deployment

Nothing here is hidden; each item is a documented dependency. "Brand" says what the brand overlay does about it.

| Party | Mechanism | Data or control | Brand |
|---|---|---|---|
| Signal Messenger | Compiled hosts, pinned CAs, zkgroup/UD/KT keys, enclave measurements in every client and in libsignal | Clients only work against Signal's servers | Replaced by `apply.py` + libsignal brand build; removed for brand hosts: static Signal IPs, reflectors, previous enclaves |
| Signal Messenger | `updates2.signal.org` release notes, megaphones, emoji, badges, fonts (MD5-only integrity, pinned TLS) | In-app banners and assets | Host is a brand value (`hosts.updates2`) |
| Signal Messenger | `debuglogs.org` | User-initiated debug logs (scrubbed; contain ACI/device id) | Brand value (`hosts.debug_logs_url`) |
| Server operator (you) | Remote config (`/v2/config`), `dynamicConfig`, HTTP 499 / `clientExpiration` kill switch, `desktop.libsignalNet.*` and `android/ios.libsignal.*` forwarded into libsignal, `internalUser` flags | Feature flags, transport routing, forced upgrades, internal tooling | Inherent to the architecture; every flag that touches security is listed per platform in `docs/override-map/` |
| Google / Fastly | Domain-fronting reflectors (TLS terminates at the front) | Request paths, headers, timing for users in nine country codes or when enabled manually | Android gated off (A1); libsignal reflectors removed for brand hosts; iOS `OWSCensorshipConfiguration` must be disabled or replaced (documented) |
| Google (Firebase) / Apple (APNs) | Push tokens and content-free wake-ups (`challenge`, `rateLimitChallenge`, `verificationCodeRequested`) | Push timing and frequency; never content | Unavoidable for push; analytics collection is disabled in the Android Firebase config |
| Cloudflare | `1.1.1.1` DNS fallback (Android plain UDP, libsignal DoH) after system DNS fails | Client IP and hostnames | Not configurable without a code change; documented |
| Intel | SGX DCAP root key and TCB policy compiled into libsignal | Whether an enclave is accepted | Genuine third-party root for SGX; a brand enclave must be added to `def_enclaves!` to accept any SW-hardening advisory |
| AWS | Nitro root (SVR2 replicas attest peers) | Server side only | — |
| Giphy, Google Maps, Stripe, PayPal/Braintree, MobileCoin, hlrlookup.com, Fixer/CoinGecko, Cloudflare TURN | Optional product features | Search terms via content proxy; coordinates; payment details; phone numbers (HLR, off by default) | Keys and hosts are brand values; the server starts currency polling unconditionally (leave keys blank) |
| Signal build infrastructure | `build-artifacts.signal.org` (WebRTC prebuilts, `libsignal-server` Maven artifact) | Build-time only | `libsignal-server` must be built from `libs/libsignal/java`; WebRTC prebuilts are sha256-pinned |

## 5. Verification status

| Change set | Verified here by |
|---|---|
| Rust lockfile bumps (ringrtc, svr2 rustclient) | `cargo check`, `cargo audit` clean |
| SVR2 Go module bumps | `go build`/`go vet`/`go test` for the packages that do not need the Open Enclave host library (rate, util, web, web/handlers pass); the enclave-linked packages need the SGX SDK |
| registration-service pins + shared-secret interceptor | JDK 26 compile; 4298 unit tests pass (4 need Docker) |
| Signal-Server Jetty bump + S1/S2/S4 + shared-secret client | dependency resolution clean; compilation requires `org.signal:libsignal-server`, which Signal publishes only to its private repository (see `docs/self-hosting.md`); see the commit message for whether the local build succeeded |
| libsignal brand fixes (L1-L3) | `cargo test` for `libsignal-net`, `attest`, `libsignal-keytrans` in the default and brand configurations; the two misconfigurations fail at build time as intended |
| Desktop D1-D5 | `tsc --noEmit` and the `LinkPreview` unit tests (see commit message) |
| Android A1-A5, iOS I1-I3 | Inspection only; no Android SDK or Xcode in this environment |

## 6. Re-running the scans

`tools/security/scan.sh` runs every scanner above; `tools/security/osvmatch.py`
matches Maven coordinates against a local clone of the GitHub Advisory
Database. Re-run both after `tools/sync-upstream.sh`.
