//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Thin Swift wrappers over the meshlink bridge functions exported by
//! libsignal_ffi (`rust/bridge/shared/src/mesh.rs`). See docs/offline-mesh.md.
//
// The LibSignalClient pod keeps its FFI helpers (`checkError`,
// `invokeFnReturningData`, `NativeHandleOwner.init(owned:)`, ...) `internal`,
// so this file carries its own minimal copies and talks to the C functions
// directly. Every C symbol used here is listed in the report so it can be
// cross-checked against the generated `signal_ffi.h`.

import Foundation
public import LibSignalClient
// verify against generated signal_ffi.h: the SignalFfi clang module must be
// reachable from SignalServiceKit. LibSignalClient's public API already
// references SignalFfi types (e.g. `NativeHandleOwner<SignalMutPointer...>`),
// so the module is loaded whenever LibSignalClient is imported; if the module
// map is not on SignalServiceKit's SWIFT_INCLUDE_PATHS, add
// `$(PODS_ROOT)/LibSignalClient/swift/Sources/SignalFfi` to it.
import SignalFfi

// MARK: - Errors

public enum MeshError: Error, CustomStringConvertible {
    /// libsignal_ffi returned an error (message from `signal_error_get_message`).
    case ffi(String)
    /// A bridge byte string did not decode.
    case wire(String)
    /// The mesh node is not running (flag off, toggle off, or not started yet).
    case notRunning
    /// The recipient is not a mesh contact.
    case notMeshRecipient
    /// No Signal session for the sender yet; the bundle was deferred.
    case noSession
    /// The local account is not registered (no ACI identity key / registration id).
    case notRegistered
    /// A field had the wrong length (e.g. a fingerprint that is not 16 bytes).
    case invalidLength(String)

    public var description: String {
        switch self {
        case .ffi(let message): return "MeshError.ffi(\(message))"
        case .wire(let message): return "MeshError.wire(\(message))"
        case .notRunning: return "MeshError.notRunning"
        case .notMeshRecipient: return "MeshError.notMeshRecipient"
        case .noSession: return "MeshError.noSession"
        case .notRegistered: return "MeshError.notRegistered"
        case .invalidLength(let what): return "MeshError.invalidLength(\(what))"
        }
    }
}

// MARK: - Raw helpers (private copies of LibSignalClient's internal utilities)

/// Turns a `SignalFfiError*` into a thrown `MeshError.ffi`.
// verify against generated signal_ffi.h:
//   void signal_error_free(SignalFfiError* err);
//   SignalFfiError* signal_error_get_message(SignalCStringPtr* out, const SignalFfiError* err);
//   void signal_free_string(SignalCStringPtr buf);
private func meshCheckError(_ error: SignalFfiErrorRef?) throws {
    guard let error else { return }
    defer { signal_error_free(error) }
    var messagePtr: UnsafePointer<CChar>? = nil
    let messageError = signal_error_get_message(&messagePtr, error)
    if messageError == nil, let messagePtr {
        let message = String(cString: messagePtr)
        signal_free_string(messagePtr)
        throw MeshError.ffi(message)
    }
    if let messageError {
        signal_error_free(messageError)
    }
    throw MeshError.ffi("libsignal error (no message)")
}

/// Copies an owned buffer into `Data` and frees it.
// verify against generated signal_ffi.h:
//   typedef struct { uint8_t* base; size_t length; } SignalOwnedBuffer;
//   void signal_free_buffer(const uint8_t* buf, size_t buf_len);
private func meshData(consuming buffer: SignalOwnedBuffer) -> Data {
    guard let base = buffer.base else {
        return Data()
    }
    let data = Data(bytes: base, count: buffer.length)
    signal_free_buffer(base, buffer.length)
    return data
}

/// Splits an owned bytestring array into `[Data]` and frees it.
// verify against generated signal_ffi.h:
//   typedef struct { SignalOwnedBuffer bytes; SignalOwnedBufferOfusize lengths; } SignalBytestringArray;
//   void signal_free_bytestring_array(SignalBytestringArray array);
private func meshDataArray(consuming array: SignalBytestringArray) -> [Data] {
    var result: [Data] = []
    if let bytesBase = array.bytes.base, let lengthsBase = array.lengths.base {
        var offset = 0
        for index in 0..<array.lengths.length {
            let length = lengthsBase[index]
            if offset + length <= array.bytes.length {
                result.append(Data(bytes: bytesBase + offset, count: length))
            }
            offset += length
        }
    }
    signal_free_bytestring_array(array)
    return result
}

