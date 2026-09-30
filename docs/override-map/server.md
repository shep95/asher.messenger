# Signal-Server configuration map (`services/server`)

`tools/brand/apply.py` generates `deploy/server/<brand>/config.yml` from
`service/config/sample.yml` with the brand's package name, bundle id,
attachments domain, WebAuthn RP/origins and zkgroup public params filled in,
and rewrites `GrpcExceptions.DOMAIN`. Everything else below is deployment work.

## Configuration kinds (`service/config/sample.yml`)
A = external infrastructure, B = key material to generate, C = tunable.

| Section | Kind | Note |
|---|---|---|
| `tlsKeyStore` | B | SNI keystore for the omnibus H2/gRPC port; unused with `grpc.h2c: true` behind a TLS proxy |
| `stripe`, `braintree`, `googlePlayBilling`, `appleAppStore`, `appleDeviceCheck` | A | donations / device check; bundle id and package name are brand values |
| `dynamoDbClient`, `dynamoDbTables` | A | 33 tables (`Example_*`); schemas in `DynamoDbExtensionSchema.java`; `issuedReceipts.generator` is a random secret |
| `pagedSingleUseKEMPreKeyStore`, `cdn`, `dynamicConfig`, `asnTable` | A | S3; the first two support `endpointOverride` |
| `cacheCluster`, `pushSchedulerCluster`, `rateLimitersCluster`, `messageCache.cluster` | A | Redis **Cluster** |
| `pubsub` | A | single Redis |
| `directoryV2.client` | A+B | CDSI shared secrets (2 × 32 bytes); no URI, clients talk to CDSI directly |
| `svr2`, `svrb` | A+B | URI, 2 shared secrets each, SVR CA cert |
| `gcpAttachments` | A+B | GCS domain, service-account e-mail, RSA signing key |
| `tus`, `cdn3StorageManager` | A+B | upload server + shared secret |
| `apn`, `fcm` | A | your APNs key / Firebase JSON |
| `unidentifiedDelivery` | **B** | `certificate` (plain) + `privateKey` (secret) from `certificate -k … -i …` |
| `storageService` | A+B | storage-service URI, shared secret, CA |
| `callingZkConfigPreV101`, `callingZkConfig`, `chatZkConfig` | **B** | GenericServerSecretParams (`tools/server/GenGenericParams.java`); chat = backups |
| `groupsZkConfig` | **B** | `zkparams` output; `serverPublic` padded with `=` |
| `registrationService` | A | gRPC host/port, GCP identity token audience, collation salt, CA |
| `keyTransparencyService` | A+B | host, TLS, mTLS client cert/key |
| `turn.cloudflare` | A | only TURN implementation |
| `foundationDbMessages` | A+B | cluster file URLs, epochs, versionstamp cipher key |
| `registrationWebAuthn` | C+B | RP id/origins (brand), 32-byte blinding secret |
| `linkDevice.secret`, `paymentsService`, `hlrLookup`, `callQualitySurvey`, `shortCode` | B/A | random secret; MobileCoin/Fixer/CoinGecko; hlrlookup.com; Pub/Sub; captcha short codes |
| `logging`, `attachments`, `remoteConfig`, `badges`, `subscription`, `oneTimeDonations`, `grpc`, `externalRequestFilter`, `idlePrimaryDeviceReminder`, `openTelemetry` | C | |

Config sections that exist but are absent from the sample:
`awsCredentialsProvider`, `backup`, `webSocket`, `reportMessage`, `spamFilter`,
`clientRelease`, `virtualThread`, `circuitBreakers`, `retries`, `bulkheads`,
`generalRedisRetry`, `changeNumber`, `registrationTotp`, Dropwizard `server:`
(defaults 8080/8081).

## Secrets bundle
`-Dsecrets.bundle.filename=…` is mandatory for every command, including key
generation. `secret://name` references resolve to the bundle; `${ENV}`
substitution is enabled. `tools/server/gen-keys.sh` prints every random
32-byte value the sample bundle lists.

## Key-generation commands
| Item | Command |
|---|---|
| zkgroup params | `java -Dsecrets.bundle.filename=b.yml -jar TextSecureServer.jar zkparams` |
| UD CA | `… certificate --ca` (public key → clients' `ud_trust_roots`) |
| UD server cert | `… certificate -k <caPrivate> -i <keyId>` (ids `0xdeadc357`, `0x7357c357` rejected) |
| GenericServerSecretParams | no upstream command; `tools/server/GenGenericParams.java` |
| validate | `… check config.yml`, `… check-dynamic-config dynamic.yml` |

## Hard-coded Signal specifics in server code
| File | What |
|---|---|
| `grpc/GrpcExceptions.java:20` | `DOMAIN` (rewritten by `apply.py`) |
| `util/ua/UserAgentUtil.java:17` | `^Signal-(Android\|Desktop\|iOS)/` — clients keep this UA |
| `util/HeaderUtils.java`, `controllers/ArchiveController.java`, `auth/IdlePrimaryDevice…Filter.java` | `X-Signal-*` headers (protocol; keep) |
| `subscriptions/BraintreeGraphqlClient.java:133,239`, `StripeManager.java:100`, `metrics/UserAgentTagUtil.java:36` | "Signal" display names / UA |
| `resources/org/signal/donations/PayPal.properties`, `bankmandate/BankMandate.properties`, `badges/Badges*.properties`, `banner.txt` | donation texts, badge names, banner |
| `captcha/` | real captcha providers come from the private spam-filter module; open builds get a no-op |

## Build facts
Temurin 26; `./mvnw -Pexclude-spam-filter clean package`; artifact
`service/target/TextSecureServer-<ver>.jar` (+ `-bin.tar.gz`); needs
`libfdb_c` 7.3.x; jib image via `package jib:dockerBuild -Ddocker.repo=…`;
test server `./mvnw integration-test -Ptest-server -DskipTests=true`.
