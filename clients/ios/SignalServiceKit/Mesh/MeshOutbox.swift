//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Sending to a mesh contact: `MeshNode_Prepare*` -> MeshCrypto ->
//! `MeshNode_SendCiphertext`, remembering bundle id -> message so the
//! recipient's ack (`Event::Delivered`) becomes a delivery receipt.
//!
//! Hooked from `MessageSender.sendMessage(_:)`: when the feature flag is on
//! and the recipient is a mesh contact, the message goes here instead of the
//! chat server. What is carried (v3 contract):
//! * the text body: `MeshNode_PrepareText`;
//! * every body attachment up to 4 MiB: `MeshNode_PrepareAttachment`
//!   (manifest + chunks, all encrypted and sent in order; the manifest's
//!   bundle id is the one whose ack marks the message delivered);
//! * an `OutgoingCallMessage` (offer / answer / ICE / hangup / busy / opaque):
//!   the serialized `SSKProtoCallMessage`, via `MeshNode_PrepareCallSignal`.
//! A mesh contact has no server identity, so a message to one is *always*
//! routed here while the node runs, online or not.

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

    /// If `preparedOutgoingMessage` is for a mesh contact and the mesh node is
    /// running, sends it over the mesh and returns the result the
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

    /// One attachment read out of the attachment store, ready to prepare.
    private struct AttachmentPayload {
        let kind: MeshAttachmentKind
        let name: String
        let mime: String
        let data: Data
    }

    private enum Payload {
        /// Text and/or body attachments (streams; read outside the transaction).
        case message(body: String, attachments: [ReferencedAttachmentStream])
        /// Serialized `SSKProtoCallMessage`.
        case callSignal(Data)
    }

    private struct Target {
        let aci: Aci
        let record: MeshContactRecord
        let payload: Payload
        /// A body attachment is still a pointer (not downloaded); the mesh
        /// cannot carry it, so the send fails with a clear message.
        var hasUndownloadedAttachment: Bool = false
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

            if let callMessage = message as? OutgoingCallMessage {
                guard let bytes = Self.serializedCallMessage(callMessage, thread: thread, tx: tx) else {
                    return nil
                }
                return Target(aci: aci, record: record, payload: .callSignal(bytes))
            }

            let body = message.body ?? ""
            var attachments: [ReferencedAttachmentStream] = []
            var hasUndownloadedAttachment = false
            if let messageRowId = message.sqliteRowId {
                let referenced = DependenciesBridge.shared.attachmentStore.fetchReferencedAttachments(
                    for: .messageBodyAttachment(messageRowId: messageRowId),
                    tx: tx,
                )
                for item in referenced {
                    if let stream = item.asReferencedStream {
                        attachments.append(stream)
                    } else {
                        hasUndownloadedAttachment = true
                    }
                }
            }
            guard !body.isEmpty || !attachments.isEmpty || hasUndownloadedAttachment else {
                // Reactions, typing, receipts, stickers-only, etc. are not
                // carried over the mesh. Let the normal path deal with it
                // (it will fail for a mesh contact, which is honest).
                return nil
            }
            return Target(
                aci: aci,
                record: record,
                payload: .message(body: body, attachments: attachments),
                hasUndownloadedAttachment: hasUndownloadedAttachment,
            )
        }
        guard let target else {
            return nil
        }

        Logger.info("Routing message \(message.uniqueId) to mesh contact \(target.record.fingerprint.meshHex)")

        do {
            // Read and size-check attachments before touching the node so a
            // failure leaves nothing half-sent.
            var attachmentPayloads: [AttachmentPayload] = []
            var textBody = ""
            var callSignal: Data?
            switch target.payload {
            case .callSignal(let bytes):
                callSignal = bytes
            case .message(let body, let attachments):
                textBody = body
                attachmentPayloads = try Self.readAttachments(attachments)
            }
            if target.hasUndownloadedAttachment {
                throw MeshError.attachmentNotSendable("An attachment has not finished downloading, so it cannot be sent over the mesh.")
            }

            try await databaseStorage.awaitableWrite { tx in
                prepared.updateAllUnsentRecipientsAsSending(tx: tx)

                let card = try MeshContactCard(bytes: target.record.card)
                let to = target.record.fingerprint

                if let callSignal {
                    let items = try node.prepareCallSignal(to: to, data: callSignal)
                    guard !items.isEmpty else {
                        throw MeshError.wire("prepare_call_signal returned no items")
                    }
                    for item in items {
                        _ = try self.encryptAndSend(item, card: card, node: node, tx: tx)
                    }
                    Logger.info("Sent call signal (\(callSignal.count) bytes) to mesh contact \(to.meshHex)")
                } else {
                    if !textBody.isEmpty {
                        let items = try node.prepareText(to: to, plaintext: Data(textBody.utf8))
                        guard let item = items.first else {
                            throw MeshError.wire("prepare_text returned no items")
                        }
                        let bundleId = try self.encryptAndSend(item, card: card, node: node, tx: tx)
                        self.remember(bundleId: bundleId, messageUniqueId: message.uniqueId, aci: target.aci, tx: tx)
                    }
                    for payload in attachmentPayloads {
                        let items = try node.prepareAttachment(
                            to: to,
                            kind: payload.kind,
                            name: payload.name,
                            mime: payload.mime,
                            data: payload.data,
                        )
                        guard !items.isEmpty else {
                            throw MeshError.wire("prepare_attachment returned no items")
                        }
                        // Contract: the manifest is the first entry; its ack
                        // means the attachment arrived.
                        for (index, item) in items.enumerated() {
                            let bundleId = try self.encryptAndSend(item, card: card, node: node, tx: tx)
                            if index == 0 {
                                self.remember(bundleId: bundleId, messageUniqueId: message.uniqueId, aci: target.aci, tx: tx)
                            }
                        }
                        Logger.info("Sent attachment \(payload.name) (\(payload.data.count) bytes, \(items.count) bundles) to mesh contact \(to.meshHex)")
                    }
                }

                let sentServiceIds: [ServiceId] = [target.aci]
                message.updateWithSentRecipients(sentServiceIds, wasSentByUD: false, tx: tx)
                prepared.updateWithSendSuccess(tx: tx)
            }
            MeshNodeService.shared.publishStatusChanged()
            return .success
        } catch {
            Logger.warn("Mesh send of \(message.uniqueId) failed: \(error)")
            await databaseStorage.awaitableWrite { tx in
                prepared.updateWithAllSendingRecipientsMarkedAsFailed(error: error, tx: tx)
            }
            return .overallFailure(error)
        }
    }

    /// Encrypts one prepared plaintext with the app's Signal session for the
    /// contact and hands the ciphertext to the node. Returns the bundle id.
    private func encryptAndSend(_ item: MeshPrepared, card: MeshContactCard, node: MeshNode, tx: DBWriteTransaction) throws -> Data {
        let (messageType, ciphertext) = try MeshCrypto.encrypt(plaintext: item.plaintext, for: card, tx: tx)
        return try node.sendCiphertext(
            to: item.to,
            commit: item.commit,
            messageType: messageType,
            ciphertext: ciphertext,
        )
    }

    /// Decrypts each body attachment into memory (<= 4 MiB each, the v3 limit).
    private static func readAttachments(_ attachments: [ReferencedAttachmentStream]) throws -> [AttachmentPayload] {
        var payloads: [AttachmentPayload] = []
        for referenced in attachments {
            let stream = referenced.attachmentStream
            let name = referenced.reference.sourceFilename ?? "attachment"
            let byteCount = Int(stream.unencryptedByteCount)
            guard byteCount <= meshAttachmentByteLimit else {
                let megabytes = Double(byteCount) / (1024 * 1024)
                throw MeshError.attachmentNotSendable(
                    String(format: "%@ is %.1f MB; attachments over 4 MB cannot be sent over the mesh.", name, megabytes)
                )
            }
            let data = try stream.decryptedRawData()
            guard data.count <= meshAttachmentByteLimit else {
                throw MeshError.attachmentNotSendable("\(name) is larger than 4 MB and cannot be sent over the mesh.")
            }
            let mime = stream.mimeType
            let kind = MeshAttachmentKind.forOutgoing(
                mimeType: mime,
                isVoiceMessage: referenced.reference.renderingFlag == .voiceMessage,
            )
            payloads.append(AttachmentPayload(kind: kind, name: name, mime: mime, data: data))
        }
        return payloads
    }

    /// The bytes an `OutgoingCallMessage` would put on the wire, minus the
    /// `Content` wrapper: the `SSKProtoCallMessage` built by its own
    /// `contentBuilder` (offer / answer / iceUpdate / hangup / busy / opaque,
    /// destination device id, profile key when applicable).
    private static func serializedCallMessage(_ callMessage: OutgoingCallMessage, thread: TSThread, tx: DBReadTransaction) -> Data? {
        guard let contentBuilder = callMessage.contentBuilder(thread: thread, transaction: tx) else {
            return nil
        }
        do {
            guard let proto = try contentBuilder.build().callMessage else {
                return nil
            }
            return try proto.serializedData()
        } catch {
            Logger.warn("Could not serialize call message for the mesh: \(error)")
            return nil
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
    /// outgoing message delivered to that recipient. A message with text and
    /// attachments has several remembered bundles; the first ack marks it
    /// delivered and the later ones are no-ops.
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
