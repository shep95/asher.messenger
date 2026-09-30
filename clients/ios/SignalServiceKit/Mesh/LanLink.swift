//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! meshlink over the local network, with no radio hardware at all: two phones
//! (or a phone and a laptop running Desktop / `meshlinkd`) on the same Wi-Fi
//! or hotspot find each other with Bonjour and talk TCP.
//!
//! * Listener: `NWListener` on port 7788 (falls back to any free port; Bonjour
//!   carries the real one), advertised as `_asher-mesh._tcp` with a TXT record
//!   `fp=<our fingerprint hex>`.
//! * Browser: `NWBrowser` for the same service type. To avoid two links between
//!   the same pair only the side with the lexicographically smaller fingerprint
//!   hex dials; the other side just accepts. A peer without a `fp` TXT entry
//!   (an older build, or `meshlinkd` announced by hand) is dialled anyway.
//! * Framing: exactly `libs/libsignal/rust/meshlink/src/transport/tcp.rs` and
//!   Desktop's `tcpFraming.std.ts`: `u16 big-endian length` then the frame,
//!   length 1...8192; anything else is a protocol violation and the
//!   connection is dropped.
//! * Each connection is one meshlink link attached with the `lan` preset
//!   (`AttachLink(mtu 1500, bytesPerSec 262144, framesPerSec 500)`).
//!
//! Requires `NSLocalNetworkUsageDescription` and `_asher-mesh._tcp` in
//! `NSBonjourServices` (Signal-Info.plist). iOS shows the local-network
//! permission prompt the first time the browser starts.

import Foundation
import Network

public final class LanLink: MeshLink {
    public let name = "lan"

    /// The v3 contract's defaults; every platform must agree on the type.
    public static let serviceType = "_asher-mesh._tcp"
    public static let defaultPort: UInt16 = 7788
    public static let txtFingerprintKey = "fp"
    /// meshlink's `MAX_FRAME_LEN`.
    static let maxFrameLength = 8192
    static let linkMTU = 1500
    /// Frames queued in Network.framework per connection before the pump
    /// waits for `markReady()`.
    static let maxInFlightSends = 64
    private static let redialInterval: TimeInterval = 10

    private let queue = DispatchQueue(label: "org.asher.meshlink.lan")
    private var node: MeshNode?
    private var ourFingerprintHex = ""

    private var listener: NWListener?
    private var browser: NWBrowser?
    private var redialTimer: DispatchSourceTimer?

    /// Connections we accepted or dialled, keyed by an opaque id.
    private var peers: [UUID: LanPeer] = [:]
    /// Endpoints we are currently connecting to or connected with (dialled side).
    private var dialledEndpoints: [NWEndpoint: UUID] = [:]
    /// The latest browse results, so the redial timer can retry failed dials.
    private var browseResults: Set<NWBrowser.Result> = []

    public init() {}

    // MARK: - MeshLink

    public func start(node: MeshNode) {
        queue.async {
            self.node = node
            self.ourFingerprintHex = (try? node.fingerprint().meshHex) ?? ""
            self.startListener()
            self.startBrowser()
            self.startRedialTimer()
        }
    }

    public func stop() {
        queue.async {
            self.node = nil
            self.redialTimer?.cancel()
            self.redialTimer = nil
            self.browser?.cancel()
            self.browser = nil
            self.listener?.cancel()
            self.listener = nil
            for peer in self.peers.values {
                peer.close(reason: "link stopped")
            }
            self.peers = [:]
            self.dialledEndpoints = [:]
            self.browseResults = []
        }
    }

    // MARK: - Parameters

    private static func tcpParameters() -> NWParameters {
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        tcpOptions.enableKeepalive = true
        tcpOptions.keepaliveIdle = 15
        let parameters = NWParameters(tls: nil, tcp: tcpOptions)
        // Also use peer-to-peer Wi-Fi (AWDL) so two iPhones with no access
        // point can still see each other.
        parameters.includePeerToPeer = true
        return parameters
    }

    // MARK: - Listener

