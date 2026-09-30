//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! Phone-to-phone meshlink link over Bluetooth LE, in both GATT roles at once:
//!
//! * Peripheral: advertises `MeshBleGatt.service`, exposes an inbound
//!   write-without-response characteristic (frames written INTO us) and an
//!   outbound notify characteristic (frames we send). One meshlink link per
//!   subscribed central; its MTU is `central.maximumUpdateValueLength`.
//! * Central: scans for the service, connects, subscribes to the peer's
//!   outbound characteristic and writes frames to its inbound one. One link
//!   per connected peripheral; its MTU is
//!   `peripheral.maximumWriteValueLength(for: .withoutResponse)`.
//!
//! One meshlink frame per characteristic write/notification, no extra framing.
//! Two phones that both scan and advertise may end up with two links between
//! them; meshlink deduplicates bundles by id, so that only costs a little air
//! time. Links attach with the `ble` preset (16 KiB/s, 200 frames/s).

import CoreBluetooth
import Foundation

/// The fixed GATT identifiers every Asher platform uses for meshlink over BLE.
/// Changing any of these breaks interop with Android and desktop builds.
public enum MeshBleGatt {
    /// meshlink service.
    public static let service = CBUUID(string: "6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e1")
    /// Inbound: frames written INTO the peripheral (write without response).
    public static let inboundCharacteristic = CBUUID(string: "6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e2")
    /// Outbound: frames the peripheral sends out (notify).
    public static let outboundCharacteristic = CBUUID(string: "6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e3")
    /// Advertised local name (informational).
    public static let localName = "Asher"
}

public final class BleLink: NSObject, MeshLink {
    public let name = "ble"

    private let queue = DispatchQueue(label: "org.asher.meshlink.ble")
    private var node: MeshNode?

    // Peripheral role
    private var peripheralManager: CBPeripheralManager?
    private var inboundCharacteristic: CBMutableCharacteristic?
    private var outboundCharacteristic: CBMutableCharacteristic?
    /// central.identifier -> pump
    private var centralPumps: [UUID: MeshLinkPump] = [:]

    // Central role
    private var centralManager: CBCentralManager?
    /// peripheral.identifier -> peripheral (kept strongly while connecting/connected)
    private var peripherals: [UUID: CBPeripheral] = [:]
    /// peripheral.identifier -> the peer's inbound characteristic we write to
    private var peerInbound: [UUID: CBCharacteristic] = [:]
    /// peripheral.identifier -> pump
    private var peripheralPumps: [UUID: MeshLinkPump] = [:]

    public override init() {
        super.init()
    }

    // MARK: - MeshLink

    public func start(node: MeshNode) {
        queue.async {
            self.node = node
            self.peripheralManager = CBPeripheralManager(
                delegate: self,
                queue: self.queue,
                options: [CBPeripheralManagerOptionShowPowerAlertKey: false],
            )
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
            if let centralManager = self.centralManager {
                if centralManager.state == .poweredOn {
                    centralManager.stopScan()
                }
                for peripheral in self.peripherals.values {
                    centralManager.cancelPeripheralConnection(peripheral)
                }
            }
            for pump in self.peripheralPumps.values {
                pump.stop()
            }
            self.peripheralPumps = [:]
            self.peerInbound = [:]
            self.peripherals = [:]

            if let peripheralManager = self.peripheralManager, peripheralManager.state == .poweredOn {
                peripheralManager.stopAdvertising()
                peripheralManager.removeAllServices()
            }
            for pump in self.centralPumps.values {
                pump.stop()
            }
            self.centralPumps = [:]
            self.peripheralManager = nil
            self.centralManager = nil
        }
    }

    // MARK: - Peripheral role helpers

    private func startAdvertisingIfReady() {
        guard let peripheralManager, peripheralManager.state == .poweredOn, node != nil else {
            return
        }
        let inbound = CBMutableCharacteristic(
            type: MeshBleGatt.inboundCharacteristic,
            properties: [.writeWithoutResponse],
            value: nil,
            permissions: [.writeable],
        )
        let outbound = CBMutableCharacteristic(
            type: MeshBleGatt.outboundCharacteristic,
            properties: [.notify],
            value: nil,
            permissions: [.readable],
        )
        let service = CBMutableService(type: MeshBleGatt.service, primary: true)
        service.characteristics = [inbound, outbound]
        inboundCharacteristic = inbound
        outboundCharacteristic = outbound
        peripheralManager.removeAllServices()
        peripheralManager.add(service)
    }

    private func startScanningIfReady() {
        guard let centralManager, centralManager.state == .poweredOn, node != nil else {
            return
        }
        centralManager.scanForPeripherals(
            withServices: [MeshBleGatt.service],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false],
        )
    }

    private func dropPeripheral(_ peripheral: CBPeripheral) {
        let id = peripheral.identifier
        peripheralPumps.removeValue(forKey: id)?.stop()
        peerInbound.removeValue(forKey: id)
        peripherals.removeValue(forKey: id)
    }
}

// MARK: - CBPeripheralManagerDelegate

