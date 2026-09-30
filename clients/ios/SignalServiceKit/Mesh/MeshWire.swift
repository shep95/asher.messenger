//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Decoders for the compact byte strings the meshlink bridge returns
//! (`rust/bridge/shared/types/src/mesh.rs`: `encode_event`, `encode_prepared`,
//! `encode_stats`). Fixed fields are big-endian; variable fields are u16
//! big-endian length-prefixed.

import Foundation

/// A cursor over a bridge byte string.
struct MeshWireReader {
    private let data: Data
    private var offset: Int

    init(_ data: Data) {
        self.data = data
        self.offset = data.startIndex
    }

    var isAtEnd: Bool { offset >= data.endIndex }

    var remaining: Int { data.endIndex - offset }

    mutating func u8() throws -> UInt8 {
        guard remaining >= 1 else { throw MeshError.wire("truncated u8") }
        let value = data[offset]
        offset += 1
        return value
    }

    mutating func u16() throws -> UInt16 {
        guard remaining >= 2 else { throw MeshError.wire("truncated u16") }
        let value = UInt16(data[offset]) << 8 | UInt16(data[offset + 1])
        offset += 2
        return value
    }

    mutating func u32() throws -> UInt32 {
        guard remaining >= 4 else { throw MeshError.wire("truncated u32") }
        var value: UInt32 = 0
        for i in 0..<4 {
            value = value << 8 | UInt32(data[offset + i])
        }
        offset += 4
        return value
    }

    mutating func u64() throws -> UInt64 {
        guard remaining >= 8 else { throw MeshError.wire("truncated u64") }
        var value: UInt64 = 0
        for i in 0..<8 {
            value = value << 8 | UInt64(data[offset + i])
        }
        offset += 8
        return value
    }

    mutating func fixed(_ count: Int) throws -> Data {
        guard remaining >= count else { throw MeshError.wire("truncated fixed(\(count))") }
        let value = Data(data[offset..<(offset + count)])
        offset += count
        return value
    }

    /// A u16 length-prefixed byte string.
    mutating func bytes() throws -> Data {
        let length = Int(try u16())
        return try fixed(length)
    }

    /// A u32 length-prefixed byte string (attachment bodies, v3 contract).
    mutating func bytes32() throws -> Data {
        let length = Int(try u32())
        return try fixed(length)
    }

    /// A u16 length-prefixed UTF-8 string (lossy on bad bytes).
    mutating func string() throws -> String {
        String(decoding: try bytes(), as: UTF8.self)
    }
}

// MARK: - Events

/// One decoded `Event` from `MeshNode_NextEvent`. Tags from `event_tag` in the bridge.
public enum MeshEvent: Equatable {
    /// External-crypto mode: a message for us. Decrypt `ciphertext` with the
    /// Signal session for `from` and answer with `deliverPlaintext` or `defer`.
    case ciphertext(from: Data, bundleId: Data, ackCommit: Data, messageType: UInt8, ciphertext: Data)
    /// A one-to-one message for us was accepted (after `deliverPlaintext`).
    case message(from: Data, bundleId: Data, plaintext: Data, knownSender: Bool)
    /// A group message for us.
    case groupMessage(group: Data, from: Data, bundleId: Data, plaintext: Data)
    /// We were added to a group.
    case groupInvite(group: Data, from: Data)
    /// A contact card was learned (beacon, share, or explicit add).
    case contact(fingerprint: Data)
    /// A message we sent was acknowledged by its recipient.
    case delivered(bundleId: Data)
    /// A neighbour identified itself on a link (unauthenticated; informational).
    case neighbour(link: UInt64, fingerprint: Data)
    /// A link went away.
    case linkClosed(link: UInt64)
    /// v3: a chunk of an inbound attachment arrived (`received`/`total` are chunk counts).
    case attachmentProgress(from: Data, transfer: Data, received: UInt32, total: UInt32)
    /// v3: a complete, hash-verified attachment. `kind`: 1 file, 2 image, 3 voice note.
    case attachment(from: Data, transfer: Data, kind: UInt8, name: String, mime: String, data: Data)
    /// v3: opaque call-signalling bytes from a mesh contact (a serialized
    /// `SSKProtoCallMessage` on every Asher platform).
    case callSignal(from: Data, bundleId: Data, data: Data)

