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

Files that are generated and ignored by git: `clients/android/brand.properties`,
`libs/libsignal/brand.env`, `libs/libsignal/brand-root-ca.der`,
`deploy/server/*/secrets-bundle.yml`.
