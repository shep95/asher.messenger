//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! The node side of one radio link: attaches a meshlink link with the given
//! MTU/pacing preset, runs a writer thread that pulls frames with
//! `MeshNode_LinkRead` and hands them to the transport, and feeds frames from
//! the transport into `MeshNode_LinkWrite`. Detaches on `stop()`.

import Foundation

/// meshlink `LinkOptions` presets (transport/mod.rs).
public enum MeshLinkPreset {
    /// `LinkOptions::ble`: 16 KiB/s, 200 frames/s.
    case ble
    /// `LinkOptions::lora`: 200 B/s, 20 frames/s.
    case lora

    var maxBytesPerSec: UInt32 {
        switch self {
        case .ble: return 16 * 1024
        case .lora: return 200
        }
    }

    var maxFramesPerSec: UInt32 {
        switch self {
        case .ble: return 200
        case .lora: return 20
        }
    }
}

final class MeshLinkPump {
    /// meshlink's minimum supported MTU.
    static let minimumMTU = 64

    let linkId: MeshNode.LinkId
    let name: String

    private let node: MeshNode
    private let lock = NSLock()
    private var stopped = false
    private let ready = DispatchSemaphore(value: 0)
    /// Puts one frame on the wire. Returns false when the transport cannot
    /// take it right now; the pump then waits for `markReady()` and retries.
    private let send: (MeshLinkPump, Data) -> Bool

    /// Attaches the link and starts the writer thread.
    init(
        node: MeshNode,
        name: String,
        mtu: Int,
        preset: MeshLinkPreset,
        send: @escaping (MeshLinkPump, Data) -> Bool,
    ) throws {
        self.node = node
        self.name = name
        self.send = send
        let effectiveMTU = UInt32(max(Self.minimumMTU, mtu))
        self.linkId = try node.attachLink(
            mtu: effectiveMTU,
            maxBytesPerSec: preset.maxBytesPerSec,
            maxFramesPerSec: preset.maxFramesPerSec,
        )
        Logger.info("Mesh link \(linkId) (\(name)) attached, mtu \(effectiveMTU)")
        MeshNodeService.shared.linkCountDidChange(delta: 1)

        let thread = Thread { [weak self] in
            self?.runWriter()
        }
        thread.name = "meshlink-link-\(linkId)"
        thread.qualityOfService = .utility
        thread.start()
    }

    var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    /// The transport can accept another write/notification.
    func markReady() {
        ready.signal()
    }

    /// Blocks the writer until `markReady()` or the timeout. For transports
    /// that must chunk one frame into several writes.
    func waitUntilReady(timeout: TimeInterval) -> Bool {
        ready.wait(timeout: .now() + timeout) == .success
    }

    /// A frame arrived from the wire.
    func receive(_ frame: Data) {
        guard !isStopped else { return }
        do {
            if try !node.linkWrite(linkId, frame: frame) {
                Logger.warn("Mesh link \(linkId) dropped an inbound frame (back-pressure)")
            }
        } catch {
            Logger.warn("Mesh link \(linkId) link_write failed: \(error)")
        }
    }

    /// Detaches the link; the writer thread exits within one poll.
    func stop() {
        lock.lock()
        if stopped {
            lock.unlock()
            return
        }
        stopped = true
        lock.unlock()
        node.detachLink(linkId)
        ready.signal()
        Logger.info("Mesh link \(linkId) (\(name)) detached")
        MeshNodeService.shared.linkCountDidChange(delta: -1)
    }

    private func runWriter() {
        while !isStopped {
            let frame: Data
            do {
                frame = try node.linkRead(linkId, timeoutMs: 500)
            } catch {
                Logger.warn("Mesh link \(linkId) link_read failed: \(error)")
                Thread.sleep(forTimeInterval: 0.5)
                continue
            }
            if frame.isEmpty {
                continue
            }
            var attempts = 0
            while !isStopped, !send(self, frame) {
                attempts += 1
                if attempts > 40 {
                    // ~20 s of "not ready": drop the frame; anti-entropy resends.
                    Logger.warn("Mesh link \(linkId) transport stalled; dropping a frame")
                    break
                }
                _ = waitUntilReady(timeout: 0.5)
            }
        }
    }
}