    public enum Tag: UInt8 {
        case ciphertext = 1
        case message = 2
        case groupMessage = 3
        case groupInvite = 4
        case contact = 5
        case delivered = 6
        case neighbour = 7
        case linkClosed = 8
        case attachmentProgress = 9
        case attachment = 10
        case callSignal = 11
    }

    /// Decodes one event; empty input (the poll timed out) returns nil.
    public static func decode(_ data: Data) throws -> MeshEvent? {
        if data.isEmpty {
            return nil
        }
        var reader = MeshWireReader(data)
        let rawTag = try reader.u8()
        guard let tag = Tag(rawValue: rawTag) else {
            throw MeshError.wire("unknown event tag \(rawTag)")
        }
        switch tag {
        case .ciphertext:
            let from = try reader.fixed(16)
            let bundleId = try reader.fixed(16)
            let ackCommit = try reader.fixed(16)
            let messageType = try reader.u8()
            let ciphertext = try reader.bytes()
            return .ciphertext(from: from, bundleId: bundleId, ackCommit: ackCommit, messageType: messageType, ciphertext: ciphertext)
        case .message:
            let from = try reader.fixed(16)
            let bundleId = try reader.fixed(16)
            let knownSender = try reader.u8() != 0
            let plaintext = try reader.bytes()
            return .message(from: from, bundleId: bundleId, plaintext: plaintext, knownSender: knownSender)
        case .groupMessage:
            let group = try reader.fixed(16)
            let from = try reader.fixed(16)
            let bundleId = try reader.fixed(16)
            let plaintext = try reader.bytes()
            return .groupMessage(group: group, from: from, bundleId: bundleId, plaintext: plaintext)
        case .groupInvite:
            let group = try reader.fixed(16)
            let from = try reader.fixed(16)
            return .groupInvite(group: group, from: from)
        case .contact:
            return .contact(fingerprint: try reader.fixed(16))
        case .delivered:
            return .delivered(bundleId: try reader.fixed(16))
        case .neighbour:
            let link = try reader.u64()
            let fingerprint = try reader.fixed(16)
            return .neighbour(link: link, fingerprint: fingerprint)
        case .linkClosed:
            return .linkClosed(link: try reader.u64())
        case .attachmentProgress:
            // `[from 16][transfer 16][received u32][total u32]`
            let from = try reader.fixed(16)
            let transfer = try reader.fixed(16)
            let received = try reader.u32()
            let total = try reader.u32()
            return .attachmentProgress(from: from, transfer: transfer, received: received, total: total)
        case .attachment:
            // `[from 16][transfer 16][kind u8][name u16-len][mime u16-len][data u32-len]`
            let from = try reader.fixed(16)
            let transfer = try reader.fixed(16)
            let kind = try reader.u8()
            let name = try reader.string()
            let mime = try reader.string()
            let data = try reader.bytes32()
            return .attachment(from: from, transfer: transfer, kind: kind, name: name, mime: mime, data: data)
        case .callSignal:
            // `[from 16][bundle 16][data u16-len]`
            let from = try reader.fixed(16)
            let bundleId = try reader.fixed(16)
            let data = try reader.bytes()
            return .callSignal(from: from, bundleId: bundleId, data: data)
        }
    }
}

// MARK: - Attachment kinds

/// `kind` byte of `MeshNode_PrepareAttachment` / `Event::Attachment`.
public enum MeshAttachmentKind: UInt8 {
    case file = 1
    case image = 2
    case voiceNote = 3

    /// The kind an outgoing attachment travels as: images by MIME type, audio
    /// flagged as a voice message as a voice note, everything else a file.
    public static func forOutgoing(mimeType: String, isVoiceMessage: Bool) -> MeshAttachmentKind {
        let lower = mimeType.lowercased()
        if lower.hasPrefix("image/") {
            return .image
        }
        if lower.hasPrefix("audio/"), isVoiceMessage {
            return .voiceNote
        }
        return .file
    }
}

// MARK: - Nearby

/// One entry of `MeshNode_Nearby`: a card seen on the mesh in the last 24 h.
public struct MeshNearbyPeer: Equatable {
    public let fingerprint: Data
    public let name: String
    /// Seconds since the epoch when the card was last seen.
    public let lastSeenSecs: UInt64
    /// True when the peer is a current neighbour (one hop away right now).
    public let isDirect: Bool