    private func startListener() {
        guard node != nil else { return }
        let parameters = Self.tcpParameters()
        var listener: NWListener?
        do {
            listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: Self.defaultPort)!)
        } catch {
            Logger.warn("Mesh LAN listener on \(Self.defaultPort) failed (\(error)); using any port")
            listener = try? NWListener(using: parameters, on: .any)
        }
        guard let listener else {
            Logger.error("Mesh LAN listener could not be created")
            return
        }
        let txt = NWTXTRecord([Self.txtFingerprintKey: ourFingerprintHex])
        listener.service = NWListener.Service(
            name: "asher-\(ourFingerprintHex.prefix(8))",
            type: Self.serviceType,
            domain: nil,
            txtRecord: txt,
        )
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            switch state {
            case .ready:
                Logger.info("Mesh LAN listening on port \(listener?.port?.rawValue ?? 0), advertising \(Self.serviceType)")
            case .failed(let error):
                Logger.error("Mesh LAN listener failed: \(error)")
                self?.queue.asyncAfter(deadline: .now() + 5) { [weak self] in
                    guard let self, self.node != nil, self.listener === listener else { return }
                    self.listener = nil
                    self.startListener()
                }
            case .cancelled:
                Logger.info("Mesh LAN listener cancelled")
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    private func accept(_ connection: NWConnection) {
        guard node != nil else {
            connection.cancel()
            return
        }
        Logger.info("Mesh LAN inbound connection from \(connection.endpoint)")
        attach(connection, dialledEndpoint: nil, label: "lan-in")
    }

    // MARK: - Browser

    private func startBrowser() {
        guard node != nil else { return }
        let parameters = NWParameters()
        parameters.includePeerToPeer = true
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: parameters)
        browser.stateUpdateHandler = { [weak self, weak browser] state in
            switch state {
            case .ready:
                Logger.info("Mesh LAN browsing for \(Self.serviceType)")
            case .failed(let error):
                Logger.error("Mesh LAN browser failed: \(error)")
                self?.queue.asyncAfter(deadline: .now() + 5) { [weak self] in
                    guard let self, self.node != nil, self.browser === browser else { return }
                    self.browser = nil
                    self.startBrowser()
                }
            case .waiting(let error):
                // Typically the local-network permission has not been granted yet.
                Logger.warn("Mesh LAN browser waiting: \(error)")
            default:
                break
            }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            self.browseResults = results
            self.dialPending()
        }
        self.browser = browser
        browser.start(queue: queue)
    }

    private func startRedialTimer() {
        redialTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.redialInterval, repeating: Self.redialInterval)
        timer.setEventHandler { [weak self] in
            self?.dialPending()
        }
        redialTimer = timer
        timer.resume()
    }

    /// The peer's fingerprint hex from its TXT record, if it published one.
    static func fingerprintHex(of result: NWBrowser.Result) -> String? {
        guard case .bonjour(let txt) = result.metadata else {
            return nil
        }
        guard let value = txt.dictionary[txtFingerprintKey], !value.isEmpty else {
            return nil
        }
        return value.lowercased()
    }

    /// Whether we (with `ours`) are the side that dials `peer`. Equal
    /// fingerprints are ourselves (or a clone) and are never dialled.
    static func shouldDial(ours: String, peer: String?) -> Bool {
        guard let peer else {
            return true
        }
        return ours < peer
    }

    private func dialPending() {
        guard node != nil else { return }
        for result in browseResults {
            let peerHex = Self.fingerprintHex(of: result)
            guard Self.shouldDial(ours: ourFingerprintHex, peer: peerHex) else {
                continue
            }
            if dialledEndpoints[result.endpoint] != nil {
                continue
            }
            Logger.info("Mesh LAN dialling \(result.endpoint) (peer \(peerHex.map { String($0.prefix(16)) } ?? "?"))")
            let connection = NWConnection(to: result.endpoint, using: Self.tcpParameters())
            attach(connection, dialledEndpoint: result.endpoint, label: "lan-out")
        }
    }

    // MARK: - Peers

    private func attach(_ connection: NWConnection, dialledEndpoint: NWEndpoint?, label: String) {
        let id = UUID()
        let peer = LanPeer(id: id, connection: connection, label: label, queue: queue)
        peers[id] = peer
        if let dialledEndpoint {
            dialledEndpoints[dialledEndpoint] = id
        }
        peer.onReady = { [weak self] peer in
            self?.attachPump(to: peer)
        }
        peer.onClosed = { [weak self] peer in
            guard let self else { return }
            self.peers.removeValue(forKey: peer.id)
            if let dialledEndpoint {
                self.dialledEndpoints.removeValue(forKey: dialledEndpoint)
            }
        }
        peer.start()
    }

    private func attachPump(to peer: LanPeer) {
        guard let node else {
            peer.close(reason: "node gone")
            return
        }
        do {
            let pump = try MeshLinkPump(
                node: node,
                name: "\(peer.label) \(peer.connection.endpoint)",
                mtu: Self.linkMTU,
                preset: .lan,
            ) { [weak peer] _, frame in
                guard let peer else { return false }
                return peer.send(frame: frame)
            }
            peer.pump = pump
            peer.receiveHeader()
        } catch {
            Logger.error("Mesh LAN could not attach link: \(error)")
            peer.close(reason: "attach failed")
        }
    }
}

