# Building Asher iOS with the mesh transport

The offline mesh transport (`docs/offline-mesh.md`) lives in the vendored
libsignal at `libs/libsignal` (crate `rust/meshlink`, bridge
`rust/bridge/shared/src/mesh.rs`). The upstream prebuilt `libsignal_ffi.a`
that Signal-iOS normally downloads does not contain the `signal_mesh_*`
symbols, so this app builds LibSignalClient from that tree instead
(`Podfile`: `pod 'LibSignalClient', path: '../../libs/libsignal'`).

The podspec (`libs/libsignal/LibSignalClient.podspec`) recognises a source
checkout by the presence of `swift/build_ffi.sh`: it skips the archive
download, symlinks its temp dir to the checkout and links
`target/$(CARGO_BUILD_TARGET)/release/libsignal_ffi.a` from there. It does
**not** run cargo for you; you build the static library first, once per
target triple, and rebuild it whenever the Rust side changes.

## 1. One-time setup

```sh
# Rust, pinned by libs/libsignal/rust-toolchain (1.98.1 at the time of writing)
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
cd libs/libsignal
rustup toolchain install "$(cat rust-toolchain)"
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios

# protobuf compiler for libsignal's build scripts, CocoaPods for the app
brew install protobuf cocoapods
```

`x86_64-apple-ios` is only needed for the simulator on an Intel Mac;
`aarch64-apple-ios-sim` for the simulator on Apple silicon; `aarch64-apple-ios`
for devices.

## 2. Build libsignal_ffi.a (with the mesh symbols)

From `libs/libsignal`:

```sh
# device
CARGO_BUILD_TARGET=aarch64-apple-ios swift/build_ffi.sh --release
# simulator (Apple silicon)
CARGO_BUILD_TARGET=aarch64-apple-ios-sim swift/build_ffi.sh --release
# simulator (Intel)
CARGO_BUILD_TARGET=x86_64-apple-ios swift/build_ffi.sh --release
```

Each command leaves `target/<triple>/release/libsignal_ffi.a`, which is exactly
the path the podspec's `LIBSIGNAL_FFI_LIB_TO_LINK` expects. Do not set
`CARGO_TARGET_DIR`; the podspec assumes the default `target/` directory.

If the generated header is stale (a bridge function exists in Rust but not in
`swift/Sources/SignalFfi/signal_ffi.h`), regenerate it on the host:

```sh
swift/build_ffi.sh --generate-ffi        # runs cargo run -p libsignal-ffi-native_swift
git diff --stat swift/Sources/SignalFfi/signal_ffi.h
```

Check that every symbol the app uses is declared:

```sh
for f in signal_mesh_node_new signal_mesh_node_prepare_text signal_mesh_node_prepare_attachment \
         signal_mesh_node_prepare_call_signal signal_mesh_node_nearby signal_mesh_node_export_backup \
         signal_mesh_node_import_backup signal_mesh_identity_from_backup signal_mesh_node_self_test; do
  grep -q "$f" swift/Sources/SignalFfi/signal_ffi.h && echo "ok   $f" || echo "MISSING $f"
done
```

`SignalServiceKit/Mesh/MeshFfi.swift` lists, above each wrapper, the exact C
signature it was written against (`// verify against generated signal_ffi.h`).
If the header disagrees, the wrapper is what needs to change.

## 3. Install pods and build

From `clients/ios`:

```sh
make dependencies          # Signal's usual pod-setup + ringrtc fetch (first time)
pod install                # regenerates Podfile.lock for the path pod
open Signal.xcworkspace
```

`pod install` rewrites `Podfile.lock` (LibSignalClient moves from the git
source to the path source) and, through the `post_install` hook
`add_signal_ffi_module_to_app_targets`, appends
`$(SRCROOT)/../../libs/libsignal/swift/Sources/SignalFfi` to
`SWIFT_INCLUDE_PATHS` / `HEADER_SEARCH_PATHS` of every `Pods-*.xcconfig`, so
`import SignalFfi` in SignalServiceKit resolves. If Xcode still reports
`no such module 'SignalFfi'`, add that directory to SignalServiceKit's
`SWIFT_INCLUDE_PATHS` by hand in the project settings.

Then turn the feature on: `FeatureFlags.meshTransport` in
`SignalServiceKit/Environment/BuildFlags.swift` is `false` by default; set it to
`true` for a mesh build. Settings > Mesh appears, off until the user enables it.

## 4. Testing without radios (two phones on one Wi-Fi)

1. Build to two devices (or one device and one simulator on the same Mac; the
   simulator shares the Mac's network and Bonjour works). Enable
   Settings > Mesh > *Mesh transport*; *Mesh over Wi-Fi* is on by default.
   iOS asks for local-network permission the first time.
2. *Run self-test* on either phone: an in-process loopback test of the link
   machinery (`MeshNode_SelfTest`), printed as a PASS/FAIL report.
3. Within a few seconds each phone lists the other under *Nearby* (card
   beacons travel over the LAN link); tap *Add*, or scan the other phone's QR
   card. Links: *Attached links* >= 1 and *Neighbours* >= 1.
4. Send a text, a photo under 4 MB and a voice note from the chat; place a
   voice call. Call signalling travels over the mesh; media is direct
   WebRTC over the LAN (host candidates only, no TURN).
5. *Export encrypted mesh backup* produces an `.asherbackup` file (share
   sheet); *Restore mesh backup* takes it back through the document picker.

Simulator note: Bluetooth links (`BleLink`, `RNodeBleLink`) do not work in the
simulator; only the LAN link does.

## 5. Going back to the upstream prebuilt

Swap the two `pod 'LibSignalClient'` lines in `Podfile` (and restore the
`LIBSIGNAL_FFI_PREBUILD_CHECKSUM` line), run `pod install`. The mesh code
will then fail to link, so also set `FeatureFlags.meshTransport = false`
and remove `SignalServiceKit/Mesh` from the SignalServiceKit target.