    /// `u16 count` then per entry `[fingerprint 16][name u16-len][lastSeenSecs u64][direct u8]`,
    /// most recent first.
    public static func decodeList(_ data: Data) throws -> [MeshNearbyPeer] {
        var reader = MeshWireReader(data)
        let count = Int(try reader.u16())
        var items: [MeshNearbyPeer] = []
        items.reserveCapacity(count)
        for _ in 0..<count {
            let fingerprint = try reader.fixed(16)
            let name = try reader.string()
            let lastSeen = try reader.u64()
            let direct = try reader.u8() != 0
            items.append(MeshNearbyPeer(fingerprint: fingerprint, name: name, lastSeenSecs: lastSeen, isDirect: direct))
        }
        return items
    }
}

// MARK: - Prepared plaintexts

/// One item of `encode_prepared`: a plaintext the app must encrypt for `to`
/// and hand back through `sendCiphertext` with the same `commit`.
public struct MeshPrepared: Equatable {
    public let to: Data
    public let commit: Data
    public let plaintext: Data

    /// `[count u16]` then per item `[to 16][commit 16][plaintext u16-len]`.
    public static func decodeList(_ data: Data) throws -> [MeshPrepared] {
        var reader = MeshWireReader(data)
        let count = Int(try reader.u16())
        var items: [MeshPrepared] = []
        items.reserveCapacity(count)
        for _ in 0..<count {
            let to = try reader.fixed(16)
            let commit = try reader.fixed(16)
            let plaintext = try reader.bytes()
            items.append(MeshPrepared(to: to, commit: commit, plaintext: plaintext))
        }
        return items
    }
}

// MARK: - Stats

/// `encode_stats`: big-endian u64 counters in the order of the Rust `Stats` fields.
public struct MeshStats: Equatable {
    public var framesIn: UInt64 = 0
    public var framesDroppedRate: UInt64 = 0
    public var framesDroppedInvalid: UInt64 = 0
    public var bundlesIn: UInt64 = 0
    public var bundlesDroppedInvalid: UInt64 = 0
    public var bundlesDroppedRate: UInt64 = 0
    public var bundlesDroppedQuota: UInt64 = 0
    public var bundlesForwarded: UInt64 = 0
    public var messagesDelivered: UInt64 = 0
    public var messagesUndecryptable: UInt64 = 0
    public var messagesDeferred: UInt64 = 0
    public var acksRejected: UInt64 = 0
    public var acksVerified: UInt64 = 0
    public var bytesOut: UInt64 = 0
    public var links: UInt64 = 0
    public var storeBundles: UInt64 = 0
    public var storeBytes: UInt64 = 0
    public var outstanding: UInt64 = 0

    public init() {}

    /// Tolerates a shorter or longer counter list so a bridge that adds a
    /// counter does not break older apps: missing trailing fields stay zero.
    public static func decode(_ data: Data) throws -> MeshStats {
        var reader = MeshWireReader(data)
        var values: [UInt64] = []
        while reader.remaining >= 8 {
            values.append(try reader.u64())
        }
        var stats = MeshStats()
        func value(_ index: Int) -> UInt64 {
            index < values.count ? values[index] : 0
        }
        stats.framesIn = value(0)
        stats.framesDroppedRate = value(1)
        stats.framesDroppedInvalid = value(2)
        stats.bundlesIn = value(3)
        stats.bundlesDroppedInvalid = value(4)
        stats.bundlesDroppedRate = value(5)
        stats.bundlesDroppedQuota = value(6)
        stats.bundlesForwarded = value(7)
        stats.messagesDelivered = value(8)
        stats.messagesUndecryptable = value(9)
        stats.messagesDeferred = value(10)
        stats.acksRejected = value(11)
        stats.acksVerified = value(12)
        stats.bytesOut = value(13)
        stats.links = value(14)
        stats.storeBundles = value(15)
        stats.storeBytes = value(16)
        stats.outstanding = value(17)
        return stats
    }
}

// MARK: - Hex

extension Data {
    /// Lowercase hex, the way meshlink renders fingerprints and bundle ids.
    public var meshHex: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
