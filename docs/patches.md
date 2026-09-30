# Local patches on top of upstream

Everything under `clients/`, `services/` and `libs/` is an upstream snapshot
except the changes below. Re-apply them after `tools/sync-upstream.sh`
(each is a single commit; `git log --follow <file>` finds it).

| Component | File(s) | Change | Why |
|---|---|---|---|
| libsignal | `rust/net/src/env/brand.rs` (new), `rust/net/src/env.rs`, `rust/net/build.rs` | `LIBSIGNAL_BRAND_*` compile-time overrides of the `PROD` environment: hosts, root CA, static-IP fallback, reflectors, enclave ids, raft group ids, key-transparency keys. | Chat/CDSI/SVR hosts and pins are compiled into libsignal; bindings only expose Production/Staging. |
| Signal-Android | `app/build.gradle.kts` | Loads `brand.properties` (generated) and routes `applicationId`, `archivesBaseName`, Maps key and every production `buildConfigField` for endpoints/keys through it. Falls back to upstream literals when the file is absent. | Make the app build brand-parametric without editing Gradle per brand. |
| Signal-Server | `GrpcExceptions.java` | `DOMAIN` rewritten by `apply.py` (`hosts.chat_grpc`). | gRPC error domain that clients match on. |
| Signal-iOS | `project.pbxproj`, `TSConstants.swift`, `Signal-Info.plist`, `*.entitlements`, `signal-messenger.cer` | Rewritten in place by `apply.py`; committed state is upstream. | No build-input mechanism exists upstream (project-level settings beat xcconfig). |
| Signal-Desktop | `package.json`, `config/production.json`, `_locales/en/messages.json` | Rewritten in place by `apply.py`; committed state is upstream. | Packaged builds ignore `NODE_CONFIG`; `local-production.json` is overwritten by upstream's build. |

| Signal-Server | `filters/ExternalRequestPathFilter.java` (new), `WhisperServerService.java`, `configuration/GrpcConfiguration.java`, `grpc/net/OmnibusH2Server.java`, `auth/JwtGenerator.java`, `configuration/SharedSecretRegistrationServiceConfiguration.java` (new), `registration/StaticBearerTokenCallCredentials.java` (new), `META-INF/services/...RegistrationServiceClientFactory` | Security fixes S1, S2, S4 and the shared-secret registration client (S3). | `docs/security-audit.md` |
| registration-service | `rpc/RpcAuthenticationConfiguration.java`, `rpc/SharedSecretAuthenticationInterceptor.java` (new), `pom.xml` | Caller authentication (S3) and dependency pins. | `docs/security-audit.md` |
| Signal-Android | `crypto/MasterSecretUtil.java`, `crypto/MasterCipher.java`, `profiles/manage/UsernameRepository.kt`, `push/SignalServiceNetworkAccess.kt`, `s3/S3.kt`, `logsubmit/SubmitDebugLogRepository.java`, `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml` (new), `app/build.gradle.kts` | A1-A5. | `docs/security-audit.md` |
| Signal-iOS | `SignalServiceKit/Environment/BuildFlags+Generated.swift`, three `Info.plist`s, `Signal/DeviceTransfer/DeviceTransferRestore.swift` | I1-I3. | `docs/security-audit.md` |
| Signal-Desktop | `app/ipcSenderGuard.main.ts` (new), `app/main.main.ts`, `app/sql_channel.main.ts`, `app/attachment_channel.main.ts`, `app/protocol_filter.node.ts`, `ts/types/LinkPreview.std.ts` | D1-D5. | `docs/security-audit.md` |
| libsignal | `rust/net/src/env/brand.rs`, `rust/net/src/env.rs`, `rust/net/build.rs`, `rust/attest/src/brand.rs` (new), `rust/attest/src/svr2.rs`, `rust/attest/src/lib.rs`, `rust/keytrans/src/verify.rs` | L1-L3 on top of the brand override. | `docs/security-audit.md` |
| libsignal | `Cargo.toml` (workspace member), `rust/meshlink/` (new crate) | `meshlink`: store-carry-forward offline transport carrying Signal Protocol ciphertext over BLE/LoRa/serial links, with contact-card prekey exchange and device-fingerprint addressing. | `docs/offline-mesh.md` |
| ringrtc, svr2 | `Cargo.lock`, `host/go.mod`, `host/go.sum` | Dependency advisories. | `docs/security-audit.md` |

Files that are generated and ignored by git: `clients/android/brand.properties`,
`libs/libsignal/brand.env`, `libs/libsignal/brand-root-ca.der`,
`deploy/server/*/secrets-bundle.yml`.