private func meshInvokeReturningData(_ fn: (UnsafeMutablePointer<SignalOwnedBuffer>) -> SignalFfiErrorRef?) throws -> Data {
    var buffer = SignalOwnedBuffer()
    try meshCheckError(fn(&buffer))
    return meshData(consuming: buffer)
}

private func meshInvokeReturningDataArray(_ fn: (UnsafeMutablePointer<SignalBytestringArray>) -> SignalFfiErrorRef?) throws -> [Data] {
    var array = SignalBytestringArray()
    try meshCheckError(fn(&array))
    return meshDataArray(consuming: array)
}

private func meshInvokeReturningString(_ fn: (UnsafeMutablePointer<UnsafePointer<CChar>?>) -> SignalFfiErrorRef?) throws -> String {
    var output: UnsafePointer<CChar>? = nil
    try meshCheckError(fn(&output))
    guard let output else {
        return ""
    }
    let result = String(cString: output)
    signal_free_string(output)
    return result
}

private func meshInvokeReturningInteger<T: FixedWidthInteger>(_ fn: (UnsafeMutablePointer<T>) -> SignalFfiErrorRef?) throws -> T {
    var output: T = 0
    try meshCheckError(fn(&output))
    return output
}

private func meshInvokeReturningBool(_ fn: (UnsafeMutablePointer<Bool>) -> SignalFfiErrorRef?) throws -> Bool {
    var output = false
    try meshCheckError(fn(&output))
    return output
}

extension Data {
    /// Borrows the bytes as a `SignalBorrowedBuffer` for the duration of `body`.
    // verify against generated signal_ffi.h:
    //   typedef struct { const uint8_t* base; size_t length; } SignalBorrowedBuffer;
    fileprivate func withMeshBorrowedBuffer<R>(_ body: (SignalBorrowedBuffer) throws -> R) rethrows -> R {
        try withUnsafeBytes { raw in
            try body(SignalBorrowedBuffer(
                base: raw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                length: raw.count,
            ))
        }
    }
}

/// Serializes a libsignal `PublicKey` handle owned by us and destroys it.
// verify against generated signal_ffi.h:
//   SignalFfiError* signal_publickey_serialize(SignalOwnedBuffer* out, SignalConstPointerPublicKey obj);
//   SignalFfiError* signal_publickey_destroy(SignalMutPointerPublicKey p);
private func meshConsumePublicKey(_ handle: SignalMutPointerPublicKey) throws -> PublicKey {
    defer { _ = signal_publickey_destroy(handle) }
    let bytes = try meshInvokeReturningData {
        signal_publickey_serialize($0, SignalConstPointerPublicKey(raw: handle.raw))
    }
    return try PublicKey(bytes)
}

/// Serializes a libsignal `KyberPublicKey` handle owned by us and destroys it.
// verify against generated signal_ffi.h:
//   SignalFfiError* signal_kyber_public_key_serialize(SignalOwnedBuffer* out, SignalConstPointerKyberPublicKey obj);
//   SignalFfiError* signal_kyber_public_key_destroy(SignalMutPointerKyberPublicKey p);
private func meshConsumeKyberPublicKey(_ handle: SignalMutPointerKyberPublicKey) throws -> KEMPublicKey {
    defer { _ = signal_kyber_public_key_destroy(handle) }
    let bytes = try meshInvokeReturningData {
        signal_kyber_public_key_serialize($0, SignalConstPointerKyberPublicKey(raw: handle.raw))
    }
    return try KEMPublicKey(bytes)
}

// MARK: - MeshIdentity

