# Signal-Android override map (`clients/android`)

**Handled by `apply.py`** (via `brand.properties` + `strings.xml`): application id,
archives base name, Maps key, all production endpoint `buildConfigField`s, SVR2
enclaves, trust roots, zkgroup/generic/backup params, currencies, Giphy key,
captcha URLs, badge root, Stripe key, static-IP fallback (disable), `app_name`,
install/donate/support/legal/username-link URLs, support e-mail.

**Key constraint.** The authenticated and unauthenticated chat websockets, CDSI
lookups, key transparency and SVR-B all go through libsignal
(`ApplicationDependencyProvider.java:350,416,443`, `CdsiV2Service.java:47`),
selected only by `LIBSIGNAL_NET_ENV` = `PRODUCTION`/`STAGING`. A brand needs a
brand build of `libs/libsignal` (see `libsignal_override` in `brand.json`) and
should build the app against it with `libsignalClientPath` in `gradle.properties`
(`settings.gradle.kts:83-86`).

## Build identity (`app/build.gradle.kts`)
| Line | Key | Value / note |
|---|---|---|
| 150 | `namespace` | `org.thoughtcrime.securesms` (component names; leave). `applicationId` now comes from `brand.properties`. |
| 33-36, 246-247 | version | `canonicalVersionCode` 1758 / `8.29.2` |
| 162, 439-506 | flavors | `distribution` (play/website/github/nightly) × `environment` (prod/staging). Brand builds use `prod`; the `staging` flavor keeps Signal staging. |
| 67-91, 543-547 | `selectableVariants` | allow-list; add variants here if you add flavors |
| 41-43, 166-173 | signing | debug only from `keystore.debug.properties`; release signing is external |
| `util/Environment.kt:9` | `GOOGLE_PLAY_BILLING_APPLICATION_ID` | hard-coded `org.thoughtcrime.securesms`; change if you use Play Billing |
| `AndroidManifest.xml:23,117` | package literals | `ACCESS_SECRETS` permission name, satellite meta-data |

## Endpoints not in `brand.properties`
| File | Value |
|---|---|
| `push/SignalServiceNetworkAccess.kt:79-86,201-257` | censorship-circumvention reflectors and fronting hosts (Signal's); disable or replace |
| `logsubmit/SubmitDebugLogRepository.java:72` | `https://debuglogs.org` |
| `service/webrtc/CallingAssets.kt:35` | `updates2.signal.org/static/android/calling/…` |
| `app/static-ips.properties`, `build-logic/.../translations.gradle.kts:520-540` | Signal IPs; `disable_static_ips` empties them at build time |

## Crypto not in `brand.properties`
| File | Value |
|---|---|
| `app/src/main/res/raw/whisper.store` (`SignalServiceTrustStore.java:20-25`, password `whisper`) | BKS trust store pinning Signal's root; supply `brands/<id>/assets/android/whisper.store` |
| `res/raw/censorship_fronting.store`, `censorship_digicert.store` | fronting roots |
| `res/raw/signal_mobilecoin_authority.der` | MobileCoin |
| CDSI enclave id | inside libsignal |

## Branding still manual
| File | What |
|---|---|
| `res/values/strings.xml` | ~646 mentions of "Signal" in ~588 strings (68 locales); `support.signal.org` article links |
| `res/mipmap-*/ic_launcher*`, `drawable/ic_launcher_*`, `AndroidManifest.xml:996-1259` | launcher + 11 alternate icons (activity-aliases named `ic_launcher_alt_signal_*`) |
| `AndroidManifest.xml:161-358` | deep-link hosts (`sgnl://`, signal.art/group/me/link/tube, signaldonations.org); also `UsernameRepository.kt:91-93`, `CallLinks.kt:25-28`, `SignalProxyUtil.kt:28`, `StickerUrl.java:70` |
| 17 Kotlin/Java files | hard-coded `support.signal.org` URLs (e.g. `RemoteBackupsSettingsFragment.kt:277`, `UsernameEditFragment.java:41`) |
| `RegistrationConstants.java:13`, `WelcomeFragment.kt:40` | terms URL |
| `SubmitDebugLogActivity.java:454` | `support@signal.org` |
| `res/values/firebase_messaging.xml` | FCM project ids/keys (hand-written, no google-services plugin) |
| `lib/donations/.../GooglePayApi.kt:129`, `PayPalRepository.kt:25-27` | merchant name, PayPal return URLs |
| `TotpRepository.kt:45`, `CreateReleaseChannelJob.kt:81`, `SaveAttachmentUtil.kt:231` | literal "Signal" (TOTP issuer, release channel, media folder) |
| `PlayStoreUtil.java:18`, `DeprecatedNotificationJob.kt:54`, website flavor `APK_UPDATE_MANIFEST_URL` (build.gradle.kts:444) | APK update/fallback URLs |
