# Signal-iOS override map (`clients/ios`)

**Handled by `apply.py`**: `SIGNAL_BUNDLEID_PREFIX`, `SIGNAL_MERCHANTID`,
`DEVELOPMENT_TEAM`, app `PRODUCT_NAME` (four configurations), every
`TSConstantsProduction` endpoint, `kUDTrustRoots`, `svr2Enclaves`,
`serverPublicParams`, `callLinkPublicParams`, `backupServerPublicParams`,
`legalTermsUrl`/`donateUrl`/`appStoreUrl`, URL scheme, `LOGS_EMAIL`,
associated domains (main app + AppStore entitlements), pinned root
certificate `signal-messenger.cer`.

**Key constraint.** libsignal `Net` is created in
`SignalServiceKit/Environment/AppSetup.swift:219-224` with
`.production`/`.staging` and owns chat websockets (`OWSChatConnection.swift`),
CDSI, SVR2/SVR-B and key transparency. Point the `Podfile` (line 15) at a
brand build of `libs/libsignal` (`path:` line is present but commented).

## Identity
| File | Line | Note |
|---|---|---|
| `project.pbxproj` | 21908-22581 | project-level settings win over xcconfig; `apply.py` rewrites them in place |
| `Signal-Info.plist`, `SignalNSE/Info.plist`, `SignalShareExtension/Info.plist` | | `OWSBundleIDPrefix`, `OWSMerchantID` read at runtime (`Bundle+OWS.swift`) |
| entitlements (6 files) | | app groups `group.$(SIGNAL_BUNDLEID_PREFIX).signal.group`, keychain groups; NSE/share-extension associated domains untouched by `apply.py` (they have none) |
| `TSConstants.swift:195` | | app group name literal `.signal.group` (safe to keep) |

## Endpoints not in `TSConstants`
| File | Value |
|---|---|
| `Network/ContentProxy.swift:12` | `contentproxy.signal.org:443` |
| `Profiles/ProfileBadgeManager.swift:101` | `updates2.signal.org/static/badges/` |
| `Network/OutageDetection.swift:59` | `uptime.signal.org` |
| `Debugging/DebugLogs.swift:433,508` | `debuglogs.org` |
| `TSConstants.swift:171-179` | censorship reflectors (Fastly, Google) |
| `Messages/Stickers/DefaultStickers.swift:23-25` | default sticker packs that only exist on Signal's CDN |
| `Network/HttpHeaders.swift:151` | `User-Agent: Signal-iOS/…` (server parses this; keep) |

## Crypto notes
| File | Note |
|---|---|
| `Network/HttpSecurityPolicy.swift:10-29`, `OWSUrlSession.swift:74-75` | pinning replaces system anchors with `signal-messenger.cer`; so `crypto.root_ca` must be the CA that issues your certs (a public root works) |
| `OWSCensorshipConfiguration.swift:200-207` | Google fronting roots |
| `Payments/MobileCoinAPI+Configuration.swift` | MobileCoin pins/environment |

## Branding still manual
| File | What |
|---|---|
| `Signal/translations/en.lproj/Localizable.strings` (+46 locales) | ~479 lines mention Signal; `InfoPlist.strings` permission texts |
| `Signal/AppIcons/*.icon`, `AppIcon.xcassets`, `Images.xcassets`, launch storyboard | app icon + 11 alternates (`AppIcon.swift:18-30`), artwork |
| `sgnl` literals in code | `UrlOpener.swift:46`, `DeviceProvisioningURL.swift:29`, `Usernames+UsernameLink.swift:17`, `GroupInviteLink.swift:80`, `SignalProxy.swift:82`, `Stripe.swift:490`, `Paypal+WebAuthentication.swift:95`, `CallLink.swift:34`, `UsernameSelectionViewController.swift:52` |
| link hosts in code | `signal.me` (`Usernames+UsernameLink.swift:18`, `SignalDotMePhoneNumberLink.swift:12`), `signal.group` (`GroupInviteLink.swift:50,79,81`), `signal.art` (`StickerPackInfo.swift:90,98`), `signal.link` (`CallLink.swift:16`), `signal.tube` (`SignalProxy.swift:73`), `signaldonations.org` (`Stripe*.swift`, `Paypal+WebAuthentication.swift:38`) |
| `SignalUI/Utils/URL+Support.swift` + ~10 view controllers | `support.signal.org` article links |
| `ComposeSupportEmailOperation.swift:228` | `support@signal.org` |
| `Signal-Info.plist:66-78, 83, 162` | ATS exception for signal.org, Bonjour service types `_sgnl-*._tcp` |
| `Stripe.swift:255-262`, `DonationUtilities.swift:262` | Stripe key, Apple Pay merchant |
| `PasswordManagerManager.swift:56` | AutoFill scope `signal.org` |
