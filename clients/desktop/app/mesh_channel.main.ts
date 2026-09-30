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

import { BrowserWindow, dialog, ipcMain } from 'electron';
import type {
  IpcMainEvent,
  IpcMainInvokeEvent,
  Session,
  WebContents,
} from 'electron';
import { createSocket } from 'node:dgram';
import type { RemoteInfo, Socket as DgramSocket } from 'node:dgram';
import { readFile, writeFile } from 'node:fs/promises';
import { createServer, Socket } from 'node:net';
import type { Server } from 'node:net';
import { networkInterfaces } from 'node:os';
import { basename, extname } from 'node:path';

import { assertPrivilegedSender } from './ipcSenderGuard.main.ts';
import { createLogger } from '../ts/logging/log.std.ts';
import {
  MESH_MAX_FRAME_LEN,
  MESH_MDNS_ANNOUNCE_INTERVAL_MS,
  MESH_MDNS_GROUP,
  MESH_MDNS_PORT,
  MESH_MDNS_QUERY_INTERVAL_MS,
  MeshIpc,
} from '../ts/mesh/constants.std.ts';
import {
  decodeDnsMessage,
  encodeMeshAnnouncement,
  encodeMeshQuery,
  isMeshQuery,
  parseMeshPeers,
} from '../ts/mesh/mdns.std.ts';
import type { DnsMessage } from '../ts/mesh/mdns.std.ts';
import { encodeTcpFrame, TcpFrameDecoder } from '../ts/mesh/tcpFraming.std.ts';

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
  return (
    Number.isInteger(port) && (port as number) > 0 && (port as number) < 65536
  );
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

// ---- LAN discovery: mDNS / DNS-SD on 224.0.0.251:5353 ----------------------
//
// A hand-written responder + querier for `_asher-mesh._tcp` (packets built in
// ts/mesh/mdns.std.ts). We answer PTR queries for the service with our
// instance (SRV -> listen port, TXT fp=<hex>, A/AAAA for each LAN address) and
// forward every peer we see in a response to the renderer, which decides who
// dials (MeshService: the lexicographically smaller fingerprint). Sharing the
// port with a system mDNS daemon works because both bind with SO_REUSEADDR;
// if the bind is refused the error goes back to the renderer and discovery
// simply stays off (manual host:port still works).

const MDNS_MIN_RESPONSE_GAP_MS = 1_000;
const FINGERPRINT_HEX = /^[0-9a-f]{32}$/;

type MdnsState = {
  socket: DgramSocket;
  fingerprintHex: string;
  /** Our TCP listen port, or undefined when we only browse. */
  port: number | undefined;
  announceTimer: ReturnType<typeof setInterval> | undefined;
  queryTimer: ReturnType<typeof setInterval> | undefined;
};

let mdns: MdnsState | undefined;
/** Last unsolicited multicast announcement (RFC 6762 asks for >= 1 s apart). */
let mdnsLastAnnounceAt = 0;

function lanAddresses(): Array<string> {
  const out: Array<string> = [];
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.internal) {
        continue;
      }
      if (entry.family === 'IPv4') {
        if (!entry.address.startsWith('169.254.')) {
          out.push(entry.address);
        }
      } else if (!/^fe[89ab]/i.test(entry.address)) {
        // Link-local v6 needs a zone id to connect to; peers cannot use it.
        out.push(entry.address);
      }
    }
  }
  return out;
}

function mdnsSend(
  state: MdnsState,
  bytes: Uint8Array,
  rinfo?: RemoteInfo
): void {
  const target = rinfo ?? { address: MESH_MDNS_GROUP, port: MESH_MDNS_PORT };
  state.socket.send(bytes, target.port, target.address, error => {
    if (error) {
      log.warn(
        `mdns: send to ${target.address}:${target.port} failed: ${error.message}`
      );
    }
  });
}

function mdnsAnnounce(
  state: MdnsState,
  ttl?: number,
  rinfo?: RemoteInfo
): void {
  if (state.port == null) {
    return;
  }
  const now = Date.now();
  if (
    ttl == null &&
    rinfo == null &&
    now - mdnsLastAnnounceAt < MDNS_MIN_RESPONSE_GAP_MS
  ) {
    return;
  }
  if (rinfo == null) {
    mdnsLastAnnounceAt = now;
  }
  let packet: Uint8Array;
  try {
    packet = encodeMeshAnnouncement({
      fingerprintHex: state.fingerprintHex,
      port: state.port,
      addresses: lanAddresses(),
      ttl,
    });
  } catch (error) {
    log.warn(`mdns: cannot build announcement: ${error}`);
    return;
  }
  mdnsSend(state, packet, rinfo);
}

