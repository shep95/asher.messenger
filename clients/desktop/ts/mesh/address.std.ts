// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// How a mesh fingerprint becomes something Desktop's conversation and
// protocol stores can hold.
//
// Convention (see docs/offline-mesh.md and the mesh_contacts table): a mesh
// contact is a private conversation whose `serviceId` is the fingerprint's
// 16 bytes rendered as a UUID string. Desktop only accepts v4/v7-shaped
// UUIDs as service ids (`ts/util/isValidUuid.std.ts`, enforced by
// `validateConversation`, which refuses to save anything else), so the
// rendering here forces the RFC 4122 version nibble to 4 and the variant bits
// to `10`. That costs six bits of the fingerprint, which is why the reverse
// mapping (serviceId -> fingerprint) is never computed from the string: it is
// always looked up in `mesh_contacts` (MeshContacts). The 32-hex fingerprint
// itself is what people compare and what the Rust node addresses.
//
// The same serviceId names the libsignal `ProtocolAddress` (device 1) under
// which the contact's Signal session, identity key and prekeys live, because
// Desktop's session store keys everything by ServiceIdString.

import { MESH_DEVICE_ID } from './constants.std.ts';

const HEX = '0123456789abcdef';

export function bytesToHex(bytes: Uint8Array): string {
  let out = '';
  for (const b of bytes) {
    out += HEX[b >> 4];
    out += HEX[b & 0x0f];
  }
  return out;
}

export function hexToBytes(hex: string): Uint8Array {
  const clean = hex.trim().toLowerCase();
  if (clean.length % 2 !== 0 || /[^0-9a-f]/.test(clean)) {
    throw new Error('hexToBytes: not hex');
  }
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i += 1) {
    out[i] = parseInt(clean.substring(i * 2, i * 2 + 2), 16);
  }
  return out;
}

/** 32 lowercase hex characters: the address meshlink shows and routes on. */
export function fingerprintToHex(fingerprint: Uint8Array): string {
  if (fingerprint.length !== 16) {
    throw new Error('fingerprintToHex: fingerprint must be 16 bytes');
  }
  return bytesToHex(fingerprint);
}

export function fingerprintFromHex(hex: string): Uint8Array {
  const bytes = hexToBytes(hex);
  if (bytes.length !== 16) {
    throw new Error('fingerprintFromHex: fingerprint must be 16 bytes');
  }
  return bytes;
}

/**
 * The conversation serviceId for a fingerprint: its bytes as a v4-shaped
 * UUID (see the file comment for why the version/variant bits are forced).
 */
export function fingerprintToMeshServiceId(fingerprint: Uint8Array): string {
  if (fingerprint.length !== 16) {
    throw new Error('fingerprintToMeshServiceId: fingerprint must be 16 bytes');
  }
  const b = new Uint8Array(fingerprint);
  b[6] = ((b[6] ?? 0) & 0x0f) | 0x40;
  b[8] = ((b[8] ?? 0) & 0x3f) | 0x80;
  const hex = bytesToHex(b);
  return [
    hex.substring(0, 8),
    hex.substring(8, 12),
    hex.substring(12, 16),
    hex.substring(16, 20),
    hex.substring(20, 32),
  ].join('-');
}

/** `ProtocolAddress` name and device id for a mesh contact. */
export function meshProtocolAddressParts(fingerprint: Uint8Array): {
  name: string;
  deviceId: number;
} {
  return {
    name: fingerprintToMeshServiceId(fingerprint),
    deviceId: MESH_DEVICE_ID,
  };
}

export function fingerprintsEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) {
    return false;
  }
  for (let i = 0; i < a.length; i += 1) {
    if (a[i] !== b[i]) {
      return false;
    }
  }
  return true;
}
