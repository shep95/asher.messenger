// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Decoders for the compact byte strings the meshlink bridge returns. The
// encoders live in libs/libsignal/rust/bridge/shared/types/src/mesh.rs
// (`encode_event`, `encode_prepared`, `encode_stats`) and use meshlink's wire
// writer: fixed-width big-endian integers, 16-byte ids written raw, and
// variable fields as a u16 big-endian length followed by the bytes.

import { MeshEventTag } from './constants.std.ts';

export type Bytes16 = Uint8Array;

/** A link id crosses the bridge as u64; the generator may hand us either. */
export type LinkIdType = bigint | number;

/** Map key for a link id regardless of how the bridge represented it. */
export function linkKey(id: LinkIdType): string {
  return id.toString();
}

export class MeshWireError extends Error {
  constructor(message: string) {
    super(`mesh wire: ${message}`);
    this.name = 'MeshWireError';
  }
}

/** Mirror of meshlink's `wire::Reader`. */
export class WireReader {
  #data: Uint8Array;
  #pos = 0;

  constructor(data: Uint8Array) {
    this.#data = data;
  }

  get remaining(): number {
    return this.#data.length - this.#pos;
  }

  #take(n: number): Uint8Array {
    if (n > this.remaining) {
      throw new MeshWireError(`unexpected end of input (need ${n} bytes)`);
    }
    const out = this.#data.subarray(this.#pos, this.#pos + n);
    this.#pos += n;
    return out;
  }

  u8(): number {
    const [b] = this.#take(1);
    return b ?? 0;
  }

  u16(): number {
    const b = this.#take(2);
    return ((b[0] ?? 0) << 8) | (b[1] ?? 0);
  }

  u32(): number {
    const b = this.#take(4);
    return (
      ((b[0] ?? 0) * 0x1000000 +
        (((b[1] ?? 0) << 16) | ((b[2] ?? 0) << 8) | (b[3] ?? 0))) >>>
      0
    );
  }

  u64(): bigint {
    const b = this.#take(8);
    let value = 0n;
    for (const byte of b) {
      value = (value << 8n) | BigInt(byte);
    }
    return value;
  }

  /** `n` raw bytes (copied, so callers may keep them). */
  fixed(n: number): Uint8Array {
    return new Uint8Array(this.#take(n));
  }

  /** u16 length-prefixed bytes (copied). */
  bytes(): Uint8Array {
    const len = this.u16();
    return new Uint8Array(this.#take(len));
  }

  finish(): void {
    if (this.remaining !== 0) {
      throw new MeshWireError(`${this.remaining} trailing bytes`);
    }
  }
}

export type MeshEvent =
  | {
      kind: 'ciphertext';
      from: Bytes16;
      bundleId: Bytes16;
      ackCommit: Bytes16;
      messageType: number;
      ciphertext: Uint8Array;
    }
  | {
      kind: 'message';
      from: Bytes16;
      bundleId: Bytes16;
      knownSender: boolean;
      plaintext: Uint8Array;
    }
  | {
      kind: 'groupMessage';
      group: Bytes16;
      from: Bytes16;
      bundleId: Bytes16;
      plaintext: Uint8Array;
    }
  | { kind: 'groupInvite'; group: Bytes16; from: Bytes16 }
  | { kind: 'contact'; fingerprint: Bytes16 }
  | { kind: 'delivered'; bundleId: Bytes16 }
  | { kind: 'neighbour'; link: bigint; fingerprint: Bytes16 }
  | { kind: 'linkClosed'; link: bigint };

/**
 * Decodes one event from `MeshNode_NextEvent`. An empty buffer means the
 * timeout elapsed with nothing new and yields `undefined`.
 */
export function decodeMeshEvent(data: Uint8Array): MeshEvent | undefined {
  if (data.length === 0) {
    return undefined;
  }
  const r = new WireReader(data);
  const tag = r.u8();
  let event: MeshEvent;
  switch (tag) {
    case MeshEventTag.Ciphertext:
      event = {
        kind: 'ciphertext',
        from: r.fixed(16),
        bundleId: r.fixed(16),
        ackCommit: r.fixed(16),
        messageType: r.u8(),
        ciphertext: r.bytes(),
      };
      break;
    case MeshEventTag.Message:
      event = {
        kind: 'message',
        from: r.fixed(16),
        bundleId: r.fixed(16),
        knownSender: r.u8() !== 0,
        plaintext: r.bytes(),
      };
      break;
    case MeshEventTag.GroupMessage:
      event = {
        kind: 'groupMessage',
        group: r.fixed(16),
        from: r.fixed(16),
        bundleId: r.fixed(16),
        plaintext: r.bytes(),
      };
      break;
    case MeshEventTag.GroupInvite:
      event = { kind: 'groupInvite', group: r.fixed(16), from: r.fixed(16) };
      break;
    case MeshEventTag.Contact:
      event = { kind: 'contact', fingerprint: r.fixed(16) };
      break;
    case MeshEventTag.Delivered:
      event = { kind: 'delivered', bundleId: r.fixed(16) };
      break;
    case MeshEventTag.Neighbour:
      event = { kind: 'neighbour', link: r.u64(), fingerprint: r.fixed(16) };
      break;
    case MeshEventTag.LinkClosed:
      event = { kind: 'linkClosed', link: r.u64() };
      break;
    default:
      throw new MeshWireError(`unknown event tag ${tag}`);
  }
  r.finish();
  return event;
}

export type MeshPrepared = Readonly<{
  to: Bytes16;
  commit: Bytes16;
  plaintext: Uint8Array;
}>;

/** `[count u16]` then per item `[to 16][commit 16][plaintext u16-len]`. */
export function decodeMeshPrepared(data: Uint8Array): Array<MeshPrepared> {
  const r = new WireReader(data);
  const count = r.u16();
  const items: Array<MeshPrepared> = [];
  for (let i = 0; i < count; i += 1) {
    items.push({ to: r.fixed(16), commit: r.fixed(16), plaintext: r.bytes() });
  }
  r.finish();
  return items;
}

/** `[group id 16][prepared list]` from `MeshNode_PrepareGroupCreate`. */
export function decodeMeshGroupCreate(data: Uint8Array): {
  groupId: Bytes16;
  prepared: Array<MeshPrepared>;
} {
  if (data.length < 16) {
    throw new MeshWireError('group create result too short');
  }
  return {
    groupId: new Uint8Array(data.subarray(0, 16)),
    prepared: decodeMeshPrepared(data.subarray(16)),
  };
}

export type MeshStats = Readonly<{
  framesIn: bigint;
  framesDroppedRate: bigint;
  framesDroppedInvalid: bigint;
  bundlesIn: bigint;
  bundlesDroppedInvalid: bigint;
  bundlesDroppedRate: bigint;
  bundlesDroppedQuota: bigint;
  bundlesForwarded: bigint;
  messagesDelivered: bigint;
  messagesUndecryptable: bigint;
  messagesDeferred: bigint;
  acksRejected: bigint;
  acksVerified: bigint;
  bytesOut: bigint;
  links: bigint;
  storeBundles: bigint;
  storeBytes: bigint;
  outstanding: bigint;
}>;

const STATS_FIELDS: ReadonlyArray<keyof MeshStats> = [
  'framesIn',
  'framesDroppedRate',
  'framesDroppedInvalid',
  'bundlesIn',
  'bundlesDroppedInvalid',
  'bundlesDroppedRate',
  'bundlesDroppedQuota',
  'bundlesForwarded',
  'messagesDelivered',
  'messagesUndecryptable',
  'messagesDeferred',
  'acksRejected',
  'acksVerified',
  'bytesOut',
  'links',
  'storeBundles',
  'storeBytes',
  'outstanding',
];

export function emptyMeshStats(): MeshStats {
  const out: Record<string, bigint> = {};
  for (const field of STATS_FIELDS) {
    out[field] = 0n;
  }
  return out as unknown as MeshStats;
}

/**
 * Big-endian u64 counters in the order of the Rust `Stats` fields. A newer
 * crate may append counters; extra trailing fields are ignored and missing
 * ones read as zero so the UI never breaks on a version skew.
 */
export function decodeMeshStats(data: Uint8Array): MeshStats {
  const r = new WireReader(data);
  const out: Record<string, bigint> = {
    ...(emptyMeshStats() as unknown as Record<string, bigint>),
  };
  for (const field of STATS_FIELDS) {
    if (r.remaining < 8) {
      break;
    }
    out[field] = r.u64();
  }
  return out as unknown as MeshStats;
}
