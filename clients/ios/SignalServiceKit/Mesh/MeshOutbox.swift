//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Sending a text to a mesh contact: `MeshNode_PrepareText` -> MeshCrypto ->
//! `MeshNode_SendCiphertext`, remembering bundle id -> message so the
//! recipient's ack (`Event::Delivered`) becomes a delivery receipt.
//!
//! Hooked from `MessageSender.sendMessage(_:)`: when the feature flag is on
//! and the recipient is a mesh contact, the message goes here instead of the
//! chat server.

import Foundation
public import LibSignalClient

public final class MeshOutbox {
    public static let shared = MeshOutbox()

    private struct Pending: Equatable {
        let messageUniqueId: String
        let aci: Aci
    }

    /// bundle id -> message. Mirrored in `kvStore` so acks that arrive after
    /// a relaunch (the node persists its carry store) still find the message.
    private let pending = AtomicValue<[Data: Pending]>([:], lock: .init())
    private let kvStore = KeyValueStore(collection: "MeshOutbox")

    private init() {}

    // MARK: - Routing

    /// If `preparedOutgoingMessage` is a text for a mesh contact and the mesh
    /// node is running, sends it over the mesh and returns the result the
    /// MessageSender should report. Returns nil to let the normal network
    /// send proceed.
    public func sendIfMeshRecipient(_ preparedOutgoingMessage: PreparedOutgoingMessage) async -> SendMessageResult? {
        guard FeatureFlags.meshTransport, let node = MeshNodeService.shared.nodeIfRunning else {
            return nil
        }
        do {
            return try await preparedOutgoingMessage.send { message -> SendMessageResult? in
                try await self.route(message, prepared: preparedOutgoingMessage, node: node)
            }
        } catch {
            Logger.warn("Mesh send failed: \(error)")
            return .overallFailure(error)
        }
    }

    private struct Target {
        let aci: Aci
        let record: MeshContactRecord
        let body: String
    }

    private func route(
        _ message: TSOutgoingMessage,
        prepared: PreparedOutgoingMessage,
        node: MeshNode,
    ) async throws -> SendMessageResult? {
        let databaseStorage = SSKEnvironment.shared.databaseStorageRef

        let target: Target? = databaseStorage.read { tx in
            guard
                let thread = message.thread(tx: tx) as? TSContactThread,
                let aci = thread.contactAddress.aci,
                let record = MeshContactStore.shared.fetch(aci: aci, tx: tx)
            else {
                return nil
            }
            guard let body = message.body, !body.isEmpty else {
                // Attachments, reactions, typing etc. are not carried over the
                // mesh (4 kB bundles). Let the normal path deal with it.
                return nil
            }
            return Target(aci: aci, record: record, body: body)
        }
        guard let target else {
            return nil
        }

        Logger.info("Routing message \(message.uniqueId) to mesh contact \(target.record.fingerprint.meshHex)")

        do {
            try await databaseStorage.awaitableWrite { tx in
                prepared.updateAllUnsentRecipientsAsSending(tx: tx)

                let card = try MeshContactCard(bytes: target.record.card)
                let items = try node.prepareText(to: target.record.fingerprint, plaintext: Data(target.body.utf8))
                guard let item = items.first else {
                    throw MeshError.wire("prepare_text returned no items")
                }
                let (messageType, ciphertext) = try MeshCrypto.encrypt(plaintext: item.plaintext, for: card, tx: tx)
                let bundleId = try node.sendCiphertext(
                    to: item.to,
                    commit: item.commit,
                    messageType: messageType,
                    ciphertext: ciphertext,
                )
                self.remember(bundleId: bundleId, messageUniqueId: message.uniqueId, aci: target.aci, tx: tx)

                let sentServiceIds: [ServiceId] = [target.aci]
                message.updateWithSentRecipients(sentServiceIds, wasSentByUD: false, tx: tx)
                prepared.updateWithSendSuccess(tx: tx)
            }
            MeshNodeService.shared.publishStatusChanged()
            return .success
        } catch {
            await databaseStorage.awaitableWrite { tx in
                prepared.updateWithAllSendingRecipientsMarkedAsFailed(error: error, tx: tx)
            }
            return .overallFailure(error)
        }
    }

    // MARK: - bundle id -> message

    private func remember(bundleId: Data, messageUniqueId: String, aci: Aci, tx: DBWriteTransaction) {
        pending.update { $0[bundleId] = Pending(messageUniqueId: messageUniqueId, aci: aci) }
        kvStore.setString("\(aci.serviceIdUppercaseString)|\(messageUniqueId)", key: bundleId.meshHex, transaction: tx)
    }

    private func lookup(bundleId: Data, tx: DBReadTransaction) -> Pending? {
        if let entry = pending.get()[bundleId] {
            return entry
        }
        guard let stored = kvStore.getString(bundleId.meshHex, transaction: tx) else {
            return nil
        }
        let parts = stored.split(separator: "|", maxSplits: 1).map(String.init)
        guard parts.count == 2, let aci = Aci.parseFrom(aciString: parts[0]) else {
            return nil
        }
        return Pending(messageUniqueId: parts[1], aci: aci)
    }

    /// Whether any message to `aci` is still waiting for its mesh ack.
    public func hasUnacknowledged(for aci: Aci) -> Bool {
        pending.get().values.contains { $0.aci == aci }
    }

    /// Restores the pending map from disk (called once when the node starts).
    func loadPending(tx: DBReadTransaction) {
        var restored: [Data: Pending] = [:]
        for key in kvStore.allKeys(transaction: tx) {
            guard let bundleId = Data(meshHex: key), let entry = lookup(bundleId: bundleId, tx: tx) else {
                continue
            }
            restored[bundleId] = entry
        }
        pending.set(restored)
    }

    /// `Event::Delivered`: the recipient acknowledged `bundleId`. Marks the
    /// outgoing message delivered to that recipient.
    func markDelivered(bundleId: Data) {
        SSKEnvironment.shared.databaseStorageRef.write { tx in
            guard let entry = lookup(bundleId: bundleId, tx: tx) else {
                Logger.info("Mesh ack for unknown bundle \(bundleId.meshHex)")
                return
            }
            pending.update { $0.removeValue(forKey: bundleId) }
            kvStore.removeValue(forKey: bundleId.meshHex, transaction: tx)

            guard
                let message = TSInteraction.anyFetch(uniqueId: entry.messageUniqueId, transaction: tx) as? TSOutgoingMessage,
                let deviceId = DeviceId(validating: 1)
            else {
                return
            }
            message.update(
                withDeliveredRecipient: SignalServiceAddress(entry.aci),
                deviceId: deviceId,
                deliveryTimestamp: Date.ows_millisecondTimestamp(),
                context: PassthroughDeliveryReceiptContext(),
                tx: tx,
            )
        }
        MeshNodeService.shared.publishStatusChanged()
    }
}

extension Data {
    /// Parses lowercase/uppercase hex; nil on odd length or non-hex characters.
    init?(meshHex hex: String) {
        let characters = Array(hex.utf8)
        guard characters.count % 2 == 0 else {
            return nil
        }
        var bytes = Data(capacity: characters.count / 2)
        var index = 0
        while index < characters.count {
            guard
                let high = Data.meshHexValue(characters[index]),
                let low = Data.meshHexValue(characters[index + 1])
            else {
                return nil
            }
            bytes.append(high << 4 | low)
            index += 2
        }
        self = bytes
    }

    private static func meshHexValue(_ character: UInt8) -> UInt8? {
        switch character {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): return character - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): return character - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): return character - UInt8(ascii: "A") + 10
        default: return nil
        }
    }
}
