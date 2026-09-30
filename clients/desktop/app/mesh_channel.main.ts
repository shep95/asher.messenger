// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Main-process half of the offline mesh transport (docs/offline-mesh.md).
//
// The meshlink node lives in the preload with libsignal; sockets and device
// choosers live here because only the main process can open TCP sockets
// without Node integration in the renderer and only it receives Electron's
// `select-serial-port` / `select-bluetooth-device` events. Frames cross over
// dedicated `mesh:*` IPC channels (ts/mesh/constants.std.ts). Every handler
// that a renderer can invoke is restricted to the main window with
// `assertPrivilegedSender`, like the other profile-affecting channels, and
// every message we send goes only to that window.

import { ipcMain } from 'electron';
import type {
  IpcMainEvent,
  IpcMainInvokeEvent,
  Session,
  WebContents,
} from 'electron';
import { createServer, Socket } from 'node:net';
import type { Server } from 'node:net';

import { assertPrivilegedSender } from './ipcSenderGuard.main.ts';
import { createLogger } from '../ts/logging/log.std.ts';
import { MESH_MAX_FRAME_LEN, MeshIpc } from '../ts/mesh/constants.std.ts';
import {
  encodeTcpFrame,
  TcpFrameDecoder,
} from '../ts/mesh/tcpFraming.std.ts';

const log = createLogger('mesh_channel');

const MAX_SOCKETS = 16;
const MAX_LISTENER_PEERS = 8;
const CONNECT_TIMEOUT_MS = 15_000;

type SendToRenderer = (channel: string, ...args: Array<unknown>) => void;
type GetWebContents = () => WebContents | undefined;

type MeshSocket = {
  id: number;
  socket: Socket;
  decoder: TcpFrameDecoder;
};

let send: SendToRenderer | undefined;
let getWebContents: GetWebContents | undefined;
let initialized = false;

const sockets = new Map<number, MeshSocket>();
let nextSocketId = 1;
let listener: Server | undefined;

function requireSend(): SendToRenderer {
  if (!send) {
    throw new Error('mesh_channel: not initialized');
  }
  return send;
}

function closeSocket(id: number, reason: string): void {
  const entry = sockets.get(id);
  if (!entry) {
    return;
  }
  sockets.delete(id);
  entry.socket.destroy();
  requireSend()(MeshIpc.TcpClosed, id, reason);
}

function register(socket: Socket): number {
  const id = nextSocketId;
  nextSocketId += 1;
  const entry: MeshSocket = { id, socket, decoder: new TcpFrameDecoder() };
  sockets.set(id, entry);

  socket.setNoDelay(true);
  socket.on('data', (chunk: Buffer) => {
    let frames: Array<Uint8Array>;
    try {
      frames = entry.decoder.feed(new Uint8Array(chunk));
    } catch (error) {
      log.warn(`socket ${id}: bad framing, closing: ${error}`);
      closeSocket(id, 'framing');
      return;
    }
    for (const frame of frames) {
      requireSend()(MeshIpc.TcpFrame, id, frame);
    }
  });
  socket.on('error', (error: Error) => {
    log.warn(`socket ${id}: ${error.message}`);
  });
  socket.on('close', () => {
    if (sockets.has(id)) {
      sockets.delete(id);
      requireSend()(MeshIpc.TcpClosed, id, 'closed');
    }
  });
  return id;
}

function isValidPort(port: unknown): port is number {
  return Number.isInteger(port) && (port as number) > 0 && (port as number) < 65536;
}

function isValidHost(host: unknown): host is string {
  return (
    typeof host === 'string' &&
    host.length > 0 &&
    host.length < 256 &&
    /^[A-Za-z0-9.:_[\]-]+$/.test(host)
  );
}

async function connect(host: string, port: number): Promise<number> {
  if (sockets.size >= MAX_SOCKETS) {
    throw new Error('mesh: too many links');
  }
  return new Promise<number>((resolve, reject) => {
    const socket = new Socket();
    const onError = (error: Error) => {
      socket.destroy();
      reject(error);
    };
    socket.setTimeout(CONNECT_TIMEOUT_MS, () => {
      onError(new Error('mesh: connect timed out'));
    });
    socket.once('error', onError);
    socket.connect(port, host, () => {
      socket.off('error', onError);
      socket.setTimeout(0);
      resolve(register(socket));
    });
  });
}

function listen(port: number): void {
  if (listener) {
    return;
  }
  const server = createServer(socket => {
    if (sockets.size >= MAX_SOCKETS) {
      log.warn('listener: refusing peer, socket limit reached');
      socket.destroy();
      return;
    }
    const id = register(socket);
    requireSend()(
      MeshIpc.TcpAccepted,
      id,
      `${socket.remoteAddress ?? '?'}:${socket.remotePort ?? 0}`
    );
  });
  server.maxConnections = MAX_LISTENER_PEERS;
  server.on('error', (error: Error) => {
    log.warn(`listener: ${error.message}`);
  });
  server.listen(port);
  listener = server;
}

function stopListening(): void {
  listener?.close();
  listener = undefined;
}

// ---- Device choosers (Web Serial / Web Bluetooth in the renderer) --------

let devicesEnabled = false;
let pendingSerialCallback: ((portId: string) => void) | undefined;
let pendingBluetoothCallback: ((deviceId: string) => void) | undefined;

