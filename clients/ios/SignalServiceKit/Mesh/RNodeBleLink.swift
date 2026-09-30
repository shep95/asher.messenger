//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! A LoRa link through an RNode board (Heltec/LILYGO flashed with RNode
//! firmware) over its BLE serial service: the Nordic UART Service.
//!
//!   service 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
//!   RX      6E400002-... (we write to the board)
//!   TX      6E400003-... (the board notifies us)
//!
//! meshlink frames are KISS data frames on that byte stream (`MeshKiss`).
//! The link attaches with the `lora` preset (200 B/s, 20 frames/s) and the
//! radio profile's frame MTU (200 bytes at SF <= 10). Optionally the radio is
//! configured on connect with `RadioConfig.frames()`; the legal band and
//! power are the operator's responsibility. Newer RNode firmware requires the
//! board to be paired in iOS Settings first.

import CoreBluetooth
import Foundation

public final class RNodeBleLink: NSObject, MeshLink {
    public let name = "rnode"

    /// Nordic UART Service identifiers used by RNode's BLE serial port.
    public enum NordicUart {
        public static let service = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        /// Write: host -> board.
        public static let rx = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        /// Notify: board -> host.
        public static let tx = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    }

    private let queue = DispatchQueue(label: "org.asher.meshlink.rnode")
    private let radioConfig: MeshKiss.RadioConfig
    private let configureRadio: Bool

    private var node: MeshNode?
    private var centralManager: CBCentralManager?
    private var board: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var pump: MeshLinkPump?
    private var decoder = MeshKiss.Decoder()

    /// - Parameters:
    ///   - radioConfig: the LoRa profile (MTU comes from it).
    ///   - configureRadio: send the profile's KISS command frames on connect.
    public init(radioConfig: MeshKiss.RadioConfig = .euLongRange, configureRadio: Bool = false) {
        self.radioConfig = radioConfig
        self.configureRadio = configureRadio
        super.init()
    }

    // MARK: - MeshLink

    public func start(node: MeshNode) {
        queue.async {
            self.node = node
            self.centralManager = CBCentralManager(
                delegate: self,
                queue: self.queue,
                options: [CBCentralManagerOptionShowPowerAlertKey: false],
            )
        }
    }

    public func stop() {
        queue.async {
            self.node = nil
            self.pump?.stop()
            self.pump = nil
            if let centralManager = self.centralManager, centralManager.state == .poweredOn {
                centralManager.stopScan()
                if let board = self.board {
                    centralManager.cancelPeripheralConnection(board)
                }
            }
            self.board = nil
            self.rxCharacteristic = nil
            self.centralManager = nil
        }
    }

    // MARK: - Helpers

    private func scanIfReady() {
        guard let centralManager, centralManager.state == .poweredOn, node != nil, board == nil else {
            return
        }
        centralManager.scanForPeripherals(withServices: [NordicUart.service], options: nil)
    }

    private func dropBoard() {
        pump?.stop()
        pump = nil
        rxCharacteristic = nil
        board = nil
        decoder = MeshKiss.Decoder()
        scanIfReady()
    }

    /// Writes one KISS-encoded byte string to the board, chunked to the
    /// characteristic's maximum write length. Returns false if the board is
    /// not ready for a write-without-response (the pump waits and retries).
    private func write(_ bytes: Data, to peripheral: CBPeripheral, characteristic: CBCharacteristic, pump: MeshLinkPump?) -> Bool {
        let withoutResponse = characteristic.properties.contains(.writeWithoutResponse)
        let writeType: CBCharacteristicWriteType = withoutResponse ? .withoutResponse : .withResponse
        let chunkSize = max(20, peripheral.maximumWriteValueLength(for: writeType))
        var offset = 0
        while offset < bytes.count {
            if withoutResponse, !peripheral.canSendWriteWithoutResponse {
                if offset == 0 {
                    return false
                }
                // Mid-frame: wait for the board rather than tear the frame.
                if pump?.waitUntilReady(timeout: 2) != true {
                    Logger.warn("RNode BLE stalled mid-frame; frame truncated")
                    return true
                }
                continue
            }
            let end = min(offset + chunkSize, bytes.count)
            peripheral.writeValue(bytes.subdata(in: offset..<end), for: characteristic, type: writeType)
            offset = end
        }
        return true
    }
}

// MARK: - CBCentralManagerDelegate

extension RNodeBleLink: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Logger.info("RNode BLE central state \(central.state.rawValue)")
        if central.state == .poweredOn {
            scanIfReady()
        } else {
            dropBoard()
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber,
    ) {
        guard board == nil else {
            return
        }
        // Prefer boards that announce themselves as RNode; accept any NUS
        // device otherwise (the user opted into this link explicitly).
        let advertisedName = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? ""
        Logger.info("RNode BLE discovered \(advertisedName) rssi \(RSSI)")
        board = peripheral
        peripheral.delegate = self
        central.stopScan()
        central.connect(peripheral, options: nil)
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([NordicUart.service])
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Logger.warn("RNode BLE connect failed: \(String(describing: error))")
        dropBoard()
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Logger.info("RNode BLE disconnected")
        dropBoard()
    }
}

// MARK: - CBPeripheralDelegate

extension RNodeBleLink: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == NordicUart.service }) else {
            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics([NordicUart.rx, NordicUart.tx], for: service)
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard
            error == nil,
            let characteristics = service.characteristics,
            let rx = characteristics.first(where: { $0.uuid == NordicUart.rx }),
            let tx = characteristics.first(where: { $0.uuid == NordicUart.tx })
        else {
            Logger.warn("RNode BLE: NUS characteristics missing")
            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }
        rxCharacteristic = rx
        peripheral.setNotifyValue(true, for: tx)
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == NordicUart.tx else {
            return
        }
        if let error {
            Logger.warn("RNode BLE notify subscribe failed: \(error)")
            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }
        guard characteristic.isNotifying, let node, let rx = rxCharacteristic else {
            pump?.stop()
            pump = nil
            return
        }

        if configureRadio {
            for frame in radioConfig.frames() {
                _ = write(frame, to: peripheral, characteristic: rx, pump: nil)
            }
        }

        pump?.stop()
        do {
            pump = try MeshLinkPump(
                node: node,
                name: "rnode \(peripheral.identifier.uuidString.prefix(8))",
                mtu: radioConfig.frameMTU,
                preset: .lora,
            ) { [weak self, weak peripheral] pump, frame in
                guard let self, let peripheral, peripheral.state == .connected else { return false }
                let encoded = MeshKiss.encode(command: MeshKiss.Command.data, payload: frame)
                return self.write(encoded, to: peripheral, characteristic: rx, pump: pump)
            }
        } catch {
            Logger.error("RNode BLE could not attach link: \(error)")
        }
    }

    public func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        pump?.markReady()
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.uuid == NordicUart.tx, let value = characteristic.value else {
            return
        }
        for frame in decoder.feed(value) {
            switch frame.command {
            case MeshKiss.Command.data:
                pump?.receive(frame.payload)
            case MeshKiss.Command.ready:
                pump?.markReady()
            default:
                // Radio status/config echoes; not needed for transport.
                break
            }
        }
    }
}
