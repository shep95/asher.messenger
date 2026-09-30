// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Renderer side of the `mesh:*` IPC channels (see app/mesh_channel.main.ts).

import { ipcRenderer } from 'electron';
import type { IpcRendererEvent } from 'electron';

import { MeshIpc } from './constants.std.ts';
import type { MeshDeviceChoiceType } from '../state/ducks/mesh.std.ts';

export type MeshTcpEvents = Readonly<{
  onFrame: (socketId: number, frame: Uint8Array) => void;
  onClosed: (socketId: number, reason: string) => void;
  onAccepted: (socketId: number, peer: string) => void;
}>;

/** A peer advertised on the LAN (app/mesh_channel.main.ts, mDNS). */
export type MeshLanPeerType = Readonly<{
  fingerprintHex: string;
  port: number;
  host: string;
  /** Addresses to try, IPv4 first; the datagram's source when none advertised. */
  addresses: ReadonlyArray<string>;
  from: string;
}>;

export type MeshDeviceEvents = Readonly<{
  onSerialPorts: (choices: ReadonlyArray<MeshDeviceChoiceType>) => void;
  onBluetoothDevices: (choices: ReadonlyArray<MeshDeviceChoiceType>) => void;
}>;

export function tcpConnect(host: string, port: number): Promise<number> {
  return ipcRenderer.invoke(MeshIpc.TcpConnect, host, port);
}

export function tcpListen(port: number): Promise<void> {
  return ipcRenderer.invoke(MeshIpc.TcpListen, port);
}

export function tcpStopListening(): Promise<void> {
  return ipcRenderer.invoke(MeshIpc.TcpStopListening);
}

export function tcpClose(socketId: number): Promise<void> {
  return ipcRenderer.invoke(MeshIpc.TcpClose, socketId);
}

export function tcpSend(socketId: number, frame: Uint8Array): void {
  ipcRenderer.send(MeshIpc.TcpSend, socketId, frame);
}

/** Returns an unsubscribe function. */
export function subscribeTcp(events: MeshTcpEvents): () => void {
  const onFrame = (_event: IpcRendererEvent, id: number, frame: Uint8Array) =>
    events.onFrame(id, new Uint8Array(frame));
  const onClosed = (_event: IpcRendererEvent, id: number, reason: string) =>
    events.onClosed(id, reason);
  const onAccepted = (_event: IpcRendererEvent, id: number, peer: string) =>
    events.onAccepted(id, peer);
  ipcRenderer.on(MeshIpc.TcpFrame, onFrame);
  ipcRenderer.on(MeshIpc.TcpClosed, onClosed);
  ipcRenderer.on(MeshIpc.TcpAccepted, onAccepted);
  return () => {
    ipcRenderer.off(MeshIpc.TcpFrame, onFrame);
    ipcRenderer.off(MeshIpc.TcpClosed, onClosed);
    ipcRenderer.off(MeshIpc.TcpAccepted, onAccepted);
  };
}

/**
 * Starts the mDNS responder/querier. `port` is our TCP listen port; without
 * it we only browse. Rejects when the multicast socket cannot be bound.
 */
export function mdnsStart(
  fingerprintHex: string,
  port: number | undefined
): Promise<void> {
  return ipcRenderer.invoke(MeshIpc.MdnsStart, fingerprintHex, port ?? null);
}

export function mdnsStop(): Promise<void> {
  return ipcRenderer.invoke(MeshIpc.MdnsStop);
}

export function subscribeMdns(
  onPeer: (peer: MeshLanPeerType) => void
): () => void {
  const handler = (_event: IpcRendererEvent, peer: MeshLanPeerType) =>
    onPeer(peer);
  ipcRenderer.on(MeshIpc.MdnsPeer, handler);
  return () => {
    ipcRenderer.off(MeshIpc.MdnsPeer, handler);
  };
}

/** Save dialog + write; resolves with the path, or undefined when cancelled. */
export async function saveBackupFile(
  bytes: Uint8Array,
  defaultName: string
): Promise<string | undefined> {
  const result: { canceled: true } | { canceled: false; filePath: string } =
    await ipcRenderer.invoke(MeshIpc.BackupSave, bytes, defaultName);
  return result.canceled ? undefined : result.filePath;
}

/** Open dialog + read; resolves with the bytes, or undefined when cancelled. */
export async function openBackupFile(): Promise<
  { filePath: string; bytes: Uint8Array } | undefined
> {
  const result:
    | { canceled: true }
    | { canceled: false; filePath: string; bytes: Uint8Array } =
    await ipcRenderer.invoke(MeshIpc.BackupOpen);
  return result.canceled
    ? undefined
    : { filePath: result.filePath, bytes: new Uint8Array(result.bytes) };
}

/** Installs the serial/bluetooth choosers in main for this window. */
export function enableDeviceChoosers(): Promise<void> {
  return ipcRenderer.invoke(MeshIpc.DevicesEnable);
}

export function pickSerialPort(portId: string | null): void {
  ipcRenderer.send(MeshIpc.SerialPick, portId ?? '');
}

export function pickBluetoothDevice(deviceId: string | null): void {
  ipcRenderer.send(MeshIpc.BluetoothPick, deviceId ?? '');
}

export function subscribeDevices(events: MeshDeviceEvents): () => void {
  const onSerial = (
    _event: IpcRendererEvent,
    choices: ReadonlyArray<MeshDeviceChoiceType>
  ) => events.onSerialPorts(choices);
  const onBluetooth = (
    _event: IpcRendererEvent,
    choices: ReadonlyArray<MeshDeviceChoiceType>
  ) => events.onBluetoothDevices(choices);
  ipcRenderer.on(MeshIpc.SerialPorts, onSerial);
  ipcRenderer.on(MeshIpc.BluetoothDevices, onBluetooth);
  return () => {
    ipcRenderer.off(MeshIpc.SerialPorts, onSerial);
    ipcRenderer.off(MeshIpc.BluetoothDevices, onBluetooth);
  };
}
