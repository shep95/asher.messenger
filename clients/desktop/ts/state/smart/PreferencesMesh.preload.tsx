// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { memo, useCallback, useMemo } from 'react';
import { useSelector } from 'react-redux';

import { PreferencesMesh } from '../../components/PreferencesMesh.dom.tsx';
import { isMeshTransportEnabledFromItems } from '../../mesh/isMeshTransportEnabled.preload.ts';
import { meshService } from '../../mesh/MeshService.preload.ts';
import { itemStorage } from '../../textsecure/Storage.preload.ts';
import { drop } from '../../util/drop.std.ts';
import { getItems } from '../selectors/items.dom.ts';
import { getMesh, getMeshContacts } from '../selectors/mesh.std.ts';

import type { JSX } from 'react';

export const SmartPreferencesMesh = memo(function SmartPreferencesMesh(): JSX.Element {
  const items = useSelector(getItems);
  const mesh = useSelector(getMesh);
  const contacts = useSelector(getMeshContacts);

  const isEnabled = isMeshTransportEnabledFromItems(items);
  const links = useMemo(() => Object.values(mesh.links), [mesh.links]);
  const neighbourCount = Object.keys(mesh.neighbours).length;

  const onToggle = useCallback((enabled: boolean) => {
    drop(meshService.setEnabled(enabled));
  }, []);

  const onGatewayAddressChange = useCallback((value: string) => {
    drop(itemStorage.put('meshGatewayAddress', value || undefined));
  }, []);

  const onConnectGateway = useCallback(async () => {
    const address = itemStorage.get('meshGatewayAddress');
    if (address) {
      await meshService.connectGateway(address);
    }
  }, []);

  const onListenPortChange = useCallback((value: number | undefined) => {
    drop(itemStorage.put('meshListenPort', value));
    if (value) {
      drop(meshService.listen(value));
    } else {
      drop(meshService.stopListening());
    }
  }, []);

  const onAddCard = useCallback(async (cardBase64: string) => {
    await meshService.addContactFromBase64(cardBase64);
  }, []);

  const onOpenRadio = useCallback(() => meshService.openRadio(), []);
  const onOpenBluetooth = useCallback(() => meshService.openBluetooth(), []);
  const onPickSerialPort = useCallback((id: string | null) => {
    meshService.pickSerialPort(id);
  }, []);
  const onPickBluetoothDevice = useCallback((id: string | null) => {
    meshService.pickBluetoothDevice(id);
  }, []);

  return (
    <PreferencesMesh
      isEnabled={isEnabled}
      isRunning={mesh.isRunning}
      onToggle={onToggle}
      fingerprintHex={mesh.fingerprintHex}
      cardBase64={mesh.cardBase64}
      contacts={contacts}
      links={links}
      neighbourCount={neighbourCount}
      stats={mesh.stats}
      gatewayAddress={items.meshGatewayAddress ?? ''}
      onGatewayAddressChange={onGatewayAddressChange}
      onConnectGateway={onConnectGateway}
      listenPort={items.meshListenPort}
      onListenPortChange={onListenPortChange}
      onAddCard={onAddCard}
      onOpenRadio={onOpenRadio}
      onOpenBluetooth={onOpenBluetooth}
      serialPortChoices={mesh.serialPortChoices}
      bluetoothDeviceChoices={mesh.bluetoothDeviceChoices}
      onPickSerialPort={onPickSerialPort}
      onPickBluetoothDevice={onPickBluetoothDevice}
      lastError={mesh.lastError}
    />
  );
});
