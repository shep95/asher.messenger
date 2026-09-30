//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Mesh contacts: the signed cards we hold, and how they appear in the app.
//!
//! Convention (documented here, used by MeshCrypto/MeshOutbox/MeshNodeService
//! and the conversation header): a mesh contact is surfaced as an ordinary
//! `SignalRecipient` + `TSContactThread` whose ACI is the contact's 16-byte
//! meshlink fingerprint rendered as a UUID string (byte-for-byte, no version
//! or variant bits adjusted). The card's name becomes the recipient's nickname
//! so the normal thread UI shows it. Nothing about such a recipient exists on
//! the server; `MeshContactStore.isMeshRecipient` is how other code tells them
//! apart. Sessions with a mesh contact live in the app's normal ACI protocol
//! stores under `ProtocolAddress(thatAci, deviceId: card.deviceId)`.

import Foundation
import GRDB
public import LibSignalClient

/// One row of the `MeshContact` table.
public struct MeshContactRecord: Codable, FetchableRecord, PersistableRecord {
    public static let databaseTableName: String = "MeshContact"

    public var id: Int64?
    /// 16-byte meshlink fingerprint (SHA-256 of the identity key, truncated).
    public let fingerprint: Data
    /// The encoded, signed contact card (`MeshContactCard.encode()`).
    public var card: Data
    /// The display name carried by the card.
    public var name: String
    /// Milliseconds since the epoch when the card was first added.
    public let addedAt: UInt64

    enum CodingKeys: String, CodingKey {
        case id
        case fingerprint
        case card
        case name
        case addedAt
    }

    enum Columns {
        static let id = Column(CodingKeys.id.rawValue)
        static let fingerprint = Column(CodingKeys.fingerprint.rawValue)
        static let card = Column(CodingKeys.card.rawValue)
        static let name = Column(CodingKeys.name.rawValue)
        static let addedAt = Column(CodingKeys.addedAt.rawValue)
    }

    public mutating func didInsert(with rowID: Int64, for column: String?) {
        id = rowID
    }

    /// The recipient identifier this contact is surfaced under (see file header).
    public var aci: Aci? {
        MeshContactStore.aci(forFingerprint: fingerprint)
    }
}

public final class MeshContactStore {
    public static let shared = MeshContactStore()

    /// Fingerprint-derived ACIs of every stored contact, for cheap
    /// "is this a mesh recipient" checks off the main thread.
    private let knownAcis = AtomicValue<Set<Aci>?>(nil, lock: .init())

    private init() {}

    // MARK: - Schema (called from GRDBSchemaMigrator)

    public static func createTable(tx: DBWriteTransaction) throws {
        try tx.database.create(table: MeshContactRecord.databaseTableName) { table in
            table.autoIncrementedPrimaryKey(MeshContactRecord.CodingKeys.id.rawValue)
            table.column(MeshContactRecord.CodingKeys.fingerprint.rawValue, .blob).notNull().unique()
            table.column(MeshContactRecord.CodingKeys.card.rawValue, .blob).notNull()
            table.column(MeshContactRecord.CodingKeys.name.rawValue, .text).notNull()
            table.column(MeshContactRecord.CodingKeys.addedAt.rawValue, .integer).notNull()
        }
    }

    // MARK: - Fingerprint <-> recipient convention

    /// 16 fingerprint bytes rendered as a UUID (no bits adjusted) and wrapped as an ACI.
    public static func aci(forFingerprint fingerprint: Data) -> Aci? {
        guard fingerprint.count == 16 else {
            return nil
        }
        let bytes = [UInt8](fingerprint)
        let uuid = UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
        return Aci(fromUUID: uuid)
    }

    /// The inverse of `aci(forFingerprint:)`.
    public static func fingerprint(forAci aci: Aci) -> Data {
        let u = aci.rawUUID.uuid
        return Data([
            u.0, u.1, u.2, u.3, u.4, u.5, u.6, u.7,
            u.8, u.9, u.10, u.11, u.12, u.13, u.14, u.15,
        ])
    }

    /// The `SignalServiceAddress` of a mesh contact's thread.
    public static func address(forFingerprint fingerprint: Data) -> SignalServiceAddress? {
        aci(forFingerprint: fingerprint).map { SignalServiceAddress($0) }
    }

    // MARK: - Reads

    public func fetch(fingerprint: Data, tx: DBReadTransaction) -> MeshContactRecord? {
        failIfThrows {
            try MeshContactRecord
                .filter(MeshContactRecord.Columns.fingerprint == fingerprint)
                .fetchOne(tx.database)
        }
    }