/// Wrapper for the bridge's `MeshIdentity` handle: the local mesh identity
/// (Signal identity key pair + one signed prekey + one Kyber prekey + name).
public final class MeshIdentity {
    // verify against generated signal_ffi.h:
    //   typedef struct { SignalMeshIdentity* raw; } SignalMutPointerMeshIdentity;
    //   typedef struct { const SignalMeshIdentity* raw; } SignalConstPointerMeshIdentity;
    private let handle: SignalMutPointerMeshIdentity

    private init(handle: SignalMutPointerMeshIdentity) {
        self.handle = handle
    }

    deinit {
        // verify against generated signal_ffi.h: SignalFfiError* signal_mesh_identity_destroy(SignalMutPointerMeshIdentity p);
        _ = signal_mesh_identity_destroy(handle)
    }

    fileprivate var constHandle: SignalConstPointerMeshIdentity {
        SignalConstPointerMeshIdentity(raw: handle.raw)
    }

    /// `MeshIdentity_Generate`: a fresh random identity (tests / no account).
    public static func generate(name: String) throws -> MeshIdentity {
        var out = SignalMutPointerMeshIdentity()
        try meshCheckError(signal_mesh_identity_generate(&out, name))
        return MeshIdentity(handle: out)
    }

    /// `MeshIdentity_FromIdentityKeyPair`: builds the mesh identity around the
    /// app's serialized `IdentityKeyPair` so the mesh fingerprint is the app's
    /// identity. The returned prekey records must be stored in the app's stores.
    public static func fromIdentityKeyPair(_ serializedKeyPair: Data, registrationId: UInt32, name: String) throws -> MeshIdentity {
        var out = SignalMutPointerMeshIdentity()
        try serializedKeyPair.withMeshBorrowedBuffer { keyPair in
            try meshCheckError(signal_mesh_identity_from_identity_key_pair(&out, keyPair, registrationId, name))
        }
        return MeshIdentity(handle: out)
    }

    /// `MeshIdentity_Import`: restores an identity from `export()`.
    public static func `import`(_ data: Data) throws -> MeshIdentity {
        var out = SignalMutPointerMeshIdentity()
        try data.withMeshBorrowedBuffer { blob in
            try meshCheckError(signal_mesh_identity_import(&out, blob))
        }
        return MeshIdentity(handle: out)
    }

    /// `MeshIdentity_Export`: a blob that keeps the prekeys stable across launches.
    public func export() throws -> Data {
        try meshInvokeReturningData { signal_mesh_identity_export($0, constHandle) }
    }

    /// `MeshIdentity_Fingerprint`: 16 bytes.
    public func fingerprint() throws -> Data {
        try meshInvokeReturningData { signal_mesh_identity_fingerprint($0, constHandle) }
    }

    /// `MeshIdentity_Card`: the encoded contact card.
    public func cardBytes() throws -> Data {
        try meshInvokeReturningData { signal_mesh_identity_card($0, constHandle) }
    }

    /// `MeshIdentity_SignedPreKeyRecord`, serialized through
    /// `signal_signed_pre_key_record_serialize` and re-wrapped with the public
    /// `SignedPreKeyRecord(bytes:)` initializer (the handle initializer is internal).
    public func signedPreKeyRecord() throws -> SignedPreKeyRecord {
        // verify against generated signal_ffi.h:
        //   SignalFfiError* signal_mesh_identity_signed_pre_key_record(SignalMutPointerSignedPreKeyRecord* out, SignalConstPointerMeshIdentity identity);
        //   SignalFfiError* signal_signed_pre_key_record_serialize(SignalOwnedBuffer* out, SignalConstPointerSignedPreKeyRecord obj);
        //   SignalFfiError* signal_signed_pre_key_record_destroy(SignalMutPointerSignedPreKeyRecord p);
        var record = SignalMutPointerSignedPreKeyRecord()
        try meshCheckError(signal_mesh_identity_signed_pre_key_record(&record, constHandle))
        defer { _ = signal_signed_pre_key_record_destroy(record) }
        let bytes = try meshInvokeReturningData {
            signal_signed_pre_key_record_serialize($0, SignalConstPointerSignedPreKeyRecord(raw: record.raw))
        }
        return try SignedPreKeyRecord(bytes: bytes)
    }

