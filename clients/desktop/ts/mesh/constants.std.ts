// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Constants shared by every part of the Desktop meshlink integration. The
// wire-level values (event tags, KISS bytes, frame limits) mirror the Rust
// crate in libs/libsignal/rust/meshlink and the bridge in
// libs/libsignal/rust/bridge/shared/types/src/mesh.rs; change them together.

/** Remote-config / local-override feature flag. Off by default. */
export const MESH_TRANSPORT_FLAG = 'mesh.transport' as const;

/**
 * GATT service used by every platform for phone-to-phone and phone-to-desktop
 * links. All three clients (Android, iOS, Desktop) must use the same values:
 *   - service:                    6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e1
 *   - inbound (write w/o resp.):  6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e2
 *   - outbound (notify):          6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e3
 * "Inbound" and "outbound" are from the peripheral's point of view: a central
 * writes frames to c0e2 and subscribes to c0e3 for frames coming back.
 */
export const MESH_BLE_SERVICE_UUID = '6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e1';
export const MESH_BLE_INBOUND_CHARACTERISTIC_UUID =
  '6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e2';
export const MESH_BLE_OUTBOUND_CHARACTERISTIC_UUID =
  '6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e3';

/** Largest frame the node accepts (meshlink `node::MAX_FRAME_LEN`). */
export const MESH_MAX_FRAME_LEN = 8192;

/** Mesh contact cards always describe device 1 (`identity.rs`). */
export const MESH_DEVICE_ID = 1;

/**
 * `LinkOptions` presets from meshlink/src/transport/mod.rs, as the three
 * numbers `MeshNode_AttachLink` takes: mtu, bytes/s, frames/s.
 */
export type LinkOptionsType = Readonly<{
  mtu: number;
  maxBytesPerSec: number;
  maxFramesPerSec: number;
}>;

/** `LinkOptions::new(1500)`: what `meshlinkd` gateways and LAN peers use. */
export const LINK_OPTIONS_TCP: LinkOptionsType = {
  mtu: 1500,
  maxBytesPerSec: 256 * 1024,
  maxFramesPerSec: 500,
};

/** `LinkOptions::lora(200)`: an RNode-class board at SF10 or below. */
export const LINK_OPTIONS_LORA: LinkOptionsType = {
  mtu: 200,
  maxBytesPerSec: 200,
  maxFramesPerSec: 20,
};

/**
 * `LinkOptions::ble(mtu)`. 244 bytes is one ATT write at the 247-byte MTU
 * every BLE 4.2+ stack negotiates; Web Bluetooth caps a write at 512.
 */
export const LINK_OPTIONS_BLE: LinkOptionsType = {
  mtu: 244,
  maxBytesPerSec: 16 * 1024,
  maxFramesPerSec: 200,
};

/** Event tags on the bridge (`types/src/mesh.rs`, `event_tag`). */
export const MeshEventTag = {
  Ciphertext: 1,
  Message: 2,
  GroupMessage: 3,
  GroupInvite: 4,
  Contact: 5,
  Delivered: 6,
  Neighbour: 7,
  LinkClosed: 8,
} as const;

/** libsignal ciphertext types carried in a message bundle. */
export const MESH_MESSAGE_TYPE_WHISPER = 2;
export const MESH_MESSAGE_TYPE_PREKEY = 3;

/** Anti-entropy interval handed to `MeshNode_New`; 0 keeps the crate default. */
export const MESH_ANTI_ENTROPY_SECS = 0;

/**
 * Polling cadence. The Native poll calls are made with a zero timeout so they
 * never block the preload thread (see MeshService for the reasoning).
 */
export const MESH_POLL_INTERVAL_MS = 100;
export const MESH_MAX_EVENTS_PER_TICK = 64;
export const MESH_MAX_FRAMES_PER_LINK_PER_TICK = 32;

/** Folder under userData that holds the node's persisted carry store. */
export const MESH_STATE_DIRNAME = 'mesh';

/** Default LAN port when the user turns on listening. */
export const MESH_DEFAULT_TCP_PORT = 48120;

/**
 * IPC channels between the preload (where libsignal and the node live) and
 * the main process (where sockets and device choosers live). Every
 * renderer->main channel below is guarded with `assertPrivilegedSender`
 * in app/mesh_channel.main.ts.
 */
export const MeshIpc = {
  // renderer -> main (invoke)
  TcpConnect: 'mesh:tcp:connect',
  TcpListen: 'mesh:tcp:listen',
  TcpStopListening: 'mesh:tcp:stop-listening',
  TcpClose: 'mesh:tcp:close',
  DevicesEnable: 'mesh:devices:enable',
  // renderer -> main (send)
  TcpSend: 'mesh:tcp:send',
  SerialPick: 'mesh:serial:pick',
  BluetoothPick: 'mesh:bluetooth:pick',
  // main -> renderer
  TcpFrame: 'mesh:tcp:frame',
  TcpClosed: 'mesh:tcp:closed',
  TcpAccepted: 'mesh:tcp:accepted',
  SerialPorts: 'mesh:serial:ports',
  BluetoothDevices: 'mesh:bluetooth:devices',
} as const;
