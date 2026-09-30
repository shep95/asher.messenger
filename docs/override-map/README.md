# Override maps

One file per platform listing every place the upstream code hard-codes
Signal's identity, endpoints, keys or branding, with what `tools/brand/apply.py`
already handles and what remains manual brand work. Line numbers refer to the
upstream commits in `upstream/manifest.json`.

* [android.md](android.md) — Signal-Android
* [ios.md](ios.md) — Signal-iOS
* [desktop.md](desktop.md) — Signal-Desktop
* [server.md](server.md) — Signal-Server (configuration and key generation)