    /// `MeshIdentity_KyberPreKeyRecord`, serialized and re-wrapped like the signed prekey.
    public func kyberPreKeyRecord() throws -> KyberPreKeyRecord {
        // verify against generated signal_ffi.h:
        //   SignalFfiError* signal_mesh_identity_kyber_pre_key_record(SignalMutPointerKyberPreKeyRecord* out, SignalConstPointerMeshIdentity identity);
        //   SignalFfiError* signal_kyber_pre_key_record_serialize(SignalOwnedBuffer* out, SignalConstPointerKyberPreKeyRecord obj);
        //   SignalFfiError* signal_kyber_pre_key_record_destroy(SignalMutPointerKyberPreKeyRecord p);
        var record = SignalMutPointerKyberPreKeyRecord()
        try meshCheckError(signal_mesh_identity_kyber_pre_key_record(&record, constHandle))
        defer { _ = signal_kyber_pre_key_record_destroy(record) }
        let bytes = try meshInvokeReturningData {
            signal_kyber_pre_key_record_serialize($0, SignalConstPointerKyberPreKeyRecord(raw: record.raw))
        }
        return try KyberPreKeyRecord(bytes: bytes)
    }
}

// MARK: - MeshContactCard

/// Wrapper for the bridge's `MeshContactCard` handle: a signed card carrying
/// identity key, signed prekey and Kyber prekey (see docs/offline-mesh.md §3).
public final class MeshContactCard {
    // verify against generated signal_ffi.h:
    //   typedef struct { SignalMeshContactCard* raw; } SignalMutPointerMeshContactCard;
    //   typedef struct { const SignalMeshContactCard* raw; } SignalConstPointerMeshContactCard;
    private let handle: SignalMutPointerMeshContactCard

    private init(handle: SignalMutPointerMeshContactCard) {
        self.handle = handle
    }

    deinit {
        // verify against generated signal_ffi.h: SignalFfiError* signal_mesh_contact_card_destroy(SignalMutPointerMeshContactCard p);
        _ = signal_mesh_contact_card_destroy(handle)
    }

    fileprivate var constHandle: SignalConstPointerMeshContactCard {
        SignalConstPointerMeshContactCard(raw: handle.raw)
    }

    /// `MeshContactCard_Decode`: both signatures are checked; tampered cards throw.
    public convenience init(bytes: Data) throws {
        var out = SignalMutPointerMeshContactCard()
        try bytes.withMeshBorrowedBuffer { buffer in
            try meshCheckError(signal_mesh_contact_card_decode(&out, buffer))
        }
        self.init(handle: out)
    }

    /// `MeshContactCard_FromBase64`: the URL-safe base64 form used in QR codes.
    public convenience init(base64: String) throws {
        var out = SignalMutPointerMeshContactCard()
        try meshCheckError(signal_mesh_contact_card_from_base64(&out, base64))
        self.init(handle: out)
    }

    /// `MeshContactCard_Encode`.
    public func encode() throws -> Data {
        try meshInvokeReturningData { signal_mesh_contact_card_encode($0, constHandle) }
    }

    /// `MeshContactCard_ToBase64`.
    public func base64() throws -> String {
        try meshInvokeReturningString { signal_mesh_contact_card_to_base64($0, constHandle) }
    }

    /// `MeshContactCard_Fingerprint`: 16 bytes.
    public func fingerprint() throws -> Data {
        try meshInvokeReturningData { signal_mesh_contact_card_fingerprint($0, constHandle) }
    }

    /// `MeshContactCard_Name`.
    public func name() throws -> String {
        try meshInvokeReturningString { signal_mesh_contact_card_name($0, constHandle) }
    }

    /// `MeshContactCard_RegistrationId`.
    public func registrationId() throws -> UInt32 {
        try meshInvokeReturningInteger { signal_mesh_contact_card_registration_id($0, constHandle) }
    }

    /// `MeshContactCard_DeviceId`.
    public func deviceId() throws -> UInt32 {
        try meshInvokeReturningInteger { signal_mesh_contact_card_device_id($0, constHandle) }
    }

    /// `MeshContactCard_CreatedAt`: seconds since the epoch.
    public func createdAt() throws -> UInt64 {
        try meshInvokeReturningInteger { signal_mesh_contact_card_created_at($0, constHandle) }
    }

