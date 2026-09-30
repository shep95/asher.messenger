# Self-hosting the server side

This is the minimum path from an empty account to a `Signal-Server` a brand
build can register against. It condenses what is in `services/server`
(`README.md`, `TESTING.md`, `service/config/sample.yml`) plus what was learned
mapping the code; exact keys are in the generated
`deploy/server/<brand>/config.yml`.

## 0. Try the test server first

`services/server` ships a self-contained mode that stubs registration
(any verification code is accepted) and runs against local DynamoDB, Redis
and FoundationDB:

```sh
cd services/server
./mvnw integration-test -Ptest-server -DskipTests=true
```

Use it to validate a brand build end-to-end before touching real
infrastructure. `SIGNAL_SERVER_CONFIG` overrides the config it uses.

## 1. Build

* JDK: Temurin 26 (`.java-version`). Maven wrapper is included.
* Native dependency: FoundationDB client library 7.3.x (`libfdb_c`).
* `./mvnw -Pexclude-spam-filter clean package -DskipTests` →
  `service/target/TextSecureServer-<ver>.jar` (the spam filter is a private
  upstream submodule; the profile substitutes a no-op captcha).
* Container image: `./mvnw -pl service -am package jib:dockerBuild -Ddocker.repo=<repo>`.

## 2. Generate key material

```sh
tools/server/gen-keys.sh ~/asher-keys      # keep this directory offline
```

Copy the **public** outputs into `brands/<id>/brand.json`
(`zkgroup_server_public_params`, `generic_server_public_params`,
`backup_server_public_params`, `ud_trust_roots`), then re-run
`tools/brand/apply.py`. Copy the **secret** outputs into
`deploy/server/<id>/secrets-bundle.yml` (git-ignored). `groupsZkConfig.serverPublic`
in `config.yml` must be padded with `=` to a multiple of four characters.

## 3. Infrastructure the sample config expects

| Dependency | Notes |
|---|---|
| DynamoDB | 33 tables; schemas in `service/src/test/java/.../storage/DynamoDbExtensionSchema.java`. No endpoint override in production code. |
| Redis Cluster ×4 (cache, pushScheduler, rateLimiters, messageCache) + Redis ×1 (pubsub) | Cluster mode is mandatory for the four. |
| FoundationDB | Message store; cluster file URL + versionstamp cipher key in `foundationDbMessages`. |
| S3 (or compatible with `endpointOverride`) | prekeys (`pagedSingleUseKEMPreKeyStore`), profiles/stickers (`cdn`), `dynamicConfig` YAML, `asnTable`. |
| GCS + service account | attachments (`gcpAttachments`), signed URLs on `attachments_domain`. |
| TUS upload server / cdn3 | `tus.uploadUri`, `cdn3StorageManager`. |
| APNs `.p8`, FCM service-account JSON | push. Bundle id / package name come from the brand profile. |
| Cloudflare TURN | only TURN provider implemented. |
| `services/registration` | gRPC; needs an SMS/voice provider. Required for real sign-ups. |
| `services/storage` | groups + encrypted settings; same zkgroup params, shared secret in `storageService`. |
| `services/cdsi`, `services/svr2` (optional but clients expect them) | SGX/Nitro enclaves; their MRENCLAVE values go into `brand.json` and the libsignal brand build. Shared secrets in `directoryV2`, `svr2`, `svrb`. |
| `services/calling` | SFU for group calls; credentials come from `callingZkConfig`. |
| TLS | Terminate at a proxy that presents certificates issued by `crypto.root_ca` for every host in `brand.json` `hosts`. libsignal connects to `hosts.chat_grpc` with HTTP/2 + TLS 1.3 and expects the server's omnibus `grpc.port` behind it (it proxies `/v1/websocket/` to the Jetty connector). |

Payments, badges, donations and MobileCoin sections can be left as upstream
placeholders; the config must still validate.

## 4. Validate and run

```sh
java -Dsecrets.bundle.filename=deploy/server/<id>/secrets-bundle.yml -jar TextSecureServer-<ver>.jar check deploy/server/<id>/config.yml
java -Dsecrets.bundle.filename=deploy/server/<id>/secrets-bundle.yml -jar TextSecureServer-<ver>.jar check-dynamic-config dynamic-config.yml
java -Dsecrets.bundle.filename=deploy/server/<id>/secrets-bundle.yml -jar TextSecureServer-<ver>.jar server deploy/server/<id>/config.yml
```

## 5. Things the server still assumes about clients

* It parses `User-Agent: Signal-(Android|Desktop|iOS)/<version>`
  (`util/ua/UserAgentUtil.java`). The clients keep that header unchanged, so
  version gating and metrics keep working; if you rename it, change both sides.
* Protocol headers are `X-Signal-*`; leave them.
* PayPal/Stripe display names, badge names, the SEPA mandate text and the
  banner mention Signal in `service/src/main/resources/**` and
  `subscriptions/*.java`; edit if you enable donations.