extension BleLink: CBPeripheralManagerDelegate {
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Logger.info("Mesh BLE peripheral state \(peripheral.state.rawValue)")
        if peripheral.state == .poweredOn {
            startAdvertisingIfReady()
        } else {
            for pump in centralPumps.values {
                pump.stop()
            }
            centralPumps = [:]
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            Logger.error("Mesh BLE service add failed: \(error)")
            return
        }
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [MeshBleGatt.service],
            CBAdvertisementDataLocalNameKey: MeshBleGatt.localName,
        ])
    }

    public func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            Logger.error("Mesh BLE advertising failed: \(error)")
        } else {
            Logger.info("Mesh BLE advertising")
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic,
    ) {
        guard characteristic.uuid == MeshBleGatt.outboundCharacteristic, let node, let outbound = outboundCharacteristic else {
            return
        }
        let id = central.identifier
        centralPumps[id]?.stop()
        do {
            let pump = try MeshLinkPump(
                node: node,
                name: "ble-peripheral \(id.uuidString.prefix(8))",
                mtu: central.maximumUpdateValueLength,
                preset: .ble,
            ) { [weak peripheral] _, frame in
                guard let peripheral else { return false }
                return peripheral.updateValue(frame, for: outbound, onSubscribedCentrals: [central])
            }
            centralPumps[id] = pump
        } catch {
            Logger.error("Mesh BLE could not attach link for central \(id): \(error)")
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic,
    ) {
        guard characteristic.uuid == MeshBleGatt.outboundCharacteristic else {
            return
        }
        centralPumps.removeValue(forKey: central.identifier)?.stop()
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        for pump in centralPumps.values {
            pump.markReady()
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard request.characteristic.uuid == MeshBleGatt.inboundCharacteristic, let value = request.value else {
                continue
            }
            if let pump = centralPumps[request.central.identifier] {
                pump.receive(value)
            } else {
                Logger.warn("Mesh BLE write from a central that has not subscribed; ignoring")
            }
            // Our inbound characteristic is write-without-response only, so
            // no `respond(to:withResult:)` is due. If a peer used a write
            // with response anyway, answer so it does not hang.
            if request.characteristic.properties.contains(.write) {
                peripheral.respond(to: request, withResult: .success)
            }
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension BleLink: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Logger.info("Mesh BLE central state \(central.state.rawValue)")
        if central.state == .poweredOn {
            startScanningIfReady()
        } else {
            for peripheral in peripherals.values {
                dropPeripheral(peripheral)
            }
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber,
    ) {
        let id = peripheral.identifier
        guard peripherals[id] == nil else {
            return
        }
        peripherals[id] = peripheral
        peripheral.delegate = self
        Logger.info("Mesh BLE discovered \(id.uuidString.prefix(8)) rssi \(RSSI)")
        central.connect(peripheral, options: nil)
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([MeshBleGatt.service])
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Logger.warn("Mesh BLE connect to \(peripheral.identifier.uuidString.prefix(8)) failed: \(String(describing: error))")
        dropPeripheral(peripheral)
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Logger.info("Mesh BLE disconnected \(peripheral.identifier.uuidString.prefix(8))")
        dropPeripheral(peripheral)
        // Scanning keeps running; the peer is rediscovered when it is back.
    }
}

// MARK: - CBPeripheralDelegate

extension BleLink: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == MeshBleGatt.service }) else {
            Logger.warn("Mesh BLE peer has no meshlink service: \(String(describing: error))")
            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics([MeshBleGatt.inboundCharacteristic, MeshBleGatt.outboundCharacteristic], for: service)
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let characteristics = service.characteristics else {
            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }
        guard
            let inbound = characteristics.first(where: { $0.uuid == MeshBleGatt.inboundCharacteristic }),
            let outbound = characteristics.first(where: { $0.uuid == MeshBleGatt.outboundCharacteristic })
        else {
            Logger.warn("Mesh BLE peer service lacks the inbound/outbound characteristics")
            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }
        peerInbound[peripheral.identifier] = inbound
        peripheral.setNotifyValue(true, for: outbound)
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == MeshBleGatt.outboundCharacteristic else {
            return
        }
        let id = peripheral.identifier
        if let error {
            Logger.warn("Mesh BLE notify subscribe failed: \(error)")
            centralManager?.cancelPeripheralConnection(peripheral)
            return
        }
        guard characteristic.isNotifying, let node, let inbound = peerInbound[id] else {
            peripheralPumps.removeValue(forKey: id)?.stop()
            return
        }
        peripheralPumps[id]?.stop()
        do {
            let pump = try MeshLinkPump(
                node: node,
                name: "ble-central \(id.uuidString.prefix(8))",
                mtu: peripheral.maximumWriteValueLength(for: .withoutResponse),
                preset: .ble,
            ) { [weak peripheral] _, frame in
                guard let peripheral, peripheral.state == .connected else { return false }
                guard peripheral.canSendWriteWithoutResponse else { return false }
                peripheral.writeValue(frame, for: inbound, type: .withoutResponse)
                return true
            }
            peripheralPumps[id] = pump
        } catch {
            Logger.error("Mesh BLE could not attach link for peripheral \(id): \(error)")
        }
    }

    public func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        peripheralPumps[peripheral.identifier]?.markReady()
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.uuid == MeshBleGatt.outboundCharacteristic, let value = characteristic.value else {
            return
        }
        peripheralPumps[peripheral.identifier]?.receive(value)
    }

    public func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        if invalidatedServices.contains(where: { $0.uuid == MeshBleGatt.service }) {
            centralManager?.cancelPeripheralConnection(peripheral)
        }
    }
}