    public func fetch(aci: Aci, tx: DBReadTransaction) -> MeshContactRecord? {
        fetch(fingerprint: Self.fingerprint(forAci: aci), tx: tx)
    }

    public func all(tx: DBReadTransaction) -> [MeshContactRecord] {
        failIfThrows {
            try MeshContactRecord
                .order(MeshContactRecord.Columns.addedAt.asc)
                .fetchAll(tx.database)
        }
    }

    /// Whether `aci` is a mesh contact. Uses the in-memory set when loaded;
    /// the first call after launch populates it with a read transaction.
    public func isMeshRecipient(aci: Aci) -> Bool {
        if let known = knownAcis.get() {
            return known.contains(aci)
        }
        let loaded: Set<Aci> = SSKEnvironment.shared.databaseStorageRef.read { tx in
            Set(all(tx: tx).compactMap(\.aci))
        }
        knownAcis.set(loaded)
        return loaded.contains(aci)
    }

    public func isMeshRecipient(aci: Aci, tx: DBReadTransaction) -> Bool {
        if let known = knownAcis.get() {
            return known.contains(aci)
        }
        return fetch(aci: aci, tx: tx) != nil
    }

    /// Whether the thread belongs to a mesh contact.
    public func isMeshThread(_ thread: TSThread) -> Bool {
        guard let contactThread = thread as? TSContactThread, let aci = contactThread.contactAddress.aci else {
            return false
        }
        return isMeshRecipient(aci: aci)
    }

    // MARK: - Writes

    /// Stores (or refreshes) a card and makes sure the recipient, its
    /// nickname and its contact thread exist so the conversation can be opened.
    @discardableResult
    public func upsert(card: MeshContactCard, tx: DBWriteTransaction) throws -> MeshContactRecord {
        let fingerprint = try card.fingerprint()
        let cardBytes = try card.encode()
        let name = try card.name()
        guard let aci = Self.aci(forFingerprint: fingerprint) else {
            throw MeshError.invalidLength("fingerprint")
        }

        var record: MeshContactRecord
        if var existing = fetch(fingerprint: fingerprint, tx: tx) {
            existing.card = cardBytes
            existing.name = name
            try existing.update(tx.database)
            record = existing
        } else {
            record = MeshContactRecord(
                id: nil,
                fingerprint: fingerprint,
                card: cardBytes,
                name: name,
                addedAt: Date.ows_millisecondTimestamp(),
            )
            try record.insert(tx.database)
        }

        // Surface the contact through the normal recipient/thread machinery.
        var recipient = DependenciesBridge.shared.recipientFetcher.fetchOrCreate(serviceId: aci, tx: tx)
        // Adding a card is an explicit act of trust: whitelist the recipient
        // so calls from it are not rejected as message requests
        // (CallOfferHandler.allowsInboundCalls). `.metadataUpdate` keeps the
        // storage service out of it; nothing about a mesh contact lives on a server.
        SSKEnvironment.shared.profileManagerRef.addRecipientToProfileWhitelist(
            &recipient,
            userProfileWriter: .metadataUpdate,
            tx: tx,
        )
        let displayName = name.isEmpty ? "Mesh \(fingerprint.prefix(4).meshHex)" : name
        DependenciesBridge.shared.nicknameManager.createOrUpdate(
            nicknameRecord: NicknameRecord(recipient: recipient, givenName: displayName, familyName: nil, note: "Mesh contact"),
            updateStorageServiceFor: nil,
            tx: tx,
        )
        _ = TSContactThread.getOrCreateThread(withContactAddress: SignalServiceAddress(aci), transaction: tx)

        knownAcis.update { known in
            known?.insert(aci)
        }
        return record
    }

    /// Removes the stored card. The recipient/thread are left alone (the
    /// conversation history stays; sends to it will fail until re-added).
    public func remove(fingerprint: Data, tx: DBWriteTransaction) {
        failIfThrows {
            _ = try MeshContactRecord
                .filter(MeshContactRecord.Columns.fingerprint == fingerprint)
                .deleteAll(tx.database)
        }
        if let aci = Self.aci(forFingerprint: fingerprint) {
            knownAcis.update { known in
                known?.remove(aci)
            }
        }
    }

    /// Drops the in-memory cache (tests / after bulk changes).
    func invalidateCache() {
        knownAcis.set(nil)
    }
}
