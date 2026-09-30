//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! External-crypto glue: meshlink hands us plaintext envelopes to encrypt and
//! ciphertext to decrypt; the Signal Protocol runs on the app's own ACI
//! session/identity/prekey stores so a mesh session and an internet session
//! with the same person would be one ratchet, not two.
//!
//! Addressing: the app's stores resolve `ProtocolAddress` names to
//! `SignalRecipient`s through their ServiceId, so a mesh contact is addressed
//! by its fingerprint-derived ACI (see MeshContactStore), not by the
//! fingerprint-hex name meshlink's internal-crypto mode uses
//! (`MeshContactCard.addressName`). The address name never crosses the wire,
//! so both nodes may pick their own.

import Foundation
public import LibSignalClient

public enum MeshCrypto {

    /// libsignal `CiphertextMessageType` bytes as carried in mesh bundles.
    public enum MessageType {
        public static let whisper: UInt8 = 2
        public static let preKey: UInt8 = 3
    }

    // MARK: - Encrypt

    /// Encrypts a prepared plaintext for the contact, starting a session from
    /// the card's prekey bundle (`processPreKeyBundle`) when none exists.
    /// Returns the libsignal message type and the serialized ciphertext.
    public static func encrypt(
        plaintext: Data,
        for card: MeshContactCard,
        tx: DBWriteTransaction,
    ) throws -> (messageType: UInt32, ciphertext: Data) {
        let fingerprint = try card.fingerprint()
        guard let aci = MeshContactStore.aci(forFingerprint: fingerprint) else {
            throw MeshError.invalidLength("fingerprint")
        }
        let (localAddress, sessionStore, identityStore) = try stores(tx: tx)
        // Make sure the recipient row exists; the stores key sessions by it.
        _ = DependenciesBridge.shared.recipientFetcher.fetchOrCreate(serviceId: aci, tx: tx)

        let remoteDeviceId = try card.deviceId()
        let remoteAddress = ProtocolAddress(aci, deviceId: remoteDeviceId)

        let existing = try sessionStore.loadSession(for: remoteAddress, context: tx)
        if existing?.hasCurrentState != true {
            Logger.info("Starting mesh session with \(fingerprint.meshHex) from its card")
            let bundle = try card.preKeyBundle()
            try processPreKeyBundle(
                bundle,
                for: remoteAddress,
                ourAddress: localAddress,
                sessionStore: sessionStore,
                identityStore: identityStore,
                context: tx,
            )
        }

        let message = try signalEncrypt(
            message: plaintext,
            for: remoteAddress,
            localAddress: localAddress,
            sessionStore: sessionStore,
            identityStore: identityStore,
            context: tx,
        )
        return (UInt32(message.messageType.rawValue), message.serialize())
    }

    // MARK: - Decrypt

    /// Decrypts an `Event.ciphertext`. Throws `MeshError.noSession` when the
    /// message cannot be decrypted yet (no session, or a Whisper message that
    /// arrived before the PreKey message that opens the ratchet), in which
    /// case the caller defers the bundle.
    public static func decrypt(
        messageType: UInt8,
        ciphertext: Data,
        from fingerprint: Data,
        tx: DBWriteTransaction,
    ) throws -> Data {
        guard let aci = MeshContactStore.aci(forFingerprint: fingerprint) else {
            throw MeshError.invalidLength("fingerprint")
        }
        let (localAddress, sessionStore, identityStore) = try stores(tx: tx)
        _ = DependenciesBridge.shared.recipientFetcher.fetchOrCreate(serviceId: aci, tx: tx)

        // The sender's device id from its card if we hold one; meshlink
        // identities built from an account use device 1.
        var deviceId: UInt32 = 1
        if let record = MeshContactStore.shared.fetch(fingerprint: fingerprint, tx: tx),
           let card = try? MeshContactCard(bytes: record.card),
           let cardDeviceId = try? card.deviceId() {
            deviceId = cardDeviceId
        }
        let remoteAddress = ProtocolAddress(aci, deviceId: deviceId)

        do {
            switch messageType {
            case MessageType.preKey:
                let preKeyStore = DependenciesBridge.shared.signalProtocolStoreManager.preKeyStore.forIdentity(.aci)
                let message = try PreKeySignalMessage(bytes: ciphertext)
                return try signalDecryptPreKey(
                    message: message,
                    from: remoteAddress,
                    localAddress: localAddress,
                    sessionStore: sessionStore,
                    identityStore: identityStore,
                    preKeyStore: preKeyStore,
                    signedPreKeyStore: preKeyStore,
                    kyberPreKeyStore: preKeyStore,
                    context: tx,
                )
            case MessageType.whisper:
                let message = try SignalMessage(bytes: ciphertext)
                return try signalDecrypt(
                    message: message,
                    from: remoteAddress,
                    to: localAddress,
                    sessionStore: sessionStore,
                    identityStore: identityStore,
                    context: tx,
                )
            default:
                throw MeshError.wire("unsupported mesh ciphertext type \(messageType)")
            }
        } catch LibSignalClient.SignalError.sessionNotFound(let detail) {
            Logger.warn("No session for mesh sender \(fingerprint.meshHex): \(detail)")
            throw MeshError.noSession
        } catch LibSignalClient.SignalError.invalidMessage(let detail) {
            Logger.warn("Mesh message from \(fingerprint.meshHex) not decryptable yet: \(detail)")
            throw MeshError.noSession
        } catch LibSignalClient.SignalError.invalidKeyIdentifier(let detail) {
            Logger.warn("Mesh PreKey message references an unknown prekey: \(detail)")
            throw MeshError.noSession
        }
    }

    // MARK: - Stores

    private static func stores(
        tx: DBWriteTransaction,
    ) throws -> (localAddress: ProtocolAddress, sessionStore: LibSignalClient.SessionStore, identityStore: LibSignalClient.IdentityKeyStore) {
        let tsAccountManager = DependenciesBridge.shared.tsAccountManager
        guard
            let localIdentifiers = tsAccountManager.localIdentifiers(tx: tx),
            let localDeviceId = tsAccountManager.storedDeviceId(tx: tx).ifValid
        else {
            throw MeshError.notRegistered
        }
        let localAddress = ProtocolAddress(localIdentifiers.aci, deviceId: localDeviceId.uint32Value)
        let signalProtocolStore = DependenciesBridge.shared.signalProtocolStoreManager.signalProtocolStore(for: .aci)
        let identityStore = try DependenciesBridge.shared.identityManager.libSignalStore(for: .aci, tx: tx)
        return (localAddress, signalProtocolStore.sessionStore, identityStore)
    }
}
