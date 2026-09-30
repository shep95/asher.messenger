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

/// A byte pipe attached to the node. Concrete links: `BleLink`, `RNodeBleLink`.
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
