// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// TCP links (a `meshlinkd` gateway we dial, or LAN peers that dial us).
// The sockets live in the main process; frames cross over IPC already
// de-framed (`u16 BE length` handling is in app/mesh_channel.main.ts), so here
// a socket is just a numbered pipe.

import { createLogger } from '../../logging/log.std.ts';
import { LINK_OPTIONS_TCP } from '../constants.std.ts';
import * as ipc from '../ipc.preload.ts';
import type { AttachedLink, LinkHost } from './Link.std.ts';

const log = createLogger('MeshTcpLink');

export class TcpLinkManager {
  readonly #host: LinkHost;
  readonly #bySocketId = new Map<number, AttachedLink>();
  #unsubscribe: (() => void) | undefined;
  #listening = false;

  constructor(host: LinkHost) {
    this.#host = host;
  }

  start(): void {
    if (this.#unsubscribe) {
      return;
    }
    this.#unsubscribe = ipc.subscribeTcp({
      onFrame: (socketId, frame) => {
        this.#bySocketId.get(socketId)?.deliver(frame);
      },
      onClosed: (socketId, reason) => {
        const link = this.#bySocketId.get(socketId);
        if (link) {
          log.info(`socket ${socketId} closed (${reason})`);
          this.#bySocketId.delete(socketId);
          link.detach();
        }
      },
      onAccepted: (socketId, peer) => {
        log.info(`accepted ${peer} as socket ${socketId}`);
        this.#attach(socketId, `LAN ${peer}`);
      },
    });
  }

  #attach(socketId: number, label: string): AttachedLink {
    const link = this.#host.attachLink(
      {
        kind: 'tcp',
        label,
        send: frame => ipc.tcpSend(socketId, frame),
        close: () => {
          this.#bySocketId.delete(socketId);
          void ipc.tcpClose(socketId);
        },
      },
      LINK_OPTIONS_TCP
    );
    this.#bySocketId.set(socketId, link);
    return link;
  }

  /** Dials a gateway (`host:port`). */
  async connect(host: string, port: number): Promise<void> {
    const socketId = await ipc.tcpConnect(host, port);
    this.#attach(socketId, `gateway ${host}:${port}`);
    log.info(`connected to ${host}:${port} as socket ${socketId}`);
  }

  async listen(port: number): Promise<void> {
    await ipc.tcpListen(port);
    this.#listening = true;
  }

  async stopListening(): Promise<void> {
    if (!this.#listening) {
      return;
    }
    this.#listening = false;
    await ipc.tcpStopListening();
  }

  get linkCount(): number {
    return this.#bySocketId.size;
  }

  async stop(): Promise<void> {
    this.#unsubscribe?.();
    this.#unsubscribe = undefined;
    await this.stopListening();
    for (const link of Array.from(this.#bySocketId.values())) {
      link.detach();
    }
    this.#bySocketId.clear();
  }
}

/** Parses `host:port` (or `[v6]:port`); undefined when malformed. */
export function parseGatewayAddress(
  value: string | undefined
): { host: string; port: number } | undefined {
  if (!value) {
    return undefined;
  }
  const trimmed = value.trim();
  const match = /^(?:\[([^\]]+)\]|([^:]+)):(\d{1,5})$/.exec(trimmed);
  if (!match) {
    return undefined;
  }
  const host = match[1] ?? match[2];
  const port = Number(match[3]);
  if (!host || !Number.isInteger(port) || port < 1 || port > 65535) {
    return undefined;
  }
  return { host, port };
}