    /// `MeshContactCard_IdentityKey`.
    public func identityKey() throws -> IdentityKey {
        // verify against generated signal_ffi.h:
        //   SignalFfiError* signal_mesh_contact_card_identity_key(SignalMutPointerPublicKey* out, SignalConstPointerMeshContactCard card);
        var key = SignalMutPointerPublicKey()
        try meshCheckError(signal_mesh_contact_card_identity_key(&key, constHandle))
        return IdentityKey(publicKey: try meshConsumePublicKey(key))
    }

    /// `MeshContactCard_AddressName`: the `ProtocolAddress` name meshlink's
    /// internal-crypto mode uses (fingerprint hex). The iOS app keys its
    /// session/identity stores by ServiceId, so `MeshCrypto` addresses the
    /// contact by the fingerprint-derived ACI instead (see MeshContactStore);
    /// this value is exposed for diagnostics and interop tooling.
    public func addressName() throws -> String {
        try meshInvokeReturningString { signal_mesh_contact_card_address_name($0, constHandle) }
    }

    /// `MeshContactCard_SafetyNumber`.
    public func safetyNumber(mine: MeshContactCard) throws -> String {
        try meshInvokeReturningString { signal_mesh_contact_card_safety_number($0, mine.constHandle, constHandle) }
    }

    /// `MeshContactCard_PreKeyBundle`, rebuilt as a `LibSignalClient.PreKeyBundle`
    /// through the bundle getters (the handle initializer is internal to
    /// LibSignalClient).
    public func preKeyBundle() throws -> PreKeyBundle {
        // verify against generated signal_ffi.h:
        //   SignalFfiError* signal_mesh_contact_card_pre_key_bundle(SignalMutPointerPreKeyBundle* out, SignalConstPointerMeshContactCard card);
        //   SignalFfiError* signal_pre_key_bundle_destroy(SignalMutPointerPreKeyBundle p);
        //   SignalFfiError* signal_pre_key_bundle_get_registration_id(uint32_t* out, SignalConstPointerPreKeyBundle obj);
        //   SignalFfiError* signal_pre_key_bundle_get_device_id(uint32_t* out, SignalConstPointerPreKeyBundle obj);
        //   SignalFfiError* signal_pre_key_bundle_get_signed_pre_key_id(uint32_t* out, SignalConstPointerPreKeyBundle obj);
        //   SignalFfiError* signal_pre_key_bundle_get_signed_pre_key_public(SignalMutPointerPublicKey* out, SignalConstPointerPreKeyBundle obj);
        //   SignalFfiError* signal_pre_key_bundle_get_signed_pre_key_signature(SignalOwnedBuffer* out, SignalConstPointerPreKeyBundle obj);
        //   SignalFfiError* signal_pre_key_bundle_get_identity_key(SignalMutPointerPublicKey* out, SignalConstPointerPreKeyBundle p);
        //   SignalFfiError* signal_pre_key_bundle_get_kyber_pre_key_id(uint32_t* out, SignalConstPointerPreKeyBundle obj);
        //   SignalFfiError* signal_pre_key_bundle_get_kyber_pre_key_public(SignalMutPointerKyberPublicKey* out, SignalConstPointerPreKeyBundle bundle);
        //   SignalFfiError* signal_pre_key_bundle_get_kyber_pre_key_signature(SignalOwnedBuffer* out, SignalConstPointerPreKeyBundle obj);
        var bundle = SignalMutPointerPreKeyBundle()
        try meshCheckError(signal_mesh_contact_card_pre_key_bundle(&bundle, constHandle))
        defer { _ = signal_pre_key_bundle_destroy(bundle) }
        let constBundle = SignalConstPointerPreKeyBundle(raw: bundle.raw)

        let registrationId: UInt32 = try meshInvokeReturningInteger { signal_pre_key_bundle_get_registration_id($0, constBundle) }
        let deviceId: UInt32 = try meshInvokeReturningInteger { signal_pre_key_bundle_get_device_id($0, constBundle) }
        let signedPreKeyId: UInt32 = try meshInvokeReturningInteger { signal_pre_key_bundle_get_signed_pre_key_id($0, constBundle) }
        var signedPreKeyHandle = SignalMutPointerPublicKey()
        try meshCheckError(signal_pre_key_bundle_get_signed_pre_key_public(&signedPreKeyHandle, constBundle))
        let signedPreKey = try meshConsumePublicKey(signedPreKeyHandle)
        let signedPreKeySignature = try meshInvokeReturningData { signal_pre_key_bundle_get_signed_pre_key_signature($0, constBundle) }
        var identityKeyHandle = SignalMutPointerPublicKey()
        try meshCheckError(signal_pre_key_bundle_get_identity_key(&identityKeyHandle, constBundle))
        let identityKey = IdentityKey(publicKey: try meshConsumePublicKey(identityKeyHandle))
        let kyberPreKeyId: UInt32 = try meshInvokeReturningInteger { signal_pre_key_bundle_get_kyber_pre_key_id($0, constBundle) }
        var kyberPreKeyHandle = SignalMutPointerKyberPublicKey()
        try meshCheckError(signal_pre_key_bundle_get_kyber_pre_key_public(&kyberPreKeyHandle, constBundle))
        let kyberPreKey = try meshConsumeKyberPublicKey(kyberPreKeyHandle)
        let kyberPreKeySignature = try meshInvokeReturningData { signal_pre_key_bundle_get_kyber_pre_key_signature($0, constBundle) }

        return try PreKeyBundle(
            registrationId: registrationId,
            deviceId: deviceId,
            signedPrekeyId: signedPreKeyId,
            signedPrekey: signedPreKey,
            signedPrekeySignature: signedPreKeySignature,
            identity: identityKey,
            kyberPrekeyId: kyberPreKeyId,
            kyberPrekey: kyberPreKey,
            kyberPrekeySignature: kyberPreKeySignature,
        )
    }
}

