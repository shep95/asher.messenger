//
// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only
//

//! KISS framing and the RNode command set.
//!
//! KISS ("Keep It Simple, Stupid", 1987) is the framing that packet radios,
//! TNCs and LoRa boards running RNode firmware speak over serial and
//! Bluetooth: frames delimited by `FEND` with two escape bytes. meshlink
//! frames are carried as KISS data frames (`CMD_DATA`), so an $18 Heltec or
//! LILYGO board flashed with RNode becomes a meshlink link with no other
//! protocol work. The serial or Bluetooth byte pipe itself is provided by the
//! platform layer (Android USB/BLE, iOS BLE, a desktop serial port); this
//! module is pure encoding and has no I/O.

/// Frame end.
pub const FEND: u8 = 0xC0;
/// Frame escape.
pub const FESC: u8 = 0xDB;
/// Transposed frame end.
pub const TFEND: u8 = 0xDC;
/// Transposed frame escape.
pub const TFESC: u8 = 0xDD;

/// KISS/RNode command bytes (the byte following FEND). The data command is
/// standard KISS; the others follow the RNode firmware command table and
/// should be checked against the firmware version you flash.
pub mod cmd {
    pub const DATA: u8 = 0x00;
    pub const FREQUENCY: u8 = 0x01;
    pub const BANDWIDTH: u8 = 0x02;
    pub const TXPOWER: u8 = 0x03;
    pub const SPREADING_FACTOR: u8 = 0x04;
    pub const CODING_RATE: u8 = 0x05;
    pub const RADIO_STATE: u8 = 0x06;
    pub const DETECT: u8 = 0x08;
    pub const READY: u8 = 0x0F;
}

/// Encodes one KISS frame with the given command byte.
pub fn encode(command: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload.len() + 4);
    out.push(FEND);
    out.push(command);
    for &b in payload {
        match b {
            FEND => out.extend_from_slice(&[FESC, TFEND]),
            FESC => out.extend_from_slice(&[FESC, TFESC]),
            _ => out.push(b),
        }
    }
    out.push(FEND);
    out
}

/// Radio parameters for an RNode LoRa board.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RadioConfig {
    /// Carrier frequency in Hz, e.g. 868_000_000 (EU) or 915_000_000 (US).
    pub frequency_hz: u32,
    /// Channel bandwidth in Hz, e.g. 125_000.
    pub bandwidth_hz: u32,
    /// Transmit power in dBm (subject to local regulation).
    pub tx_power_dbm: u8,
    /// LoRa spreading factor 7..=12; higher = longer range, lower data rate.
    pub spreading_factor: u8,
    /// Coding rate 5..=8 (4/5 .. 4/8).
    pub coding_rate: u8,
}

impl RadioConfig {
    /// A conservative long-range profile for the 868 MHz band.
    pub const EU_LONG_RANGE: RadioConfig = RadioConfig {
        frequency_hz: 868_000_000,
        bandwidth_hz: 125_000,
        tx_power_dbm: 14,
        spreading_factor: 10,
        coding_rate: 5,
    };

    /// The KISS command frames that configure and enable an RNode radio.
    pub fn to_frames(&self) -> Vec<Vec<u8>> {
        vec![
            encode(cmd::FREQUENCY, &self.frequency_hz.to_be_bytes()),
            encode(cmd::BANDWIDTH, &self.bandwidth_hz.to_be_bytes()),
            encode(cmd::TXPOWER, &[self.tx_power_dbm]),
            encode(cmd::SPREADING_FACTOR, &[self.spreading_factor]),
            encode(cmd::CODING_RATE, &[self.coding_rate]),
            encode(cmd::RADIO_STATE, &[0x01]),
        ]
    }

    /// Largest meshlink frame that fits one LoRa transmission on this board.
    /// RNode's maximum packet is ~500 bytes; keeping frames small reduces air
    /// time and collision loss at high spreading factors.
    pub fn frame_mtu(&self) -> usize {
        if self.spreading_factor >= 11 {
            120
        } else {
            200
        }
    }
}

/// Incremental KISS decoder for a byte stream.
#[derive(Default)]
/// Longest KISS payload accepted; a stream that never closes a frame cannot
/// grow memory past this.
pub const MAX_KISS_FRAME: usize = 8192;

pub struct Decoder {
    in_frame: bool,
    escaped: bool,
    command: Option<u8>,
    buf: Vec<u8>,
}

impl Decoder {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feeds bytes, returning every complete `(command, payload)` frame.
    pub fn feed(&mut self, bytes: &[u8]) -> Vec<(u8, Vec<u8>)> {
        let mut frames = Vec::new();
        for &b in bytes {
            if b == FEND {
                if self.in_frame {
                    if let Some(c) = self.command.take() {
                        frames.push((c, std::mem::take(&mut self.buf)));
                    }
                    self.buf.clear();
                    self.escaped = false;
                }
                self.in_frame = true;
                self.command = None;
                continue;
            }
            if !self.in_frame {
                continue;
            }
            if self.command.is_none() {
                self.command = Some(b);
                continue;
            }
            if self.escaped {
                self.escaped = false;
                match b {
                    TFEND => self.buf.push(FEND),
                    TFESC => self.buf.push(FESC),
                    // Invalid escape: drop the frame.
                    _ => {
                        self.in_frame = false;
                        self.command = None;
                        self.buf.clear();
                    }
                }
                continue;
            }
            if b == FESC {
                self.escaped = true;
                continue;
            }
            if self.buf.len() >= MAX_KISS_FRAME {
                // Oversized or unterminated frame: drop it and resynchronise.
                self.in_frame = false;
                self.command = None;
                self.buf.clear();
                continue;
            }
            self.buf.push(b);
        }
        frames
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn escapes_and_decodes_in_arbitrary_chunks() {
        let payload = vec![0x01, FEND, 0x02, FESC, 0x03, FEND, FEND];
        let encoded = encode(cmd::DATA, &payload);
        assert!(!encoded[1..encoded.len() - 1].contains(&FEND));
        let mut d = Decoder::new();
        let mut got = Vec::new();
        for chunk in encoded.chunks(3) {
            got.extend(d.feed(chunk));
        }
        assert_eq!(got, vec![(cmd::DATA, payload)]);
    }

    #[test]
    fn back_to_back_frames_and_noise() {
        let a = encode(cmd::DATA, b"first");
        let b = encode(cmd::READY, &[]);
        let mut stream = vec![0x55, 0x66]; // line noise before the first FEND
        stream.extend(&a);
        stream.extend(&b);
        let got = Decoder::new().feed(&stream);
        assert_eq!(
            got,
            vec![(cmd::DATA, b"first".to_vec()), (cmd::READY, vec![])]
        );
    }

    #[test]
    fn radio_config_frames() {
        let frames = RadioConfig::EU_LONG_RANGE.to_frames();
        assert_eq!(frames.len(), 6);
        assert_eq!(frames[0][1], cmd::FREQUENCY);
        assert_eq!(&frames[0][2..6], &868_000_000u32.to_be_bytes());
        assert_eq!(frames[5], vec![FEND, cmd::RADIO_STATE, 0x01, FEND]);
    }
}
