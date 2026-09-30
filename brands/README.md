# Brand profiles

A brand is one directory here containing `brand.json` and optional assets.
`tools/brand/apply.py --brand <id>` renders it into the tree; `--dry-run`
lists what would change. The tool is idempotent and reversible: it can move
the tree between any two brands. `brands/.applied.json` records which brand
is currently applied and the few free-text literals it rewrote.

| Profile | Purpose |
|---|---|
| `signal-upstream` | Signal's own production values. Exists so the overlay can be verified to be a no-op against upstream and to restore the tree. Never ship it (see `NOTICE.md`). |
| `asher` | Asher Messenger. Placeholders (`REPLACE_ME`) for values that come out of the server deployment. |

## brand.json keys

| Key | Used by | Meaning |
|---|---|---|
| `id`, `display_name`, `short_name`, `desktop_name`, `organization`, `support_email`, `url_scheme` | all | Naming. `url_scheme` replaces `sgnl`. |
| `libsignal_override` | libsignal | `true` to generate `libs/libsignal/brand.env` (a brand build of libsignal). |
| `urls.website/legal/donate/install/support_center/app_store/repository` | clients | Public web addresses. |
| `hosts.chat` | android, ios, desktop | REST/websocket host of `services/server` (`https://` is added). |
| `hosts.chat_grpc` | libsignal, server | Host libsignal connects to for chat (HTTP/2 front of the server's `grpc.port`). Same as `chat` unless you front them separately. Also `GrpcExceptions.DOMAIN`. |
| `hosts.storage`, `cdn0`, `cdn2`, `cdn3` | clients | storage-service and the three CDN origins. |
| `hosts.cdsi`, `svr2`, `svrb` | clients, libsignal | Enclave services. |
| `hosts.sfu`, `sfu_test`, `sfu_staging` | clients | Group-call SFUs (`services/calling`). |
| `hosts.updates`, `updates2` | clients | Static assets (badges, emoji, desktop updates). |
| `hosts.content_proxy`, `status` | android, ios, desktop | Link-preview proxy; DNS name probed for outage detection. |
| `hosts.debug_logs_url` | android | Where user-initiated debug logs are uploaded (`https://debuglogs.org` upstream). |
| `hosts.captcha_registration_url`, `captcha_challenge_url` | clients | Full URLs of the captcha pages. |
| `link_domains.me/group/art/link/tube/donations` | android, ios, desktop | Universal-link hosts (usernames, group invites, sticker packs, call links, proxies, donations). |
| `android.application_id`, `archives_base_name`, `maps_key`, `giphy_api_key`, `disable_static_ips` | android | Package id, APK base name, API keys; `disable_static_ips` empties Signal's hard-coded IP fallbacks. |
| `android.censorship_circumvention` | android | `false` (default for brands) disables domain fronting through Google/Fastly reflectors that forward to Signal's servers. Set `true` only if you operate your own reflectors and edit `SignalServiceNetworkAccess.kt`. |
| `ios.bundle_id_prefix`, `team_id`, `merchant_id` | ios, server | `SIGNAL_BUNDLEID_PREFIX` (app is `<prefix>.signal`), Apple team, Apple Pay merchant. |
| `desktop.package_name`, `description`, `desktop_name`, `app_id`, `executable_name`, `startup_wm_class`, `updates_url`, `updates_public_key`, `app_image_updates_public_key` | desktop | electron-builder identity and auto-update feed/keys. |
| `server.webauthn_relying_party_id`, `attachments_domain` | server | WebAuthn RP and GCS signed-URL domain in the generated config. |
| `crypto.root_ca` | ios, desktop, libsignal | Path (relative to the brand dir) of the CA that issues your servers' TLS certificates, PEM or DER. A private CA or a public root (e.g. ISRG Root X1) both work. Becomes `signal-messenger.cer`, Desktop `certificateAuthority`, and `LIBSIGNAL_BRAND_ROOT_CA_DER`. Android's `whisper.store` (BKS) must be built separately, see below. |
| `crypto.ud_trust_roots[]` | clients | Sealed-sender CA public keys (`certificate --ca` output). |
| `crypto.zkgroup_server_public_params` | clients, server | `zkparams` public output (also `groupsZkConfig.serverPublic`). |
| `crypto.generic_server_public_params` | clients | Public half of `callingZkConfig`. |
| `crypto.backup_server_public_params` | clients | Public half of `chatZkConfig`. |
| `crypto.svr2_mrenclave`, `svr2_mrenclave_legacy`, `ios_svr2_enclaves[]`, `cdsi_mrenclave`, `svrb_mrenclave` | clients, libsignal | Enclave measurements of your CDSI/SVR builds. |
| `crypto.svr2_raft_group_id`, `svrb_raft_group_id` | libsignal | Required together with the matching MRENCLAVE (libsignal refuses to build otherwise). |
| `crypto.keytrans_signing_key`, `keytrans_vrf_key`, `keytrans_auditor_keys[]` | libsignal | Set all three together (at least one auditor key). Without an auditor the log signature would never be verified, so libsignal refuses that configuration at build time. `null` keeps Signal's keys, which fail closed against a brand server. |
| `integrations.stripe_publishable_key`, `default_currencies` | clients | Donations. |

## Optional assets

* `assets/android/whisper.store`: BKS trust store containing `crypto.root_ca`,
  password `whisper`. Build it with keytool + BouncyCastle:
  `keytool -importcert -noprompt -alias root -file root-ca.pem -keystore whisper.store -storetype BKS -storepass whisper -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath bcprov-jdk18on.jar`
* `assets/android/mipmap-*/ic_launcher*.png`: launcher icons, copied over
  the upstream ones.

## Desktop build expiry

`clients/desktop` refuses to run once `buildExpiration` passes, and a packaged
build with the checked-in `buildExpiration: 0` is expired on first launch.
`pnpm generate` runs `scripts/get-expire-time.mjs`, which stamps
`config/local-production.json` with creation + 90 days; make sure every
release build runs it and ships auto-updates within that window.

## What apply.py does not do

Icons, artwork, the ~200 UI strings that mention Signal, App Store / Play
listing metadata, support-article links and code signing are brand work
that no template can do for you. `docs/override-map/*.md` lists every
location per platform.