// MARK: - MeshNode

/// Wrapper for the bridge's `MeshNode` handle. All calls block briefly on the
/// node's own tokio runtime; `nextEvent`/`linkRead` block up to their timeout
/// and are meant to be driven from background threads (see MeshNodeService).
public final class MeshNode {
    // verify against generated signal_ffi.h:
    //   typedef struct { SignalMeshNode* raw; } SignalMutPointerMeshNode;
    //   typedef struct { const SignalMeshNode* raw; } SignalConstPointerMeshNode;
    private let handle: SignalMutPointerMeshNode

    /// Link ids are the `u64` handed out by `MeshNode_AttachLink`.
    public typealias LinkId = UInt64

    /// `MeshNode_New`. `statePath` nil/empty: nothing persists. `externalCrypto`
    /// true: the app encrypts/decrypts (the mode this integration uses).
    public init(identity: MeshIdentity, statePath: String?, externalCrypto: Bool, antiEntropySecs: UInt32) throws {
        var out = SignalMutPointerMeshNode()
        // verify against generated signal_ffi.h: Option<String> crosses as a nullable `const int8_t*`.
        if let statePath, !statePath.isEmpty {
            try statePath.withCString { path in
                try meshCheckError(signal_mesh_node_new(&out, identity.constHandle, path, externalCrypto, antiEntropySecs))
            }
        } else {
            try meshCheckError(signal_mesh_node_new(&out, identity.constHandle, nil, externalCrypto, antiEntropySecs))
        }
        self.handle = out
    }

    deinit {
        // verify against generated signal_ffi.h: SignalFfiError* signal_mesh_node_destroy(SignalMutPointerMeshNode p);
        _ = signal_mesh_node_destroy(handle)
    }

    private var constHandle: SignalConstPointerMeshNode {
        SignalConstPointerMeshNode(raw: handle.raw)
    }

    private static func requireLength(_ data: Data, _ length: Int, _ what: String) throws {
        guard data.count == length else {
            throw MeshError.invalidLength("\(what) must be \(length) bytes, got \(data.count)")
        }
    }

    // MARK: Identity

    /// `MeshNode_Fingerprint`.
    public func fingerprint() throws -> Data {
        try meshInvokeReturningData { signal_mesh_node_fingerprint($0, constHandle) }
    }

