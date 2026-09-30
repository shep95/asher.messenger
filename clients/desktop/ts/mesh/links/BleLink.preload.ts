// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// A phone (or another desktop acting as a peripheral) over Web Bluetooth.
// Desktop is always the GATT central: it writes frames to the peer's inbound
// characteristic without response and receives frames as notifications on
// the outbound one. One write / one notification carries exactly one frame,
// so the link MTU is the largest write we make (LINK_OPTIONS_BLE.mtu).
// `navigator.bluetooth.requestDevice` makes main emit `select-bluetooth-device`;
// main forwards the candidates to Preferences > Mesh and answers with the
// user's pick (app/mesh_channel.main.ts). The UUIDs are shared by all
// platforms (ts/mesh/constants.std.ts).

import { createLogger } from '../../logging/log.std.ts';
import {
  LINK_OPTIONS_BLE,
  MESH_BLE_INBOUND_CHARACTERISTIC_UUID,
  MESH_BLE_OUTBOUND_CHARACTERISTIC_UUID,
  MESH_BLE_SERVICE_UUID,
} from '../constants.std.ts';
import type { AttachedLink, LinkHost } from './Link.std.ts';

const log = createLogger('MeshBleLink');

// Web Bluetooth is not in TypeScript's DOM lib; these are the pieces we touch.
type CharacteristicLike = {
  writeValueWithoutResponse: (value: BufferSource) => Promise<void>;
  startNotifications: () => Promise<CharacteristicLike>;
  stopNotifications: () => Promise<CharacteristicLike>;
  addEventListener: (
    type: 'characteristicvaluechanged',
    listener: (event: Event) => void
  ) => void;
  readonly value: DataView | null;
};
type ServiceLike = {
  getCharacteristic: (uuid: string) => Promise<CharacteristicLike>;
};
type GattServerLike = {
  connected: boolean;
  connect: () => Promise<GattServerLike>;
  disconnect: () => void;
  getPrimaryService: (uuid: string) => Promise<ServiceLike>;
};
type DeviceLike = {
  id: string;
  name?: string;
  gatt?: GattServerLike;
  addEventListener: (type: 'gattserverdisconnected', listener: () => void) => void;
};
type BluetoothLike = {
  requestDevice: (options: {
    filters: Array<{ services: Array<string> }>;
    optionalServices?: Array<string>;
  }) => Promise<DeviceLike>;
};

function getBluetooth(): BluetoothLike | undefined {
  return (navigator as unknown as { bluetooth?: BluetoothLike }).bluetooth;
}

export function isWebBluetoothAvailable(): boolean {
  return getBluetooth() != null;
}

/** Asks the user for a device advertising the mesh service and attaches it. */
export async function openBleLink(host: LinkHost): Promise<AttachedLink> {
  const bluetooth = getBluetooth();
  if (!bluetooth) {
    throw new Error('Web Bluetooth is not available in this window');
  }
  const device = await bluetooth.requestDevice({
    filters: [{ services: [MESH_BLE_SERVICE_UUID] }],
  });
  if (!device.gatt) {
    throw new Error('device has no GATT server');
  }
  const server = await device.gatt.connect();
  const service = await server.getPrimaryService(MESH_BLE_SERVICE_UUID);
  const inbound = await service.getCharacteristic(
    MESH_BLE_INBOUND_CHARACTERISTIC_UUID
  );
  const outbound = await service.getCharacteristic(
    MESH_BLE_OUTBOUND_CHARACTERISTIC_UUID
  );

  let closed = false;
  const link = host.attachLink(
    {
      kind: 'ble',
      label: `bluetooth ${device.name ?? device.id}`,
      send: async frame => {
        try {
          // Fresh copy: the bridge may hand us a view into a pooled buffer.
          await inbound.writeValueWithoutResponse(new Uint8Array(frame));
        } catch (error) {
          log.warn(`write failed: ${error}`);
        }
      },
      close: async () => {
        if (closed) {
          return;
        }
        closed = true;
        try {
          await outbound.stopNotifications();
        } catch {
          // Already gone.
        }
        if (server.connected) {
          server.disconnect();
        }
      },
    },
    LINK_OPTIONS_BLE
  );

  outbound.addEventListener('characteristicvaluechanged', event => {
    const target = event.target as unknown as CharacteristicLike | null;
    const value = target?.value ?? outbound.value;
    if (!value || value.byteLength === 0) {
      return;
    }
    link.deliver(
      new Uint8Array(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength))
    );
  });
  await outbound.startNotifications();

  device.addEventListener('gattserverdisconnected', () => {
    log.info('device disconnected');
    link.detach();
  });

  return link;
}
