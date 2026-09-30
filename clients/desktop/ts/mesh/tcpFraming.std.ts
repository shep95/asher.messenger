// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Frames over a TCP stream, as meshlink/src/transport/tcp.rs and the
// `meshlinkd` gateway speak them: `u16 big-endian length` then the frame.
// Nothing else is added at this layer; bundles are already end-to-end
// encrypted and a peer here is just another relay.

import { MESH_MAX_FRAME_LEN } from './constants.std.ts';

export class TcpFramingError extends Error {
  constructor(message: string) {
    super(`mesh tcp framing: ${message}`);
    this.name = 'TcpFramingError';
  }
}

/** Prefixes one frame with its length. */
export function encodeTcpFrame(frame: Uint8Array): Uint8Array {
  if (frame.length === 0 || frame.length > 0xffff) {
    throw new TcpFramingError(`frame too large for tcp (${frame.length})`);
  }
  const out = new Uint8Array(2 + frame.length);
  out[0] = frame.length >>> 8;
  out[1] = frame.length & 0xff;
  out.set(frame, 2);
  return out;
}

/**
 * Incremental decoder. Feed it whatever the socket delivers; it returns the
 * complete frames and keeps the remainder. A zero or oversized length is a
 * protocol violation and throws; the caller should drop the connection.
 */
export class TcpFrameDecoder {
  #buffer = new Uint8Array(0);

  feed(chunk: Uint8Array): Array<Uint8Array> {
    if (this.#buffer.length === 0) {
      this.#buffer = new Uint8Array(chunk);
    } else {
      const merged = new Uint8Array(this.#buffer.length + chunk.length);
      merged.set(this.#buffer, 0);
      merged.set(chunk, this.#buffer.length);
      this.#buffer = merged;
    }

    const frames: Array<Uint8Array> = [];
    let offset = 0;
    while (this.#buffer.length - offset >= 2) {
      const len =
        ((this.#buffer[offset] ?? 0) << 8) | (this.#buffer[offset + 1] ?? 0);
      if (len === 0 || len > MESH_MAX_FRAME_LEN) {
        this.#buffer = new Uint8Array(0);
        throw new TcpFramingError(`frame length out of range (${len})`);
      }
      if (this.#buffer.length - offset - 2 < len) {
        break;
      }
      frames.push(
        new Uint8Array(this.#buffer.subarray(offset + 2, offset + 2 + len))
      );
      offset += 2 + len;
    }
    if (offset > 0) {
      this.#buffer = new Uint8Array(this.#buffer.subarray(offset));
    }
    return frames;
  }
}