function enableDeviceChoosers(webContents: WebContents): void {
  if (devicesEnabled) {
    return;
  }
  devicesEnabled = true;
  const session: Session = webContents.session;

  // Web Serial and Web Bluetooth are permission-checked, not requested. Only
  // the main window may use them; everything else keeps Electron's default
  // (allowed), which is what applied before this handler existed.
  session.setPermissionCheckHandler((requestingContents, permission) => {
    if (permission === 'serial' || permission === 'bluetooth') {
      return requestingContents != null && requestingContents.id === webContents.id;
    }
    return true;
  });

  // Never auto-grant a device: the renderer picks from the list we forward
  // and we answer Electron with exactly that id.
  session.on(
    'select-serial-port',
    (event, portList, requestingContents, callback) => {
      event.preventDefault();
      if (requestingContents.id !== webContents.id) {
        callback('');
        return;
      }
      pendingSerialCallback = callback;
      requireSend()(
        MeshIpc.SerialPorts,
        portList.map(port => ({
          id: port.portId,
          label: port.displayName || port.portName || port.portId,
        }))
      );
    }
  );
  session.on('serial-port-added', (_event, port) => {
    if (pendingSerialCallback) {
      requireSend()(MeshIpc.SerialPorts, [
        { id: port.portId, label: port.displayName || port.portName || port.portId },
      ]);
    }
  });

  webContents.on('select-bluetooth-device', (event, deviceList, callback) => {
    event.preventDefault();
    pendingBluetoothCallback = callback;
    requireSend()(
      MeshIpc.BluetoothDevices,
      deviceList.map(device => ({
        id: device.deviceId,
        label: device.deviceName || device.deviceId,
      }))
    );
  });
}

// ---- IPC ------------------------------------------------------------------

export function initialize(options: {
  send: SendToRenderer;
  getWebContents: GetWebContents;
}): void {
  if (initialized) {
    throw new Error('mesh_channel: already initialized!');
  }
  initialized = true;
  send = options.send;
  getWebContents = options.getWebContents;

  ipcMain.handle(
    MeshIpc.TcpConnect,
    async (event: IpcMainInvokeEvent, host: unknown, port: unknown) => {
      assertPrivilegedSender(event, MeshIpc.TcpConnect);
      if (!isValidHost(host) || !isValidPort(port)) {
        throw new Error('mesh: invalid gateway address');
      }
      const id = await connect(host, port);
      log.info(`connected socket ${id}`);
      return id;
    }
  );

  ipcMain.handle(
    MeshIpc.TcpListen,
    (event: IpcMainInvokeEvent, port: unknown) => {
      assertPrivilegedSender(event, MeshIpc.TcpListen);
      if (!isValidPort(port)) {
        throw new Error('mesh: invalid listen port');
      }
      listen(port);
    }
  );

  ipcMain.handle(MeshIpc.TcpStopListening, (event: IpcMainInvokeEvent) => {
    assertPrivilegedSender(event, MeshIpc.TcpStopListening);
    stopListening();
  });

  ipcMain.handle(
    MeshIpc.TcpClose,
    (event: IpcMainInvokeEvent, id: unknown) => {
      assertPrivilegedSender(event, MeshIpc.TcpClose);
      if (typeof id === 'number') {
        closeSocket(id, 'requested');
      }
    }
  );

  ipcMain.on(
    MeshIpc.TcpSend,
    (event: IpcMainEvent, id: unknown, frame: unknown) => {
      assertPrivilegedSender(event, MeshIpc.TcpSend);
      if (typeof id !== 'number' || !(frame instanceof Uint8Array)) {
        return;
      }
      if (frame.length === 0 || frame.length > MESH_MAX_FRAME_LEN) {
        log.warn(`socket ${id}: dropping frame of ${frame.length} bytes`);
        return;
      }
      const entry = sockets.get(id);
      if (!entry) {
        return;
      }
      entry.socket.write(encodeTcpFrame(frame));
    }
  );

  ipcMain.handle(MeshIpc.DevicesEnable, (event: IpcMainInvokeEvent) => {
    assertPrivilegedSender(event, MeshIpc.DevicesEnable);
    const webContents = getWebContents?.();
    if (!webContents) {
      throw new Error('mesh: main window not ready');
    }
    enableDeviceChoosers(webContents);
  });

  ipcMain.on(MeshIpc.SerialPick, (event: IpcMainEvent, portId: unknown) => {
    assertPrivilegedSender(event, MeshIpc.SerialPick);
    const callback = pendingSerialCallback;
    pendingSerialCallback = undefined;
    callback?.(typeof portId === 'string' ? portId : '');
  });

  ipcMain.on(
    MeshIpc.BluetoothPick,
    (event: IpcMainEvent, deviceId: unknown) => {
      assertPrivilegedSender(event, MeshIpc.BluetoothPick);
      const callback = pendingBluetoothCallback;
      pendingBluetoothCallback = undefined;
      callback?.(typeof deviceId === 'string' ? deviceId : '');
    }
  );
}

/** Closes everything; called when the main window goes away. */
export function shutdown(): void {
  stopListening();
  for (const id of Array.from(sockets.keys())) {
    const entry = sockets.get(id);
    sockets.delete(id);
    entry?.socket.destroy();
  }
}
