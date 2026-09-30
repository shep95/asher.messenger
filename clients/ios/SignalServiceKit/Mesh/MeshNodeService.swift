//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Owns the meshlink node for the process: builds it (external crypto, state
//! under Application Support), runs the event loop on a background thread,
//! dispatches events into the app, and manages the radio links.
//!
//! Gate: `FeatureFlags.meshTransport` (build flag) AND the user's toggle
//! (`MeshNodeService.isEnabled`, persisted). Runs while the app is in the
//! foreground and keeps its BLE links alive in the background under the
//! `bluetooth-central` / `bluetooth-peripheral` background modes.
//!
//! v3 (MESH_CONTRACT_V3): LAN link over Wi-Fi (`LanLink`), attachments and
//! call signalling over the mesh, nearby discovery, encrypted backup and the
//! loopback self-test. Hook points into the app:
//! * outgoing: `MeshOutbox.route` (texts, attachments, `OutgoingCallMessage`),
//! * incoming text: `handleMessage` -> `TSIncomingMessage`,
//! * incoming attachment (tag 10): `handleAttachment` -> `AttachmentManager.createAttachmentStream`,
//! * incoming call signal (tag 11): `handleCallSignal` -> `MessageReceiver.handleMeshCallMessage`.

import Foundation
public import LibSignalClient

// MARK: - Status

/// What the scene indicator needs: who is next to us right now.
public struct MeshStatus: Equatable {
    /// link id -> neighbour fingerprint (unauthenticated; informational).
    public var neighbours: [UInt64: Data] = [:]
    /// Links currently attached (with or without an identified neighbour).
    public var attachedLinks: Int = 0
    /// Whether the node is running at all.
    public var isRunning: Bool = false

    public init() {}

    public var hasNeighbour: Bool { !neighbours.isEmpty }

    public func isDirectNeighbour(_ fingerprint: Data) -> Bool {
        neighbours.values.contains(fingerprint)
    }
}

extension Notification.Name {
    /// Posted (on any thread) whenever `MeshNodeService.shared.status` or the
    /// outbox's set of unacknowledged bundles changes.
    public static let meshStatusDidChange = Notification.Name("MeshStatusDidChangeNotification")
}

// MARK: - Links

/// A byte pipe attached to the node. Concrete links: `BleLink`, `RNodeBleLink`, `LanLink`.
public protocol MeshLink: AnyObject {
    var name: String { get }
    func start(node: MeshNode)
    func stop()
}

// MARK: - Service

public final class MeshNodeService {
    public static let shared = MeshNodeService()

    private let kvStore = KeyValueStore(collection: "MeshNodeService")
    private enum Keys {
        static let enabled = "enabled"
        static let rnodeEnabled = "rnodeEnabled"
        static let lanEnabled = "lanEnabled"
    }

    private let lock = NSLock()
    private var node: MeshNode?
    private var eventThread: Thread?
    private var stopRequested = false
    private var links: [MeshLink] = []
    private var currentStatus = MeshStatus()

    /// Anti-entropy interval handed to `MeshNode_New` (seconds; 0 = crate default).
    public var antiEntropySecs: UInt32 = 15

