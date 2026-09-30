// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// KISS framing and the RNode command set, a TypeScript port of
// libs/libsignal/rust/meshlink/src/kiss.rs so Desktop can drive a LoRa board
// over Web Serial without the crate opening any device itself.

export const KISS_FEND = 0xc0;
export const KISS_FESC = 0xdb;
export const KISS_TFEND = 0xdc;
export const KISS_TFESC = 0xdd;

/** KISS/RNode command bytes (the byte after FEND). */
export const KissCommand = {
  Data: 0x00,
  Frequency: 0x01,
  Bandwidth: 0x02,
  TxPower: 0x03,
  SpreadingFactor: 0x04,
  CodingRate: 0x05,
  RadioState: 0x06,
  Detect: 0x08,
  Ready: 0x0f,
} as const;

/** Encodes one KISS frame with the given command byte. */
export function encodeKiss(command: number, payload: Uint8Array): Uint8Array {
  const out: Array<number> = [KISS_FEND, command & 0xff];
  for (const b of payload) {
    if (b === KISS_FEND) {
      out.push(KISS_FESC, KISS_TFEND);
    } else if (b === KISS_FESC) {
      out.push(KISS_FESC, KISS_TFESC);
    } else {
      out.push(b);
    }
  }
  out.push(KISS_FEND);
  return Uint8Array.from(out);
}

export type KissFrame = Readonly<{ command: number; payload: Uint8Array }>;

/**
 * Incremental decoder for a byte stream: copes with frames split across
 * arbitrary read boundaries and with line noise between frames. Same state
 * machine as the Rust `Decoder`, including dropping a frame on a bad escape.
 */
export class KissDecoder {
  #inFrame = false;
  #escaped = false;
  #command: number | undefined;
  #buf: Array<number> = [];

  feed(bytes: Uint8Array): Array<KissFrame> {
    const frames: Array<KissFrame> = [];
    for (const b of bytes) {
      if (b === KISS_FEND) {
        if (this.#inFrame) {
          if (this.#command !== undefined) {
            frames.push({
              command: this.#command,
              payload: Uint8Array.from(this.#buf),
            });
          }
          this.#buf = [];
          this.#escaped = false;
        }
        this.#inFrame = true;
        this.#command = undefined;
        continue;
      }
      if (!this.#inFrame) {
        continue;
      }
      if (this.#command === undefined) {
        this.#command = b;
        continue;
      }
      if (this.#escaped) {
        this.#escaped = false;
        if (b === KISS_TFEND) {
          this.#buf.push(KISS_FEND);
        } else if (b === KISS_TFESC) {
          this.#buf.push(KISS_FESC);
        } else {
          // Invalid escape: drop the frame.
          this.#inFrame = false;
          this.#command = undefined;
          this.#buf = [];
        }
        continue;
      }
      if (b === KISS_FESC) {
        this.#escaped = true;
        continue;
      }
      this.#buf.push(b);
    }
    return frames;
  }
}

/** Radio parameters for an RNode LoRa board. */
export type RadioConfigType = Readonly<{
  /** Carrier frequency in Hz, e.g. 868_000_000 (EU) or 915_000_000 (US). */
  frequencyHz: number;
  /** Channel bandwidth in Hz, e.g. 125_000. */
  bandwidthHz: number;
  /** Transmit power in dBm (subject to local regulation). */
  txPowerDbm: number;
  /** LoRa spreading factor 7..=12. */
  spreadingFactor: number;
  /** Coding rate 5..=8 (4/5 .. 4/8). */
  codingRate: number;
}>;

/**
 * `RadioConfig::EU_LONG_RANGE`: 868.0 MHz, 125 kHz, SF10, CR 4/5, 14 dBm.
 * The legal band and power are the operator's responsibility per region.
 */
export const RADIO_CONFIG_EU_LONG_RANGE: RadioConfigType = {
  frequencyHz: 868_000_000,
  bandwidthHz: 125_000,
  txPowerDbm: 14,
  spreadingFactor: 10,
  codingRate: 5,
};

function u32be(value: number): Uint8Array {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value >>> 0, false);
  return out;
}

/** The KISS command frames that configure and enable an RNode radio. */
export function radioConfigFrames(config: RadioConfigType): Array<Uint8Array> {
  return [
    encodeKiss(KissCommand.Frequency, u32be(config.frequencyHz)),
    encodeKiss(KissCommand.Bandwidth, u32be(config.bandwidthHz)),
    encodeKiss(KissCommand.TxPower, Uint8Array.of(config.txPowerDbm & 0xff)),
    encodeKiss(
      KissCommand.SpreadingFactor,
      Uint8Array.of(config.spreadingFactor & 0xff)
    ),
    encodeKiss(KissCommand.CodingRate, Uint8Array.of(config.codingRate & 0xff)),
    encodeKiss(KissCommand.RadioState, Uint8Array.of(0x01)),
  ];
}

/** Largest meshlink frame that fits one LoRa transmission on this board. */
export function radioFrameMtu(config: RadioConfigType): number {
  return config.spreadingFactor >= 11 ? 120 : 200;
}