// MARK: - One TCP connection

/// One accepted or dialled TCP connection: frames the stream, feeds inbound
/// frames to the pump, and paces outbound sends.
final class LanPeer {
    let id: UUID
    let connection: NWConnection
    let label: String
    private let queue: DispatchQueue

    var pump: MeshLinkPump?
    var onReady: ((LanPeer) -> Void)?
    var onClosed: ((LanPeer) -> Void)?

    private let lock = NSLock()
    private var inFlightSends = 0
    private var closed = false
    private var announcedReady = false

    init(id: UUID, connection: NWConnection, label: String, queue: DispatchQueue) {
        self.id = id
        self.connection = connection
        self.label = label
        self.queue = queue
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                if !self.announcedReady {
                    self.announcedReady = true
                    Logger.info("Mesh LAN connection ready: \(self.label) \(self.connection.endpoint)")
                    self.onReady?(self)
                }
            case .failed(let error):
                Logger.warn("Mesh LAN connection failed (\(self.label)): \(error)")
                self.close(reason: "failed")
            case .waiting(let error):
                Logger.info("Mesh LAN connection waiting (\(self.label)): \(error)")
            case .cancelled:
                self.finishClose()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    // MARK: Inbound framing

    /// Reads `u16 length` then the frame, forever, until the stream ends.
    func receiveHeader() {
        connection.receive(minimumIncompleteLength: 2, maximumLength: 2) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error {
                Logger.warn("Mesh LAN read error (\(self.label)): \(error)")
                self.close(reason: "read error")
                return
            }
            guard let data, data.count == 2 else {
                if isComplete {
                    self.close(reason: "peer closed")
                } else {
                    self.close(reason: "short header")
                }
                return
            }
            let length = Int(data[data.startIndex]) << 8 | Int(data[data.startIndex + 1])
            guard length >= 1, length <= LanLink.maxFrameLength else {
                Logger.warn("Mesh LAN frame length out of range (\(length)) from \(self.label); dropping connection")
                self.close(reason: "bad frame length")
                return
            }
            self.receiveBody(length: length)
        }
    }

    private func receiveBody(length: Int) {
        connection.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error {
                Logger.warn("Mesh LAN read error (\(self.label)): \(error)")
                self.close(reason: "read error")
                return
            }
            guard let data, data.count == length else {
                self.close(reason: isComplete ? "peer closed mid-frame" : "short frame")
                return
            }
            self.pump?.receive(data)
            self.receiveHeader()
        }
    }

    // MARK: Outbound framing

    /// Called from the pump's writer thread. Returns false when too many
    /// frames are still queued in Network.framework; the pump then waits for
    /// `markReady()` and retries.
    func send(frame: Data) -> Bool {
        guard frame.count >= 1, frame.count <= LanLink.maxFrameLength else {
            Logger.warn("Mesh LAN refusing to send a \(frame.count)-byte frame")
            return true // drop it; nothing to retry
        }
        lock.lock()
        if closed {
            lock.unlock()
            return false
        }
        if inFlightSends >= LanLink.maxInFlightSends {
            lock.unlock()
            return false
        }
        inFlightSends += 1
        lock.unlock()

        var packet = Data(capacity: frame.count + 2)
        packet.append(UInt8(frame.count >> 8))
        packet.append(UInt8(frame.count & 0xff))
        packet.append(frame)
        connection.send(content: packet, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.lock.lock()
            self.inFlightSends = max(0, self.inFlightSends - 1)
            self.lock.unlock()
            if let error {
                Logger.warn("Mesh LAN write error (\(self.label)): \(error)")
                self.close(reason: "write error")
                return
            }
            self.pump?.markReady()
        })
        return true
    }

    // MARK: Closing

    func close(reason: String) {
        lock.lock()
        if closed {
            lock.unlock()
            return
        }
        closed = true
        lock.unlock()
        Logger.info("Mesh LAN closing \(label) \(connection.endpoint): \(reason)")
        pump?.stop()
        pump = nil
        connection.cancel()
        // `.cancelled` may never arrive if the connection never started; make
        // sure the owner forgets us either way.
        queue.async { [weak self] in
            self?.finishClose()
        }
    }

    private var finished = false
    private func finishClose() {
        if finished { return }
        finished = true
        pump?.stop()
        pump = nil
        onClosed?(self)
        onClosed = nil
        onReady = nil
    }
}
