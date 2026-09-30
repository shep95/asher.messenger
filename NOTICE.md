# Notice

This repository ("Asher Messenger platform") is a multi-brand, self-hostable
messaging platform built from the open-source software published by
Signal Messenger, LLC. Every component under `clients/`, `services/` and
`libs/` is an unmodified-or-lightly-patched snapshot of an upstream
`signalapp/*` repository; the exact upstream commit for each is recorded in
`upstream/manifest.json`.

## License

All upstream components are licensed under the GNU Affero General Public
License, version 3 (see `LICENSE`), except `services/tls-proxy`, which is
MIT-licensed. Modifications made in this repository are likewise released
under AGPL-3.0-only. If you run a modified version of any of these services
for users over a network, the AGPL requires you to offer those users the
corresponding source code.

## Trademarks

"Signal" and the Signal logo are trademarks of Signal Messenger, LLC. This
project is not affiliated with, endorsed by or supported by Signal Messenger.
A brand you build from this repository must not use the Signal name, logo,
icons or wordmark, and must not present itself as Signal. The brand overlay
(`brands/`, `tools/brand/apply.py`) exists precisely so you can ship your own
name and assets.

## Do not point brand builds at Signal's servers

Signal Messenger operates its servers for its official clients only. Their
Terms of Service do not permit third-party or modified clients to use
`*.signal.org` infrastructure. A brand built from this repository must run
its own `services/*` deployment and its own libsignal build (see
`docs/self-hosting.md`). The `brands/signal-upstream` profile reproduces
Signal's own values solely so the overlay can be tested for parity; it is
not a licence to connect to Signal's production service.