    /// `MeshNode_Card`: our encoded card (reflects `rename`).
    public func cardBytes() throws -> Data {
        try meshInvokeReturningData { signal_mesh_node_card($0, constHandle) }
    }

    /// `MeshNode_Rename`.
    public func rename(_ name: String) throws {
        try meshCheckError(signal_mesh_node_rename(constHandle, name))
    }

    // MARK: Links

    /// `MeshNode_AttachLink`: returns the link id. `mtu` >= 64.
    public func attachLink(mtu: UInt32, maxBytesPerSec: UInt32, maxFramesPerSec: UInt32) throws -> LinkId {
        try meshInvokeReturningInteger { signal_mesh_node_attach_link($0, constHandle, mtu, maxBytesPerSec, maxFramesPerSec) }
    }

    /// `MeshNode_DetachLink`.
    public func detachLink(_ link: LinkId) {
        do {
            try meshCheckError(signal_mesh_node_detach_link(constHandle, link))
        } catch {
            Logger.warn("detach_link failed: \(error)")
        }
    }

    /// `MeshNode_LinkWrite`: a frame received from the wire. False if the link
    /// is gone or the node applied back-pressure for a second.
    public func linkWrite(_ link: LinkId, frame: Data) throws -> Bool {
        try frame.withMeshBorrowedBuffer { buffer in
            try meshInvokeReturningBool { signal_mesh_node_link_write($0, constHandle, link, buffer) }
        }
    }

    /// `MeshNode_LinkRead`: the next frame to transmit, or empty after `timeoutMs`.
    public func linkRead(_ link: LinkId, timeoutMs: UInt32) throws -> Data {
        try meshInvokeReturningData { signal_mesh_node_link_read($0, constHandle, link, timeoutMs) }
    }

    // MARK: Events

    /// `MeshNode_NextEvent`: the next encoded event (see `MeshEvent`), or empty
    /// after `timeoutMs`.
    public func nextEvent(timeoutMs: UInt32) throws -> Data {
        try meshInvokeReturningData { signal_mesh_node_next_event($0, constHandle, timeoutMs) }
    }

    // MARK: Contacts

    /// `MeshNode_AddContact`: returns the 16-byte fingerprint.
    public func addContact(cardBytes: Data) throws -> Data {
        try cardBytes.withMeshBorrowedBuffer { buffer in
            try meshInvokeReturningData { signal_mesh_node_add_contact($0, constHandle, buffer) }
        }
    }

    /// `MeshNode_RemoveContact`.
    public func removeContact(fingerprint: Data) throws -> Bool {
        try Self.requireLength(fingerprint, 16, "fingerprint")
        return try fingerprint.withMeshBorrowedBuffer { buffer in
            try meshInvokeReturningBool { signal_mesh_node_remove_contact($0, constHandle, buffer) }
        }
    }

    /// `MeshNode_Contact`: the encoded card, or nil if unknown.
    public func contactCardBytes(fingerprint: Data) throws -> Data? {
        try Self.requireLength(fingerprint, 16, "fingerprint")
        let bytes = try fingerprint.withMeshBorrowedBuffer { buffer in
            try meshInvokeReturningData { signal_mesh_node_contact($0, constHandle, buffer) }
        }
        return bytes.isEmpty ? nil : bytes
    }

    /// `MeshNode_Contacts`: fingerprints of every known contact.
    public func contacts() throws -> [Data] {
        try meshInvokeReturningDataArray { signal_mesh_node_contacts($0, constHandle) }
    }

    /// `MeshNode_SafetyNumber`.
    public func safetyNumber(fingerprint: Data) throws -> String {
        try Self.requireLength(fingerprint, 16, "fingerprint")
        return try fingerprint.withMeshBorrowedBuffer { buffer in
            try meshInvokeReturningString { signal_mesh_node_safety_number($0, constHandle, buffer) }
        }
    }

    /// `MeshNode_BroadcastCard`: beacon our card; returns the bundle id.
    public func broadcastCard() throws -> Data {
        try meshInvokeReturningData { signal_mesh_node_broadcast_card($0, constHandle) }
    }

    // MARK: Messages (external-crypto mode)

