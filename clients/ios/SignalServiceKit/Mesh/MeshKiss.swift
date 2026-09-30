//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! KISS framing and the RNode command set, a Swift port of
//! `rust/meshlink/src/kiss.rs`. RNode LoRa boards speak KISS over their BLE
//! serial service; meshlink frames travel as KISS data frames (command 0x00).
//! Pure encoding, no I/O: `RNodeBleLink` owns the CoreBluetooth pipe.

import Foundation

public enum MeshKiss {
    /// Frame end.
    public static let fend: UInt8 = 0xC0
    /// Frame escape.
    public static let fesc: UInt8 = 0xDB
    /// Transposed frame end.
    public static let tfend: UInt8 = 0xDC
    /// Transposed frame escape.
    public static let tfesc: UInt8 = 0xDD

    /// KISS/RNode command bytes (the byte after FEND). `data` is standard
    /// KISS; the rest follow the RNode firmware command table and should be
    /// checked against the firmware you flash.
    public enum Command {
        public static let data: UInt8 = 0x00
        public static let frequency: UInt8 = 0x01
        public static let bandwidth: UInt8 = 0x02
        public static let txPower: UInt8 = 0x03
        public static let spreadingFactor: UInt8 = 0x04
        public static let codingRate: UInt8 = 0x05
        public static let radioState: UInt8 = 0x06
        public static let detect: UInt8 = 0x08
        public static let ready: UInt8 = 0x0F
    }

    /// Encodes one KISS frame with the given command byte.
    public static func encode(command: UInt8, payload: Data) -> Data {
        var out = Data(capacity: payload.count + 4)
        out.append(fend)
        out.append(command)
        for byte in payload {
            switch byte {
            case fend:
                out.append(fesc)
                out.append(tfend)
            case fesc:
                out.append(fesc)
                out.append(tfesc)
            default:
                out.append(byte)
            }
        }
        out.append(fend)
        return out
    }

    /// One decoded frame.
    public struct Frame: Equatable {
        public let command: UInt8
        public let payload: Data
    }

    /// Incremental decoder for a byte stream: copes with frames split across
    /// arbitrary read boundaries and with line noise between frames.
    public struct Decoder {
        private var inFrame = false
        private var escaped = false
        private var command: UInt8?
        private var buffer = Data()

        public init() {}

        /// Feeds bytes, returning every complete frame.
        public mutating func feed(_ bytes: Data) -> [Frame] {
            var frames: [Frame] = []
            for byte in bytes {
                if byte == MeshKiss.fend {
                    if inFrame {
                        if let command {
                            frames.append(Frame(command: command, payload: buffer))
                        }
                        buffer = Data()
                        escaped = false
                    }
                    inFrame = true
                    command = nil
                    continue
                }
                if !inFrame {
                    continue
                }
                if command == nil {
                    command = byte
                    continue
                }
                if escaped {
                    escaped = false
                    switch byte {
                    case MeshKiss.tfend:
                        buffer.append(MeshKiss.fend)
                    case MeshKiss.tfesc:
                        buffer.append(MeshKiss.fesc)
                    default:
                        // Invalid escape: drop the frame.
                        inFrame = false
                        command = nil
                        buffer = Data()
                    }
                    continue
                }
                if byte == MeshKiss.fesc {
                    escaped = true
                    continue
                }
                buffer.append(byte)
            }
            return frames
        }
    }

    /// Radio parameters for an RNode LoRa board (`RadioConfig` in kiss.rs).
    public struct RadioConfig: Equatable {
        /// Carrier frequency in Hz, e.g. 868_000_000 (EU) or 915_000_000 (US).
        public var frequencyHz: UInt32
        /// Channel bandwidth in Hz, e.g. 125_000.
        public var bandwidthHz: UInt32
        /// Transmit power in dBm (subject to local regulation).
        public var txPowerDbm: UInt8
        /// LoRa spreading factor 7...12.
        public var spreadingFactor: UInt8
        /// Coding rate 5...8 (4/5 ... 4/8).
        public var codingRate: UInt8

        public init(frequencyHz: UInt32, bandwidthHz: UInt32, txPowerDbm: UInt8, spreadingFactor: UInt8, codingRate: UInt8) {
            self.frequencyHz = frequencyHz
            self.bandwidthHz = bandwidthHz
            self.txPowerDbm = txPowerDbm
            self.spreadingFactor = spreadingFactor
            self.codingRate = codingRate
        }

        /// `RadioConfig::EU_LONG_RANGE`: 868.0 MHz, 125 kHz, SF 10, CR 4/5, 14 dBm.
        /// The legal band and power are the operator's responsibility.
        public static let euLongRange = RadioConfig(
            frequencyHz: 868_000_000,
            bandwidthHz: 125_000,
            txPowerDbm: 14,
            spreadingFactor: 10,
            codingRate: 5,
        )

        /// The KISS command frames that configure and enable the radio, in
        /// the same order and byte layout as `RadioConfig::to_frames()`:
        /// frequency (u32 BE), bandwidth (u32 BE), TX power, SF, CR, radio state 1.
        public func frames() -> [Data] {
            return [
                MeshKiss.encode(command: Command.frequency, payload: Self.bigEndian(frequencyHz)),
                MeshKiss.encode(command: Command.bandwidth, payload: Self.bigEndian(bandwidthHz)),
                MeshKiss.encode(command: Command.txPower, payload: Data([txPowerDbm])),
                MeshKiss.encode(command: Command.spreadingFactor, payload: Data([spreadingFactor])),
                MeshKiss.encode(command: Command.codingRate, payload: Data([codingRate])),
                MeshKiss.encode(command: Command.radioState, payload: Data([0x01])),
            ]
        }

        /// Largest meshlink frame that fits one LoRa transmission (`frame_mtu`).
        public var frameMTU: Int {
            spreadingFactor >= 11 ? 120 : 200
        }

        private static func bigEndian(_ value: UInt32) -> Data {
            Data([
                UInt8((value >> 24) & 0xFF),
                UInt8((value >> 16) & 0xFF),
                UInt8((value >> 8) & 0xFF),
                UInt8(value & 0xFF),
            ])
        }
    }
}