    private init() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationDidEnterBackground),
            name: .OWSApplicationDidEnterBackground,
            object: nil,
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationWillEnterForeground),
            name: .OWSApplicationWillEnterForeground,
            object: nil,
        )
    }

    // MARK: - Preferences

    /// The user's toggle (settings screen). Off by default.
    public var isEnabled: Bool {
        SSKEnvironment.shared.databaseStorageRef.read { tx in
            kvStore.getBool(Keys.enabled, defaultValue: false, transaction: tx)
        }
    }

    public func setEnabled(_ enabled: Bool) {
        SSKEnvironment.shared.databaseStorageRef.write { tx in
            kvStore.setBool(enabled, key: Keys.enabled, transaction: tx)
        }
        if enabled {
            startIfEnabled()
        } else {
            stop()
        }
    }

    /// Whether to also look for an RNode LoRa board over BLE. Off by default.
    public var isRNodeEnabled: Bool {
        SSKEnvironment.shared.databaseStorageRef.read { tx in
            kvStore.getBool(Keys.rnodeEnabled, defaultValue: false, transaction: tx)
        }
    }

    public func setRNodeEnabled(_ enabled: Bool) {
        SSKEnvironment.shared.databaseStorageRef.write { tx in
            kvStore.setBool(enabled, key: Keys.rnodeEnabled, transaction: tx)
        }
        if isRunning {
            stop()
            startIfEnabled()
        }
    }

    /// Whether to mesh over the local network (Bonjour + TCP, `LanLink`).
    /// On by default: it is the zero-hardware path and the end-to-end test path.
    public var isLanEnabled: Bool {
        SSKEnvironment.shared.databaseStorageRef.read { tx in
            kvStore.getBool(Keys.lanEnabled, defaultValue: true, transaction: tx)
        }
    }

    public func setLanEnabled(_ enabled: Bool) {
        SSKEnvironment.shared.databaseStorageRef.write { tx in
            kvStore.setBool(enabled, key: Keys.lanEnabled, transaction: tx)
        }
        if isRunning {
            stop()
            startIfEnabled()
        }
    }

    // MARK: - Lifecycle

    public var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return node != nil
    }

    /// The node while running; nil otherwise. Callers must tolerate nil.
    public var nodeIfRunning: MeshNode? {
        lock.lock()
        defer { lock.unlock() }
        return node
    }

    public var status: MeshStatus {
        lock.lock()
        defer { lock.unlock() }
        return currentStatus
    }

    /// Called at app-ready (AppEnvironment) and when the toggle turns on.
    public func startIfEnabled() {
        guard FeatureFlags.meshTransport, isEnabled else {
            return
        }
        do {
            try start()
        } catch {
            Logger.error("Mesh node failed to start: \(error)")
        }
    }

    public func start() throws {
        guard FeatureFlags.meshTransport else {
            throw MeshError.notRunning
        }
        lock.lock()
        if node != nil {
            lock.unlock()
            return
        }
        lock.unlock()

        let databaseStorage = SSKEnvironment.shared.databaseStorageRef
        let identity: MeshIdentity = try databaseStorage.write { tx in
            try MeshIdentityManager.shared.loadOrCreate(tx: tx)
        }
        let newNode = try MeshNode(
            identity: identity,
            statePath: try Self.statePath(),
            externalCrypto: true,
            antiEntropySecs: antiEntropySecs,
        )

        // Tell the node about every stored contact so prepare_text works,
        // and restore the outbox's bundle id -> message map.
        databaseStorage.read { tx in
            for record in MeshContactStore.shared.all(tx: tx) {
                do {
                    _ = try newNode.addContact(cardBytes: record.card)
                } catch {
                    Logger.warn("Could not re-add mesh contact \(record.fingerprint.meshHex): \(error)")
                }
            }
            MeshOutbox.shared.loadPending(tx: tx)
        }

        lock.lock()
        node = newNode
        stopRequested = false
        currentStatus = MeshStatus()
        currentStatus.isRunning = true
        lock.unlock()

        let thread = Thread { [weak self] in
            self?.runEventLoop(node: newNode)
        }
        thread.name = "meshlink-events"
        thread.qualityOfService = .utility
        lock.lock()
        eventThread = thread
        lock.unlock()
        thread.start()

        startLinks(node: newNode)
        Logger.info("Mesh node started; fingerprint \((try? newNode.fingerprint().meshHex) ?? "?")")
        publishStatusChanged()
    }

    public func stop() {
        lock.lock()
        guard let runningNode = node else {
            lock.unlock()
            return
        }
        stopRequested = true
        let runningLinks = links
        links = []
        node = nil
        eventThread = nil
        currentStatus = MeshStatus()
        lock.unlock()

        for link in runningLinks {
            link.stop()
        }
        do {
            try runningNode.flush()
        } catch {
            Logger.warn("Mesh flush on stop failed: \(error)")
        }
        // The event thread notices `node == nil` within one poll (1 s) and
        // exits; the handle is destroyed when the last reference drops.
        Logger.info("Mesh node stopped")
        publishStatusChanged()
    }

    @objc
    private func applicationDidEnterBackground() {
        // BLE keeps running under the bluetooth background modes; the event
        // loop keeps draining. Persist so a suspension loses nothing.
        if let node = nodeIfRunning {
            do {
                try node.flush()
            } catch {
                Logger.warn("Mesh flush on background failed: \(error)")
            }
        }
    }

    @objc
    private func applicationWillEnterForeground() {
        // If iOS killed us while backgrounded, app-ready restarts the node.
        // Otherwise nothing to do; re-announce so the header refreshes.
        publishStatusChanged()
    }

    // MARK: - State directory

    /// `<Application Support>/meshlink/`, excluded from backups (it holds
    /// other people's carried ciphertext and our carry-store bookkeeping).
    private static func statePath() throws -> String {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true,
        )
        var directory = base.appendingPathComponent("meshlink", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? directory.setResourceValues(values)
        return directory.path
    }

    // MARK: - Links

    private func startLinks(node: MeshNode) {
        var newLinks: [MeshLink] = [BleLink()]
        if isLanEnabled {
            newLinks.append(LanLink())
        }
        if isRNodeEnabled {
            newLinks.append(RNodeBleLink(radioConfig: .euLongRange, configureRadio: false))
        }
        lock.lock()
        links = newLinks
        lock.unlock()
        for link in newLinks {
            link.start(node: node)
        }
    }

    /// Links call this when they attach/detach so `attachedLinks` stays current.
    func linkCountDidChange(delta: Int) {
        lock.lock()
        currentStatus.attachedLinks = max(0, currentStatus.attachedLinks + delta)
        lock.unlock()
        publishStatusChanged()
    }

    // MARK: - Contacts

    /// Adds a scanned card: node + store + recipient/thread.
    public func addContact(card: MeshContactCard) throws {
        let cardBytes = try card.encode()
        if let node = nodeIfRunning {
            _ = try node.addContact(cardBytes: cardBytes)
        }
        try SSKEnvironment.shared.databaseStorageRef.write { tx in
            try MeshContactStore.shared.upsert(card: card, tx: tx)
        }
        publishStatusChanged()
    }

    /// Diagnostics for the settings screen.
    public func stats() -> MeshStats? {
        guard let node = nodeIfRunning else {
            return nil
        }
        return try? node.stats()
    }

    // MARK: - Nearby (v3)

    /// Cards seen on the mesh in the last 24 h (`MeshNode_Nearby`), most
    /// recent first; empty when the node is not running.
    public func nearby() -> [MeshNearbyPeer] {
        guard let node = nodeIfRunning else {
            return []
        }
        do {
            return try node.nearby()
        } catch {
            Logger.warn("nearby() failed: \(error)")
            return []
        }
    }

    /// Adds a nearby peer as a contact. The nearby list only carries
    /// fingerprint + name; the card comes from the node, which keeps every
    /// card it learned (`MeshNode_Contact`). Throws `notMeshRecipient` when
    /// the node no longer holds that card (it expired; wait for the next beacon).
    public func addNearbyContact(fingerprint: Data) throws {
        guard let node = nodeIfRunning else {
            throw MeshError.notRunning
        }
        guard let cardBytes = try node.contactCardBytes(fingerprint: fingerprint) else {
            throw MeshError.notMeshRecipient
        }
        try addContact(card: try MeshContactCard(bytes: cardBytes))
    }

    // MARK: - Self-test (v3)

    /// `MeshNode_SelfTest`: blocks up to `timeoutMs`; call from a background queue.
    public func runSelfTest(timeoutMs: UInt32 = 15_000) throws -> String {
        guard let node = nodeIfRunning else {
            throw MeshError.notRunning
        }
        return try node.selfTest(timeoutMs: timeoutMs)
    }

    // MARK: - Encrypted backup (v3)

    /// `MeshNode_ExportBackup`: the ".asherbackup" blob for the share sheet.
    public func exportBackup(passphrase: String) throws -> Data {
        guard let node = nodeIfRunning else {
            throw MeshError.notRunning
        }
        try node.flush()
        return try node.exportBackup(passphrase: passphrase)
    }

    /// Restores a backup. With a running node (an identity already exists)
    /// this is `MeshNode_ImportBackup`, which merges contacts, groups and
    /// carried bundles and requires the backup's identity to be ours. Without
    /// an identity yet, the identity is recovered first
    /// (`MeshIdentity_FromBackup`), installed, the node started, and the rest
    /// imported. Returns a one-line summary for the UI.
    public func restoreBackup(passphrase: String, blob: Data) throws -> String {
        let databaseStorage = SSKEnvironment.shared.databaseStorageRef
        let hadIdentity = databaseStorage.read { tx in
            MeshIdentityManager.shared.fingerprint(tx: tx) != nil
        }
        if !hadIdentity {
            if isRunning {
                stop()
            }
            let identity = try MeshIdentity.fromBackup(passphrase: passphrase, blob: blob)
            try databaseStorage.write { tx in
                try MeshIdentityManager.shared.install(identity, tx: tx)
            }
        }
        if !isRunning {
            if isEnabled {
                try start()
            } else {
                setEnabled(true)
            }
        }
        guard let node = nodeIfRunning else {
            throw MeshError.notRunning
        }
        try node.importBackup(passphrase: passphrase, blob: blob)

        // Mirror the node's contacts into the app's table so threads exist.
        var restoredContacts = 0
        let fingerprints = try node.contacts()
        try databaseStorage.write { tx in
            for fingerprint in fingerprints {
                guard let cardBytes = try node.contactCardBytes(fingerprint: fingerprint) else {
                    continue
                }
                try MeshContactStore.shared.upsert(card: try MeshContactCard(bytes: cardBytes), tx: tx)
                restoredContacts += 1
            }
        }
        try node.flush()
        publishStatusChanged()
        return "Restored \(restoredContacts) mesh contact\(restoredContacts == 1 ? "" : "s")" + (hadIdentity ? "." : " and the mesh identity.")
    }

    // MARK: - Event loop

    private func runEventLoop(node: MeshNode) {
        while true {
            lock.lock()
            let shouldStop = stopRequested || self.node == nil
            lock.unlock()
            if shouldStop {
                return
            }
            let bytes: Data
            do {
                bytes = try node.nextEvent(timeoutMs: 1000)
            } catch {
                Logger.error("next_event failed: \(error)")
                Thread.sleep(forTimeInterval: 1)
                continue
            }
            if bytes.isEmpty {
                continue
            }
            do {
                if let event = try MeshEvent.decode(bytes) {
                    handle(event, node: node)
                }
            } catch {
                Logger.error("Bad mesh event: \(error)")
            }
        }
    }

    private func handle(_ event: MeshEvent, node: MeshNode) {
        switch event {
        case .ciphertext(let from, let bundleId, _, let messageType, let ciphertext):
            handleCiphertext(from: from, bundleId: bundleId, messageType: messageType, ciphertext: ciphertext, node: node)

        case .message(let from, let bundleId, let plaintext, let knownSender):
            handleMessage(from: from, bundleId: bundleId, plaintext: plaintext, knownSender: knownSender)

        case .groupMessage(let group, let from, let bundleId, let plaintext):
            // TODO(mesh-groups): map a mesh group (16-byte id, pairwise fan-out
            // in meshlink) onto a local-only TSGroupThread keyed by the group
            // id, insert the plaintext as an incoming message from `from`, and
            // send replies with MeshNode_PrepareGroupText per member. No UI yet.
            Logger.info("Mesh group message in group \(group.meshHex) from \(from.meshHex) (bundle \(bundleId.meshHex), \(plaintext.count) bytes) ignored: mesh groups have no UI")

        case .groupInvite(let group, let from):
            // TODO(mesh-groups): create the local-only group thread named after
            // MeshNode_Group(group)'s encoded MeshGroup and add its members as
            // mesh recipients.
            Logger.info("Mesh group invite to \(group.meshHex) from \(from.meshHex) ignored: mesh groups have no UI")

        case .contact(let fingerprint):
            handleContact(fingerprint: fingerprint, node: node)

        case .delivered(let bundleId):
            MeshOutbox.shared.markDelivered(bundleId: bundleId)

        case .neighbour(let link, let fingerprint):
            lock.lock()
            currentStatus.neighbours[link] = fingerprint
            lock.unlock()
            Logger.info("Mesh neighbour \(fingerprint.meshHex) on link \(link)")
            publishStatusChanged()

        case .linkClosed(let link):
            lock.lock()
            currentStatus.neighbours.removeValue(forKey: link)
            lock.unlock()
            Logger.info("Mesh link \(link) closed")
            publishStatusChanged()

        case .attachmentProgress(let from, let transfer, let received, let total):
            // No per-transfer UI yet: the message appears when the transfer completes.
            Logger.info("Mesh attachment \(transfer.meshHex) from \(from.meshHex): \(received)/\(total) chunks")

        case .attachment(let from, let transfer, let kind, let name, let mime, let data):
            handleAttachment(from: from, transfer: transfer, kind: kind, name: name, mime: mime, data: data)

        case .callSignal(let from, let bundleId, let data):
            handleCallSignal(from: from, bundleId: bundleId, data: data)
        }
    }

    private func handleCiphertext(from: Data, bundleId: Data, messageType: UInt8, ciphertext: Data, node: MeshNode) {
        let databaseStorage = SSKEnvironment.shared.databaseStorageRef
        let plaintext: Data? = databaseStorage.write { tx in
            do {
                return try MeshCrypto.decrypt(messageType: messageType, ciphertext: ciphertext, from: from, tx: tx)
            } catch MeshError.noSession {
                return nil
            } catch {
                Logger.warn("Mesh decrypt from \(from.meshHex) failed: \(error)")
                return nil
            }
        }
        do {
            if let plaintext {
                try node.deliverPlaintext(bundleId: bundleId, plaintext: plaintext)
            } else {
                // No session yet (or undecryptable): keep the bundle; meshlink
                // re-announces it when a session with `from` appears.
                try node.defer(bundleId: bundleId)
            }
        } catch {
            Logger.error("deliver/defer for bundle \(bundleId.meshHex) failed: \(error)")
        }
    }

    /// Inserts the decrypted text as an incoming message in the mesh contact's
    /// thread, the same objects the network path builds (TSIncomingMessage).
    private func handleMessage(from: Data, bundleId: Data, plaintext: Data, knownSender: Bool) {
        guard let aci = MeshContactStore.aci(forFingerprint: from) else {
            return
        }
        let text = String(decoding: plaintext, as: UTF8.self)
        guard !text.isEmpty else {
            return
        }
        SSKEnvironment.shared.databaseStorageRef.write { tx in
            let address = SignalServiceAddress(aci)
            if !knownSender {
                // Beacon-less sender: still make the thread so the text lands
                // somewhere; the name shows as the fingerprint prefix until a
                // card arrives.
                let recipient = DependenciesBridge.shared.recipientFetcher.fetchOrCreate(serviceId: aci, tx: tx)
                if DependenciesBridge.shared.nicknameManager.fetchNickname(for: recipient, tx: tx) == nil {
                    DependenciesBridge.shared.nicknameManager.createOrUpdate(
                        nicknameRecord: NicknameRecord(recipient: recipient, givenName: "Mesh \(from.prefix(4).meshHex)", familyName: nil, note: "Mesh contact"),
                        updateStorageServiceFor: nil,
                        tx: tx,
                    )
                }
            }
            let thread = TSContactThread.getOrCreateThread(withContactAddress: address, transaction: tx)
            let now = Date.ows_millisecondTimestamp()
            let validatedBody = DependenciesBridge.shared.attachmentContentValidator.truncatedMessageBodyForInlining(
                MessageBody(text: text, ranges: .empty),
                tx: tx,
            )
            let builder = TSIncomingMessageBuilder.withDefaultValues(
                thread: thread,
                timestamp: now,
                receivedAtTimestamp: now,
                authorAci: aci,
                messageBody: validatedBody,
                serverTimestamp: now,
                serverDeliveryTimestamp: now,
                serverGuid: nil,
                wasReceivedByUD: false,
            )
            let message = builder.build()
            message.insertOrReplacePlaceholder(from: address, transaction: tx)
            SSKEnvironment.shared.notificationPresenterRef.notifyUser(
                forIncomingMessage: message,
                thread: thread,
                transaction: tx,
            )
            Logger.info("Inserted mesh message \(bundleId.meshHex) from \(from.meshHex)")
        }
    }

    /// `Event::Attachment` (tag 10): a complete, hash-verified attachment from
    /// a mesh contact. Goes through the app's normal local-attachment path:
    /// `AttachmentContentValidator.validateDataContents` (content type, blur
    /// hash, waveform, encryption to the attachment store) and then
    /// `AttachmentManager.createAttachmentStream` owned by a new
    /// `TSIncomingMessage` in the contact's thread, exactly what an incoming
    /// server message with a downloaded body attachment ends up as.
    private func handleAttachment(from: Data, transfer: Data, kind: UInt8, name: String, mime: String, data: Data) {
        guard let aci = MeshContactStore.aci(forFingerprint: from) else {
            return
        }
        let renderingFlag: AttachmentReference.RenderingFlag =
            kind == MeshAttachmentKind.voiceNote.rawValue ? .voiceMessage : .default
        let mimeType = mime.isEmpty ? "application/octet-stream" : mime
        let sourceFilename: String? = name.isEmpty ? nil : name
        Task {
            do {
                let pending = try await DependenciesBridge.shared.attachmentContentValidator.validateDataContents(
                    data,
                    mimeType: mimeType,
                    renderingFlag: renderingFlag,
                    sourceFilename: sourceFilename,
                )
                try await SSKEnvironment.shared.databaseStorageRef.awaitableWrite { tx in
                    try self.insertIncomingAttachmentMessage(from: aci, pending: pending, tx: tx)
                }
                Logger.info("Inserted mesh attachment \(transfer.meshHex) (\(mimeType), \(data.count) bytes) from \(from.meshHex)")
            } catch {
                Logger.error("Could not store mesh attachment \(transfer.meshHex) from \(from.meshHex): \(error)")
            }
        }
    }

    private func insertIncomingAttachmentMessage(from aci: Aci, pending: PendingAttachment, tx: DBWriteTransaction) throws {
        let address = SignalServiceAddress(aci)
        let thread = TSContactThread.getOrCreateThread(withContactAddress: address, transaction: tx)
        let now = Date.ows_millisecondTimestamp()
        let builder = TSIncomingMessageBuilder.withDefaultValues(
            thread: thread,
            timestamp: now,
            receivedAtTimestamp: now,
            authorAci: aci,
            messageBody: nil,
            serverTimestamp: now,
            serverDeliveryTimestamp: now,
            serverGuid: nil,
            wasReceivedByUD: false,
        )
        let message = builder.build()
        message.insertOrReplacePlaceholder(from: address, transaction: tx)
        guard let messageRowId = message.sqliteRowId, let threadRowId = thread.sqliteRowId else {
            throw OWSAssertionError("Mesh attachment message was not inserted")
        }
        _ = try DependenciesBridge.shared.attachmentManager.createAttachmentStream(
            from: OwnedAttachmentDataSource(
                dataSource: .pendingAttachment(pending),
                owner: .messageBodyAttachment(.init(
                    messageRowId: messageRowId,
                    receivedAtTimestamp: now,
                    threadRowId: threadRowId,
                    isViewOnce: false,
                    isPastEditRevision: false,
                    orderInMessage: 0,
                )),
            ),
            tx: tx,
        )
        SSKEnvironment.shared.notificationPresenterRef.notifyUser(
            forIncomingMessage: message,
            thread: thread,
            transaction: tx,
        )
    }

    /// `Event::CallSignal` (tag 11): the bytes are a serialized
    /// `SSKProtoCallMessage` (what `MeshOutbox` sends for an
    /// `OutgoingCallMessage`). They go to `MessageReceiver.handleMeshCallMessage`,
    /// which dispatches to the app's `CallMessageHandler` like a server-delivered
    /// call message from that ACI (device id from the contact's card, else 1).
    private func handleCallSignal(from: Data, bundleId: Data, data: Data) {
        guard let aci = MeshContactStore.aci(forFingerprint: from) else {
            return
        }
        let callMessage: SSKProtoCallMessage
        do {
            callMessage = try SSKProtoCallMessage(serializedData: data)
        } catch {
            Logger.warn("Mesh call signal \(bundleId.meshHex) from \(from.meshHex) is not a call message: \(error)")
            return
        }
        SSKEnvironment.shared.databaseStorageRef.write { tx in
            var rawDeviceId: UInt32 = 1
            if let record = MeshContactStore.shared.fetch(fingerprint: from, tx: tx),
               let card = try? MeshContactCard(bytes: record.card),
               let cardDeviceId = try? card.deviceId() {
                rawDeviceId = cardDeviceId
            }
            guard let deviceId = DeviceId(validating: rawDeviceId) else {
                Logger.warn("Mesh call signal from \(from.meshHex) has an invalid device id \(rawDeviceId)")
                return
            }
            Logger.info("Mesh call signal \(bundleId.meshHex) from \(from.meshHex)")
            SSKEnvironment.shared.messageReceiverRef.handleMeshCallMessage(
                callMessage,
                from: aci,
                senderDeviceId: deviceId,
                tx: tx,
            )
        }
    }

    private func handleContact(fingerprint: Data, node: MeshNode) {
        do {
            guard let cardBytes = try node.contactCardBytes(fingerprint: fingerprint) else {
                return
            }
            let card = try MeshContactCard(bytes: cardBytes)
            try SSKEnvironment.shared.databaseStorageRef.write { tx in
                try MeshContactStore.shared.upsert(card: card, tx: tx)
            }
            Logger.info("Learned mesh contact \(fingerprint.meshHex)")
            publishStatusChanged()
        } catch {
            Logger.warn("Could not store learned mesh contact \(fingerprint.meshHex): \(error)")
        }
    }

    // MARK: - Status publishing

    func publishStatusChanged() {
        NotificationCenter.default.post(name: .meshStatusDidChange, object: nil)
    }

    /// The scene-indicator state for a thread, or nil to fall back to
    /// connectivity (Orbit / Out of range). Cheap; called on the main thread.
    ///
    /// - `.mesh(hops: 1)` when the contact is a direct neighbour,
    /// - `.mesh(hops: 2)` when some neighbour is present (relayed; meshlink
    ///   does not report per-destination hop counts, so 2 means "via relay"),
    /// - `.carrying` when nothing is in range but a bundle for this contact
    ///   is still unacknowledged.
    public enum TransportState: Equatable {
        case mesh(hops: Int)
        case carrying
    }

    public func transportState(forContactAci aci: Aci) -> TransportState? {
        guard FeatureFlags.meshTransport, MeshContactStore.shared.isMeshRecipient(aci: aci) else {
            return nil
        }
        let snapshot = status
        guard snapshot.isRunning else {
            return nil
        }
        let fingerprint = MeshContactStore.fingerprint(forAci: aci)
        if snapshot.isDirectNeighbour(fingerprint) {
            return .mesh(hops: 1)
        }
        if snapshot.hasNeighbour {
            return .mesh(hops: 2)
        }
        if MeshOutbox.shared.hasUnacknowledged(for: aci) {
            return .carrying
        }
        return nil
    }
}
