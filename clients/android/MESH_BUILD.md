# Building the Android app against the vendored libsignal (meshlink bridge)

The offline mesh transport (`docs/offline-mesh.md`) needs the `MeshNode_*` /
`MeshIdentity_*` / `MeshContactCard_*` bridge functions, which exist only in the
vendored `libs/libsignal`, not in the published `org.signal:libsignal-android`
artifact. `gradle.properties` therefore sets

```
libsignalClientPath=../../libs/libsignal
org.gradle.dependency.verification=lenient
```

`settings.gradle.kts` turns that into `includeBuild("<rootDir>/../../libs/libsignal/java")`
with dependency substitution of `org.signal:libsignal-client` -> `:client` and
`org.signal:libsignal-android` -> `:android` (`libs/libsignal/java/settings.gradle`
includes both projects, plus `:android:benchmarks` and `:android:packaging-test`,
unless `-PskipAndroid` is given, which we must not give). The version pinned in
`gradle/libs.versions.toml` (`libsignal-client = "0.102.2"`) is ignored by the
substitution; the included build is `0.103.1`.

Comment both properties out to build against the published artifact again (the
mesh code then fails to compile: `Native.MeshNode_*` do not exist there).

## Toolchain the included build needs

`libs/libsignal/java/android/build.gradle` runs `../build_jni.sh android` from
its `preBuild` task (`makeJniLibraries`), which cross-compiles the Rust crate for
`arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86` with the NDK clang as linker. It
does **not** use `cargo ndk`; it sets `CC_*`/`CARGO_TARGET_*_LINKER` itself from
`ANDROID_NDK_HOME`, which the Gradle task derives from the SDK's NDK directory.
The NDK version it asks for (`ndkVersion = '28.0.13004108'`) is the one the app
already pins in `gradle/libs.versions.toml` (`ndk = "28.0.13004108"`), so one NDK
install serves both.

One-time setup:

```sh
# Rust toolchain pinned by libs/libsignal/rust-toolchain(.toml), plus the Android targets
rustup show                       # installs the pinned toolchain when run inside libs/libsignal
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
# protoc is needed by libsignal's build scripts
# (Debian/Ubuntu: apt install protobuf-compiler; macOS: brew install protobuf)

# Android SDK with the NDK the app pins
sdkmanager "ndk;28.0.13004108" "platforms;android-37" "build-tools;37.0.0"
export ANDROID_HOME=/path/to/sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
```

Building the JNI libraries by hand (what Gradle does automatically):

```sh
cd libs/libsignal/java
./build_jni.sh android            # all four ABIs -> android/src/main/jniLibs/<abi>/libsignal_jni.so
./build_jni.sh android-arm64      # only arm64-v8a, much faster for a device build
```

Then the normal app build from `clients/android`:

```sh
cd clients/android
./gradlew :app:assemblePlayProdDebug            # or whichever variant you use
./gradlew :app:assemblePlayProdDebug -PandroidArchs=arm64   # restricts the included build's JNI step to arm64-v8a
```

`-PandroidArchs=arm64` (comma-separated, values as accepted by `build_jni.sh`:
`arm64`, `arm`, `x86_64`, `x86`) is read by the included build's
`makeJniLibraries` task. Project properties passed on the command line apply to
every build in the composite.

## Things to watch

* **AGP versions differ.** The included build declares
  `id 'com.android.library' version "9.2.1"` (`libs/libsignal/java/build.gradle`);
  the app uses `android-gradle-plugin = "9.4.0"`. Gradle resolves plugins per
  build, and both use the same Kotlin (`2.2.20`) and JVM target (21), but if
  Gradle reports that `com.android.library` is already on the classpath with a
  different version, align the two by bumping the version string in
  `libs/libsignal/java/build.gradle` to `9.4.0` (a one-line change in the
  vendored tree that the coordinator should make, not this client).
* **Dokka and Spotless plugins** (`org.jetbrains.dokka 2.1.0`,
  `com.diffplug.spotless 7.2.1`) are applied by the included root build and are
  downloaded from the plugin portal; they are not in
  `gradle/verification-metadata.xml`, which is why verification is `lenient`.
* **libsignal 0.103.1 vs 0.102.2.** The app's `lib/libsignal-service` was
  written against the published 0.102.2. If the newer API removed or renamed
  something the service layer uses, the compile error shows up there, not in the
  mesh code.