function mdnsOnMessage(state: MdnsState, msg: Buffer, rinfo: RemoteInfo): void {
  let message: DnsMessage;
  try {
    message = decodeDnsMessage(new Uint8Array(msg));
  } catch {
    // Not for us or malformed; mDNS traffic from other software is normal.
    return;
  }
  if (isMeshQuery(message)) {
    // Legacy unicast query (source port not 5353): answer the asker directly
    // as well as the group (RFC 6762 section 6.7).
    mdnsAnnounce(state);
    if (rinfo.port !== MESH_MDNS_PORT) {
      mdnsAnnounce(state, undefined, rinfo);
    }
    return;
  }
  for (const peer of parseMeshPeers(message)) {
    if (peer.fingerprintHex === state.fingerprintHex) {
      continue;
    }
    requireSend()(MeshIpc.MdnsPeer, {
      fingerprintHex: peer.fingerprintHex,
      port: peer.port,
      host: peer.host,
      addresses: peer.addresses.length > 0 ? peer.addresses : [rinfo.address],
      from: rinfo.address,
    });
  }
}

async function startMdns(
  fingerprintHex: string,
  port: number | undefined
): Promise<void> {
  if (mdns && mdns.fingerprintHex === fingerprintHex && mdns.port === port) {
    return;
  }
  stopMdns();

  const socket = createSocket({ type: 'udp4', reuseAddr: true });
  const state: MdnsState = {
    socket,
    fingerprintHex,
    port,
    announceTimer: undefined,
    queryTimer: undefined,
  };
  await new Promise<void>((resolve, reject) => {
    const onError = (error: Error) => {
      socket.close();
      reject(new Error(`mesh: mDNS bind failed: ${error.message}`));
    };
    socket.once('error', onError);
    socket.bind(MESH_MDNS_PORT, '0.0.0.0', () => {
      socket.off('error', onError);
      resolve();
    });
  });
  socket.on('error', (error: Error) => {
    log.warn(`mdns: ${error.message}`);
  });
  socket.on('message', (msg: Buffer, rinfo: RemoteInfo) => {
    mdnsOnMessage(state, msg, rinfo);
  });
  let joined = 0;
  for (const address of lanAddresses().filter(a => !a.includes(':'))) {
    try {
      socket.addMembership(MESH_MDNS_GROUP, address);
      joined += 1;
    } catch (error) {
      log.warn(`mdns: join on ${address} failed: ${error}`);
    }
  }
  if (joined === 0) {
    try {
      socket.addMembership(MESH_MDNS_GROUP);
    } catch (error) {
      log.warn(`mdns: join failed: ${error}`);
    }
  }
  try {
    socket.setMulticastTTL(255);
    socket.setMulticastLoopback(true);
  } catch (error) {
    log.warn(`mdns: socket options: ${error}`);
  }
  mdns = state;

  mdnsSend(state, encodeMeshQuery());
  mdnsAnnounce(state);
  state.queryTimer = setInterval(() => {
    mdnsSend(state, encodeMeshQuery());
  }, MESH_MDNS_QUERY_INTERVAL_MS);
  state.announceTimer = setInterval(() => {
    mdnsLastAnnounceAt = 0;
    mdnsAnnounce(state);
  }, MESH_MDNS_ANNOUNCE_INTERVAL_MS);
  log.info(
    `mdns: started as ${fingerprintHex} ${port == null ? '(browse only)' : `port ${port}`}`
  );
}

function stopMdns(): void {
  const state = mdns;
  if (!state) {
    return;
  }
  mdns = undefined;
  if (state.queryTimer) {
    clearInterval(state.queryTimer);
  }
  if (state.announceTimer) {
    clearInterval(state.announceTimer);
  }
  // Goodbye: TTL 0 flushes our records from peers' caches.
  mdnsAnnounce(state, 0);
  setTimeout(() => {
    try {
      state.socket.close();
    } catch {
      // already closed
    }
  }, 50);
  log.info('mdns: stopped');
}

// ---- Encrypted mesh backup files -----------------------------------------

