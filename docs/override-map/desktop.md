# Signal-Desktop override map (`clients/desktop`)

**Handled by `apply.py`**: `package.json` name/productName/description/
desktopName/repository/author/appId/publish URLs/executableName/
StartupWMClass/protocols; `config/production.json` endpoints, trust roots,
zk params, Stripe key, updates URL + keys, content proxy, resources URL,
`certificateAuthority`; `_locales/en` tray tooltip and About menu names.

**Key constraint.** `ts/textsecure/preconnect.preload.ts:26-53` picks libsignal
`Net.Environment.Production` unless `serverUrl` matches `/staging/i` or is
localhost. Chat websocket, CDSI and SVR2 go through it. Build against a brand
build of `libs/libsignal/node` (`@signalapp/libsignal-client`, package.json:126).

## Config plumbing
| File | Line | Note |
|---|---|---|
| `app/config.main.ts` | 21-62 | packaged builds force `NODE_ENV=production` and blank `NODE_CONFIG`; only the `config/*.json` layers apply |
| `scripts/get-expire-time.mjs` | 16-31 | overwrites `config/local-production.json` during `generate` (so brand values live in `production.json`) |
| `package.json` | 573-575 | which config files are packaged |
| `ts/types/RendererConfig.std.ts` | 27-69 | zod schema of renderer config keys |
| `scripts/prepare_{beta,alpha,staging}_build.mjs` | | rewrite identity for channels and `checkValue()` the Signal names; adjust if you use channels |

## Endpoints not in config
| File | Value |
|---|---|
| `app/updateDefaultSession.main.ts:13` | Hunspell dictionaries on updates.signal.org |
| `build/dns-fallback.json`, `scripts/generate-dns-fallback.mjs` | Signal hosts + IPs; regenerate or empty |
| `build/optional-resources.json` | emoji assets on updates2.signal.org |
| `ts/services/networkObserver.preload.ts:58` | `uptime.signal.org` probe |
| `ts/util/createHTTPSAgent.node.ts:38-58` | host allow-list for logging only |
| `ts/textsecure/WebAPI.preload.ts:4740,4791` | Stripe API base |

## Identity/packaging still manual
| File | What |
|---|---|
| `app/startup_config.main.ts:18-22` | AUMID prefix `org.whispersystems.` |
| `app/main.main.ts:2747-2762` | `setAsDefaultProtocolClient('sgnl')` |
| `package.json:439-457` | `ElectronTeamID`, Windows signing subject/sha1 |
| `build/entitlements.mas*.plist` | team-qualified app id |
| `build/installer.nsh`, `build/SignalStrings.nsh`, `scripts/gen-nsis-script.mjs` | installer strings |
| `build/policy-templates/org.signalapp.*.policy` | polkit vendor/action ids (`promptOSAuthMain.main.ts`) |
| `build/icons/**`, `build/dmg/*`, `images/signal-logo*`, `images/tray-icons/*` | icons and artwork (`scripts/generate-tray-icons.mjs`) |

## Branding still manual
| File | What |
|---|---|
| `_locales/*/messages.json` | ~194 English strings contain "Signal" (68 locales) |
| `background.html:29`, `ts/services/notifications.preload.ts:136`, `attachments.preload.ts:213`, `installer.preload.ts:281`, `ConversationController.preload.ts:694`, `uploadDebugLog.node.ts:97`, `app/user_config.main.ts:17-24` | window title, notification fallback title, quarantine app name, default device name, release-notes profile, log file name, userData dir prefix |
| `ts/util/getUserAgent.node.ts:23` | `Signal-Desktop/<ver>` (server parses; keep) |
| `ts/util/signalRoutes.std.ts:30-43,232-453`, `callLinkRootKeyToUrl.std.ts:9` | `sgnl:`/`signalcaptcha:` schemes and signal.me/group/link/art/donations hosts |
| `ts/types/support.std.ts`, `createSupportUrl.std.ts`, `About.dom.tsx`, `app/main.main.ts:1289-1314`, ~20 components | support/help/legal links |
| `sticker-creator/src/**` | signal.art, support links |