    /// `MeshNode_PrepareText`: the encoded prepared list (one item) the app
    /// must encrypt before `sendCiphertext`.
    public func prepareText(to fingerprint: Data, plaintext: Data) throws -> [MeshPrepared] {
        try Self.requireLength(fingerprint, 16, "fingerprint")
        let encoded = try fingerprint.withMeshBorrowedBuffer { to in
            try plaintext.withMeshBorrowedBuffer { body in
                try meshInvokeReturningData { signal_mesh_node_prepare_text($0, constHandle, to, body) }
            }
        }
        return try MeshPrepared.decodeList(encoded)
    }

    /// `MeshNode_SendCiphertext`: `messageType` is libsignal's ciphertext type
    /// (3 = PreKeySignalMessage, 2 = SignalMessage). Returns the bundle id.
    public func sendCiphertext(to fingerprint: Data, commit: Data, messageType: UInt32, ciphertext: Data) throws -> Data {
        try Self.requireLength(fingerprint, 16, "fingerprint")
        try Self.requireLength(commit, 16, "commit")
        return try fingerprint.withMeshBorrowedBuffer { to in
            try commit.withMeshBorrowedBuffer { commitBuffer in
                try ciphertext.withMeshBorrowedBuffer { body in
                    try meshInvokeReturningData {
                        signal_mesh_node_send_ciphertext($0, constHandle, to, commitBuffer, messageType, body)
                    }
                }
            }
        }
    }

    /// `MeshNode_DeliverPlaintext`: the app decrypted `Event.ciphertext`.
    public func deliverPlaintext(bundleId: Data, plaintext: Data) throws {
        try Self.requireLength(bundleId, 16, "bundle id")
        try bundleId.withMeshBorrowedBuffer { id in
            try plaintext.withMeshBorrowedBuffer { body in
                try meshCheckError(signal_mesh_node_deliver_plaintext(constHandle, id, body))
            }
        }
    }

    /// `MeshNode_Defer`: the app could not decrypt yet; re-announced later.
    public func `defer`(bundleId: Data) throws {
        try Self.requireLength(bundleId, 16, "bundle id")
        try bundleId.withMeshBorrowedBuffer { id in
            try meshCheckError(signal_mesh_node_defer(constHandle, id))
        }
    }

    // MARK: Groups (no UI yet; kept for completeness)

    /// `MeshNode_PrepareGroupCreate`: `[group id 16][prepared list]`.
    public func prepareGroupCreate(name: String, members: [Data]) throws -> (groupId: Data, prepared: [MeshPrepared]) {
        var concatenated = Data()
        for member in members {
            try Self.requireLength(member, 16, "member fingerprint")
            concatenated.append(member)
        }
        let encoded = try concatenated.withMeshBorrowedBuffer { buffer in
            try meshInvokeReturningData { signal_mesh_node_prepare_group_create($0, constHandle, name, buffer) }
        }
        guard encoded.count >= 16 else {
            throw MeshError.wire("prepare_group_create returned fewer than 16 bytes")
        }
        return (Data(encoded.prefix(16)), try MeshPrepared.decodeList(Data(encoded.dropFirst(16))))
    }

    /// `MeshNode_PrepareGroupText`.
    public func prepareGroupText(group: Data, plaintext: Data) throws -> [MeshPrepared] {
        try Self.requireLength(group, 16, "group id")
        let encoded = try group.withMeshBorrowedBuffer { groupBuffer in
            try plaintext.withMeshBorrowedBuffer { body in
                try meshInvokeReturningData { signal_mesh_node_prepare_group_text($0, constHandle, groupBuffer, body) }
            }
        }
        return try MeshPrepared.decodeList(encoded)
    }

    /// `MeshNode_Groups`: encoded groups.
    public func groups() throws -> [Data] {
        try meshInvokeReturningDataArray { signal_mesh_node_groups($0, constHandle) }
    }

    // MARK: Diagnostics

    /// `MeshNode_Stats`.
    public func stats() throws -> MeshStats {
        try MeshStats.decode(try meshInvokeReturningData { signal_mesh_node_stats($0, constHandle) })
    }

    /// `MeshNode_Flush`: persist state now.
    public func flush() throws {
        try meshCheckError(signal_mesh_node_flush(constHandle))
    }
}