* **Configuration cache** (`org.gradle.configuration-cache=true`) works with
  included builds; the `Exec` JNI task reads `ANDROID_NDK_HOME` at configuration
  time through `androidComponents.sdkComponents.ndkDirectory`, so the NDK must
  be installed before the first sync.
* `libs/libsignal/java/android/build.gradle` also runs
  `java/find_cargo.sh metadata` at configuration time (rustls-platform-verifier
  version check); `cargo` must be on `PATH` for the Gradle daemon.

## Mesh feature checklist for a device test with no radios

1. Two phones (or a phone and a Desktop build) on one Wi-Fi network or a phone
   hotspot. Settings > Offline mesh: switch on; "Mesh over Wi-Fi" on (default).
2. Each device announces `_asher-mesh._tcp` on port 7788 with `fp=<hex>` and
   dials the other (the smaller fingerprint dials). The Links section shows a
   `lan · out <ip>:7788` / `lan · in <ip>:<port>` link with the neighbour's
   fingerprint once the meshlink Hello has crossed.
3. "Nearby" lists the other device after its card beacon (tap "Broadcast card"
   to speed it up); "Add" saves it as a contact; a conversation opens from the
   Mesh contacts list.
4. Text, an image, a voice note and a file under 4 MiB travel over the mesh; a
   larger attachment fails the message with a toast naming the size limit.
5. A voice/video call to that contact while the device has no chat-server
   connection (airplane mode with Wi-Fi on) sends its offer/answer/ICE over the
   mesh; media flows over the LAN with host candidates.
6. "Run self-test" prints the loopback report; "Export encrypted mesh backup"
   writes an `.asherbackup` through the system file picker; "Restore mesh
   backup" reads one back.

## Where the mesh hooks into Signal-Android (for reviewers)

| Concern | Hook |
|---|---|
| Outgoing texts and attachments | `sms/MessageSender.java` `sendMessageInternal` -> `MeshOutbox.shouldRoute` / `MeshOutbox.send` (`mesh/MeshOutbox.kt`: `MeshNode_PrepareText`, `MeshNode_PrepareAttachment`, `MeshCrypto.encrypt`, `MeshNode_SendCiphertext`) |
| Incoming texts | `mesh/MeshRuntime.kt` `insertIncomingText` -> `MessageTable.insertMessageInbox` |
| Incoming attachments (event tag 10) | `mesh/MeshRuntime.kt` `insertIncomingAttachment` -> `BlobProvider` in-memory uri -> `UriAttachment` -> `MessageTable.insertMessageInbox` (which stores the data through `AttachmentTable.insertAttachmentsForMessage` / `PartAuthority.getAttachmentStream`) |
| Outgoing call signalling | `service/webrtc/SignalCallManager.java` `sendCallMessage` -> `MeshCallSignalling.shouldRoute` / `MeshCallSignalling.send` (`mesh/MeshCallSignalling.kt` `toProtoBytes` mirrors `SignalServiceMessageSender.createCallContent`; `MeshOutbox.sendCallSignal` -> `MeshNode_PrepareCallSignal`) |
| TURN servers offline | `service/webrtc/SignalCallManager.java` `retrieveTurnServers`: on `IOException`, a mesh callee proceeds with an empty ICE server list instead of `handleSetupFailure` |
| Incoming call signalling (event tag 11) | `mesh/MeshRuntime.kt` -> `MeshCallSignalling.onReceived` -> `messages/CallMessageProcessor.process` with a synthesised `Envelope` (`DOUBLE_RATCHET`, source = fingerprint ACI, device 1, timestamps = now) and `EnvelopeMetadata` |
| LAN link | `mesh/link/LanLink.kt` (NsdManager + `ServerSocket(7788)`, u16 framing, `MeshLinkOptions.lan()`), started from `MeshRuntime.start` when `SignalStore.mesh.lanEnabled` |
| Settings | `mesh/ui/MeshSettingsFragment.kt`, `mesh/ui/MeshSettingsViewModel.kt` (Wi-Fi toggle, Nearby + Add via `MeshNode_Nearby`/`MeshNode_Contact`, self-test via `MeshNode_SelfTest(node, 15000)`, backup via `MeshNode_ExportBackup` / `MeshIdentity_FromBackup` / `MeshNode_ImportBackup` and SAF) |