const BACKUP_EXTENSION = 'asherbackup';
const BACKUP_FILTERS = [
  { name: 'Asher mesh backup', extensions: [BACKUP_EXTENSION] },
];
const MAX_BACKUP_BYTES = 64 * 1024 * 1024;

function parentWindow(): BrowserWindow | undefined {
  const webContents = getWebContents?.();
  return webContents
    ? (BrowserWindow.fromWebContents(webContents) ?? undefined)
    : undefined;
}

async function saveBackup(
  bytes: Uint8Array,
  defaultName: string
): Promise<{ canceled: true } | { canceled: false; filePath: string }> {
  const window = parentWindow();
  if (!window) {
    throw new Error('mesh: main window not ready');
  }
  const safeName = basename(defaultName).replace(/[^A-Za-z0-9._-]/g, '_');
  const { canceled, filePath } = await dialog.showSaveDialog(window, {
    defaultPath: safeName,
    filters: BACKUP_FILTERS,
    showsTagField: false,
  });
  if (canceled || !filePath) {
    return { canceled: true };
  }
  const target =
    extname(filePath) === `.${BACKUP_EXTENSION}`
      ? filePath
      : `${filePath}.${BACKUP_EXTENSION}`;
  await writeFile(target, bytes, { mode: 0o600 });
  return { canceled: false, filePath: target };
}

async function openBackup(): Promise<
  { canceled: true } | { canceled: false; filePath: string; bytes: Uint8Array }
> {
  const window = parentWindow();
  if (!window) {
    throw new Error('mesh: main window not ready');
  }
  const { canceled, filePaths } = await dialog.showOpenDialog(window, {
    filters: BACKUP_FILTERS,
    properties: ['openFile'],
  });
  const [filePath] = filePaths;
  if (canceled || !filePath) {
    return { canceled: true };
  }
  const bytes = await readFile(filePath);
  if (bytes.length > MAX_BACKUP_BYTES) {
    throw new Error('mesh: backup file is too large');
  }
  return { canceled: false, filePath, bytes: new Uint8Array(bytes) };
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
    // Electron's typings do not list every permission the runtime checks
    // (`serial`, `bluetooth`), hence the widening.
    const name = permission as string;
    if (name === 'serial' || name === 'bluetooth') {
      return (
        requestingContents != null && requestingContents.id === webContents.id
      );
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
        {
          id: port.portId,
          label: port.displayName || port.portName || port.portId,
        },
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

  ipcMain.handle(MeshIpc.TcpClose, (event: IpcMainInvokeEvent, id: unknown) => {
    assertPrivilegedSender(event, MeshIpc.TcpClose);
    if (typeof id === 'number') {
      closeSocket(id, 'requested');
    }
  });

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

  ipcMain.handle(
    MeshIpc.MdnsStart,
    async (
      event: IpcMainInvokeEvent,
      fingerprintHex: unknown,
      port: unknown
    ) => {
      assertPrivilegedSender(event, MeshIpc.MdnsStart);
      if (
        typeof fingerprintHex !== 'string' ||
        !FINGERPRINT_HEX.test(fingerprintHex)
      ) {
        throw new Error('mesh: invalid fingerprint');
      }
      if (port != null && !isValidPort(port)) {
        throw new Error('mesh: invalid listen port');
      }
      await startMdns(fingerprintHex, port == null ? undefined : port);
    }
  );

  ipcMain.handle(MeshIpc.MdnsStop, (event: IpcMainInvokeEvent) => {
    assertPrivilegedSender(event, MeshIpc.MdnsStop);
    stopMdns();
  });

  ipcMain.handle(
    MeshIpc.BackupSave,
    (event: IpcMainInvokeEvent, bytes: unknown, defaultName: unknown) => {
      assertPrivilegedSender(event, MeshIpc.BackupSave);
      if (!(bytes instanceof Uint8Array) || bytes.length === 0) {
        throw new Error('mesh: nothing to save');
      }
      return saveBackup(
        bytes,
        typeof defaultName === 'string' && defaultName
          ? defaultName
          : 'mesh.asherbackup'
      );
    }
  );

  ipcMain.handle(MeshIpc.BackupOpen, (event: IpcMainInvokeEvent) => {
    assertPrivilegedSender(event, MeshIpc.BackupOpen);
    return openBackup();
  });

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
  stopMdns();
  stopListening();
  for (const id of Array.from(sockets.keys())) {
    const entry = sockets.get(id);
    sockets.delete(id);
    entry?.socket.destroy();
  }
}
