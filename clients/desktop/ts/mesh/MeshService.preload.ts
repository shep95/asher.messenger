// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// The offline mesh transport for Desktop (docs/offline-mesh.md), behind the
// `mesh.transport` flag. Owns the meshlink node (external crypto, state under
// userData/mesh), pumps its events and link frames, and routes text between
// the mesh and the normal message pipeline.
//
// Threading. `MeshNode_NextEvent` and `MeshNode_LinkRead` block the calling
// thread for up to their timeout; the node lives in the preload, where every
// other libsignal call Desktop makes (encrypt, decrypt, zkgroup) also runs
// synchronously on the main renderer thread. A `worker_threads` Worker would
// need the node handle to live in the worker and every call proxied through
// postMessage; instead the poll calls are made with a zero timeout on a
// 100 ms timer, which returns immediately when nothing is ready (the Rust side
// polls once and hits an already-elapsed deadline). Latency is bounded by the
// timer, the UI thread is never parked, and the handle stays where libsignal
// loaded it. Only `MeshNode_LinkWrite` can wait (up to 1 s) and only under
// back-pressure from a full 256-frame queue.

import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';

import { createLogger } from '../logging/log.std.ts';
import { handleDataMessage } from '../messages/handleDataMessage.preload.ts';
import { ReadStatus } from '../messages/MessageReadStatus.std.ts';
import { saveAndNotify } from '../messages/saveAndNotify.preload.ts';
import { SeenStatus } from '../MessageSeenStatus.std.ts';
import { MessageModel } from '../models/messages.preload.ts';
import { itemStorage } from '../textsecure/Storage.preload.ts';
import { drop } from '../util/drop.std.ts';
import { generateMessageId } from '../util/generateMessageId.node.ts';
import { incrementMessageCounter } from '../util/incrementMessageCounter.preload.ts';
import { fingerprintToHex } from './address.std.ts';
import {
  MESH_ANTI_ENTROPY_SECS,
  MESH_MAX_EVENTS_PER_TICK,
  MESH_MAX_FRAMES_PER_LINK_PER_TICK,
  MESH_POLL_INTERVAL_MS,
  MESH_STATE_DIRNAME,
} from './constants.std.ts';
import { decodeMeshEvent, decodeMeshStats, linkKey } from './encoding.std.ts';
import * as ipc from './ipc.preload.ts';
import { openBleLink } from './links/BleLink.preload.ts';
import { openSerialLink } from './links/SerialLink.preload.ts';
import { parseGatewayAddress, TcpLinkManager } from './links/TcpLink.preload.ts';
import { MeshContacts, toMeshContactState } from './MeshContacts.preload.ts';
import { MeshCrypto } from './MeshCrypto.preload.ts';
import { loadOrCreateMeshIdentity } from './MeshIdentity.preload.ts';
import {
  isMeshNativeAvailable,
  NativeMeshContactCard,
  NativeMeshNode,
} from './MeshNative.std.ts';
import { MeshOutbox } from './MeshOutbox.preload.ts';
import { setMeshRouter } from './meshRouter.std.ts';
import { RADIO_CONFIG_EU_LONG_RANGE } from './kiss.std.ts';

import type { LinkOptionsType } from './constants.std.ts';
import type { LinkIdType, MeshEvent } from './encoding.std.ts';
import type { AttachedLink, LinkHost, MeshLinkTransport } from './links/Link.std.ts';
import type { RadioConfigType } from './kiss.std.ts';
import type { MeshContactType } from '../sql/server/meshContacts.std.ts';
import type { MeshContactStateType } from '../state/ducks/mesh.std.ts';
import type { ProcessedDataMessage } from '../textsecure/Types.d.ts';
import type { MessageAttributesType } from '../model-types.d.ts';
import type { ServiceIdString } from '../types/ServiceId.std.ts';
import type { DurationInSeconds } from '../util/durations/index.std.ts';

const log = createLogger('MeshService');

const STATS_EVERY_TICKS = 20;

type LinkRecord = {
  key: string;
  linkId: LinkIdType;
  transport: MeshLinkTransport;
  options: LinkOptionsType;
  detached: boolean;
};

function noop(): void {
  // The synthetic incoming message has no envelope cache entry to confirm.
}

export class MeshService implements LinkHost {
  #node: NativeMeshNode | undefined;
  #crypto: MeshCrypto | undefined;
  readonly #contacts = new MeshContacts();
  readonly #outbox = new MeshOutbox();
  readonly #links = new Map<string, LinkRecord>();
  readonly #tcp = new TcpLinkManager(this);
  #timer: ReturnType<typeof setInterval> | undefined;
  #unsubscribeDevices: (() => void) | undefined;
  #eventChain: Promise<void> = Promise.resolve();
  #starting: Promise<void> | undefined;
  #tickCount = 0;
  #lastSentAt = 0;

  get isRunning(): boolean {
    return this.#node != null;
  }

  // ---- lifecycle ----------------------------------------------------------

  async start(): Promise<void> {
    if (this.#node) {
      return;
    }
    if (!this.#starting) {
      this.#starting = this.#doStart().finally(() => {
        this.#starting = undefined;
      });
    }
    return this.#starting;
  }

  async #doStart(): Promise<void> {
    const ourAci = itemStorage.user.getAci();
    if (!ourAci) {
      log.warn('not registered; mesh stays off');
      return;
    }
    if (!isMeshNativeAvailable()) {
      const message = 'this libsignal build has no meshlink; mesh stays off';
      log.error(message);
      window.reduxActions?.mesh?.setMeshLastError(message);
      return;
    }

    const identity = await loadOrCreateMeshIdentity(ourAci);
    const stateDir = join(
      window.SignalContext.getPath('userData'),
      MESH_STATE_DIRNAME
    );
    await mkdir(stateDir, { recursive: true });

    const node = NativeMeshNode.new(
      identity,
      stateDir,
      true,
      MESH_ANTI_ENTROPY_SECS
    );
    this.#node = node;
    this.#crypto = new MeshCrypto(ourAci, itemStorage.user.getCheckedDeviceId());

    await this.#contacts.load();
    for (const contact of this.#contacts.all()) {
      if (contact.card.length > 0) {
        try {
          node.addContact(contact.card);
        } catch (error) {
          log.warn(`node rejected stored card ${contact.fingerprint}: ${error}`);
        }
      }
    }

    const card = NativeMeshContactCard.decode(node.card());
    const actions = window.reduxActions?.mesh;
    actions?.setMeshIdentity(fingerprintToHex(node.fingerprint()), card.toBase64());
    actions?.setMeshContacts(this.#contacts.all().map(toMeshContactState));
    actions?.setMeshLastError(undefined);
    actions?.setMeshRunning(true);

    setMeshRouter({
      shouldRoute: conversationId => this.shouldRoute(conversationId),
      send: (conversationId, message) => this.sendMessage(conversationId, message),
    });

    this.#tcp.start();
    this.#unsubscribeDevices = ipc.subscribeDevices({
      onSerialPorts: choices => actions?.setMeshSerialPortChoices(choices),
      onBluetoothDevices: choices =>
        actions?.setMeshBluetoothDeviceChoices(choices),
    });
    this.#timer = setInterval(() => this.#tick(), MESH_POLL_INTERVAL_MS);

    log.info(`started; fingerprint ${fingerprintToHex(node.fingerprint())}`);

    const gateway = parseGatewayAddress(itemStorage.get('meshGatewayAddress'));
    if (gateway) {
      drop(this.connectGateway(`${gateway.host}:${gateway.port}`));
    }
    const listenPort = itemStorage.get('meshListenPort');
    if (listenPort) {
      drop(this.listen(listenPort));
    }
  }

  async stop(): Promise<void> {
    const node = this.#node;
    if (!node) {
      return;
    }
    setMeshRouter(undefined);
    if (this.#timer) {
      clearInterval(this.#timer);
      this.#timer = undefined;
    }
    this.#unsubscribeDevices?.();
    this.#unsubscribeDevices = undefined;
    await this.#tcp.stop();
    for (const record of Array.from(this.#links.values())) {
      this.#detach(record);
    }
    await this.#eventChain.catch(noop);
    try {
      node.flush();
    } catch (error) {
      log.warn(`flush failed: ${error}`);
    }
    this.#outbox.clear();
    this.#node = undefined;
    this.#crypto = undefined;
    window.reduxActions?.mesh?.setMeshRunning(false);
    log.info('stopped');
  }

  /** Called from the Preferences toggle; persists the local override. */
  async setEnabled(enabled: boolean): Promise<void> {
    await itemStorage.put('meshTransportEnabled', enabled);
    if (enabled) {
      await this.start();
    } else {
      await this.stop();
    }
  }

  // ---- links --------------------------------------------------------------

  attachLink(transport: MeshLinkTransport, options: LinkOptionsType): AttachedLink {
    const node = this.#requireNode();
    const linkId = node.attachLink(
      options.mtu,
      options.maxBytesPerSec,
      options.maxFramesPerSec
    );
    const key = linkKey(linkId);
    const record: LinkRecord = {
      key,
      linkId,
      transport,
      options,
      detached: false,
    };
    this.#links.set(key, record);
    window.reduxActions?.mesh?.meshLinkAttached({
      key,
      kind: transport.kind,
      label: transport.label,
    });
    log.info(`link ${key} attached (${transport.kind}: ${transport.label})`);

    // Announce ourselves to whoever is on the other end.
    try {
      node.broadcastCard();
    } catch (error) {
      log.warn(`broadcastCard failed: ${error}`);
    }

    return {
      key,
      options,
      deliver: frame => {
        if (record.detached || !this.#node) {
          return;
        }
        if (!this.#node.linkWrite(linkId, frame)) {
          log.warn(`link ${key}: node refused frame (back-pressure or gone)`);
        }
      },
      detach: () => this.#detach(record),
    };
  }

  #detach(record: LinkRecord): void {
    if (record.detached) {
      return;
    }
    record.detached = true;
    this.#links.delete(record.key);
    try {
      this.#node?.detachLink(record.linkId);
    } catch (error) {
      log.warn(`detachLink ${record.key} failed: ${error}`);
    }
    Promise.resolve(record.transport.close()).catch(error => {
      log.warn(`closing link ${record.key} failed: ${error}`);
    });
    window.reduxActions?.mesh?.meshLinkDetached(record.key);
    log.info(`link ${record.key} detached`);
  }

  async connectGateway(address: string): Promise<void> {
    const parsed = parseGatewayAddress(address);
    if (!parsed) {
      throw new Error('gateway address must be host:port');
    }
    this.#requireNode();
    try {
      await this.#tcp.connect(parsed.host, parsed.port);
      window.reduxActions?.mesh?.setMeshLastError(undefined);
    } catch (error) {
      this.#reportError(`gateway ${address}: ${error}`);
      throw error;
    }
  }

  async listen(port: number): Promise<void> {
    this.#requireNode();
    await this.#tcp.listen(port);
  }

  async stopListening(): Promise<void> {
    await this.#tcp.stopListening();
  }

  /** RNode over Web Serial. Main forwards the port list; see pickSerialPort. */
  async openRadio(
    radioConfig: RadioConfigType | undefined = RADIO_CONFIG_EU_LONG_RANGE
  ): Promise<void> {
    this.#requireNode();
    await ipc.enableDeviceChoosers();
    try {
      await openSerialLink(this, { radioConfig });
    } catch (error) {
      this.#reportError(`radio: ${error}`);
      throw error;
    } finally {
      window.reduxActions?.mesh?.setMeshSerialPortChoices(null);
    }
  }

  async openBluetooth(): Promise<void> {
    this.#requireNode();
    await ipc.enableDeviceChoosers();
    try {
      await openBleLink(this);
    } catch (error) {
      this.#reportError(`bluetooth: ${error}`);
      throw error;
    } finally {
      window.reduxActions?.mesh?.setMeshBluetoothDeviceChoices(null);
    }
  }

  pickSerialPort(portId: string | null): void {
    ipc.pickSerialPort(portId);
  }

  pickBluetoothDevice(deviceId: string | null): void {
    ipc.pickBluetoothDevice(deviceId);
  }

  // ---- contacts -----------------------------------------------------------

  /** Paste-a-card (URL-safe base64, as shown in the QR code). */
  async addContactFromBase64(text: string): Promise<MeshContactStateType> {
    const node = this.#requireNode();
    const card = NativeMeshContactCard.fromBase64(text.trim());
    node.addContact(card.encode());
    const contact = await this.#contacts.addCard(card);
    return toMeshContactState(contact);
  }

  getContactForConversation(conversationId: string): MeshContactType | undefined {
    return this.#contacts.getByConversationId(conversationId);
  }

  // ---- sending (MeshRouter) -----------------------------------------------

  shouldRoute(conversationId: string): boolean {
    return this.#node != null && this.#contacts.isMeshConversation(conversationId);
  }

  async sendMessage(conversationId: string, message: MessageModel): Promise<void> {
    const node = this.#requireNode();
    const crypto = this.#crypto;
    const contact = this.#contacts.getByConversationId(conversationId);
    if (!crypto || !contact) {
      throw new Error(`sendMessage: ${conversationId} is not a mesh contact`);
    }
    const body = message.get('body');
    const attachments = message.get('attachments') ?? [];
    if (!body || attachments.length > 0) {
      // Text only over the mesh (docs/offline-mesh.md, section 7): nothing
      // is sent, the message shows as failed.
      await this.#outbox.markFailed(message, contact.conversationId);
      throw new Error('mesh carries text only');
    }
    await this.#outbox.sendText(
      node,
      crypto,
      contact,
      this.#contacts.fingerprintBytes(contact),
      message,
      body
    );
  }

  // ---- polling ------------------------------------------------------------

  #tick(): void {
    const node = this.#node;
    if (!node) {
      return;
    }
    try {
      for (let i = 0; i < MESH_MAX_EVENTS_PER_TICK; i += 1) {
        const event = decodeMeshEvent(node.nextEvent(0));
        if (!event) {
          break;
        }
        this.#enqueueEvent(event);
      }
      for (const record of this.#links.values()) {
        for (let i = 0; i < MESH_MAX_FRAMES_PER_LINK_PER_TICK; i += 1) {
          const frame = node.linkRead(record.linkId, 0);
          if (frame.length === 0) {
            break;
          }
          Promise.resolve(record.transport.send(frame)).catch(error => {
            log.warn(`link ${record.key}: send failed: ${error}`);
          });
        }
      }
      this.#tickCount += 1;
      if (this.#tickCount % STATS_EVERY_TICKS === 0) {
        this.refreshStats();
      }
    } catch (error) {
      log.error(`tick failed: ${error}`);
    }
  }

  refreshStats(): void {
    const node = this.#node;
    if (!node) {
      return;
    }
    const stats = decodeMeshStats(node.stats());
    const plain: Record<string, number> = {};
    for (const [key, value] of Object.entries(stats)) {
      plain[key] = Number(value);
    }
    window.reduxActions?.mesh?.setMeshStats(plain);
  }

  #enqueueEvent(event: MeshEvent): void {
    this.#eventChain = this.#eventChain
      .then(() => this.#handleEvent(event))
      .catch(error => {
        log.error(`event ${event.kind} failed: ${error}`);
      });
  }

  async #handleEvent(event: MeshEvent): Promise<void> {
    const node = this.#node;
    if (!node) {
      return;
    }
    const actions = window.reduxActions?.mesh;
    switch (event.kind) {
      case 'ciphertext': {
        const crypto = this.#crypto;
        if (!crypto) {
          return;
        }
        const result = await crypto.decrypt(
          event.from,
          event.messageType,
          event.ciphertext
        );
        if (result.kind === 'plaintext') {
          node.deliverPlaintext(event.bundleId, result.plaintext);
        } else if (result.kind === 'defer') {
          log.info(`deferring bundle from ${fingerprintToHex(event.from)}: ${result.reason}`);
          node.defer(event.bundleId);
        }
        // 'drop': undecryptable; the node keeps it until it expires.
        return;
      }
      case 'message':
        await this.#onMessage(event.from, event.plaintext);
        return;
      case 'groupMessage':
        // TODO(mesh): pairwise mesh groups (group.rs) are not surfaced yet.
        log.info(`group message for ${fingerprintToHex(event.group)} ignored`);
        return;
      case 'groupInvite':
        // TODO(mesh): pairwise mesh groups (group.rs) are not surfaced yet.
        log.info(`group invite for ${fingerprintToHex(event.group)} ignored`);
        return;
      case 'contact': {
        const card = node.contact(event.fingerprint);
        if (card.length > 0) {
          await this.#contacts.addCard(NativeMeshContactCard.decode(card));
        }
        return;
      }
      case 'delivered':
        await this.#outbox.onDelivered(event.bundleId);
        return;
      case 'neighbour':
        actions?.meshNeighbourSeen(
          linkKey(event.link),
          fingerprintToHex(event.fingerprint)
        );
        return;
      case 'linkClosed': {
        const record = this.#links.get(linkKey(event.link));
        if (record) {
          this.#detach(record);
        } else {
          actions?.meshLinkDetached(linkKey(event.link));
        }
        return;
      }
      default:
        return;
    }
  }

  // ---- incoming -----------------------------------------------------------

  /**
   * `Event.Message`: the node decoded the envelope and acked; insert the text
   * as an incoming message through the same handler MessageReceiver's data
   * messages take, with a synthetic envelope-less payload.
   */
  async #onMessage(from: Uint8Array, plaintext: Uint8Array): Promise<void> {
    const contact =
      this.#contacts.getByFingerprint(from) ??
      (await this.#contacts.ensureForFingerprint(from));
    const conversation = window.ConversationController.get(contact.conversationId);
    if (!conversation) {
      log.warn(`no conversation for mesh contact ${contact.fingerprint}`);
      return;
    }

    // The bundle does not carry the sender's clock in the event; use ours,
    // strictly increasing so handleDataMessage's (sent_at, sender) dedupe
    // never collapses two messages that arrive in the same millisecond.
    const now = Math.max(Date.now(), this.#lastSentAt + 1);
    this.#lastSentAt = now;
    const body = new TextDecoder().decode(plaintext);
    const sourceServiceId = conversation.getServiceId() as ServiceIdString;

    const attributes: MessageAttributesType = {
      ...generateMessageId(incrementMessageCounter()),
      conversationId: conversation.id,
      type: 'incoming',
      body,
      sent_at: now,
      timestamp: now,
      received_at_ms: now,
      serverTimestamp: now,
      readStatus: ReadStatus.Unread,
      seenStatus: SeenStatus.Unseen,
      sourceServiceId,
      sourceDevice: 1,
      unidentifiedDeliveryReceived: false,
    };
    const message = window.MessageCache.register(new MessageModel(attributes));

    const dataMessage: ProcessedDataMessage = {
      body,
      attachments: [],
      flags: 0,
      expireTimer: (conversation.get('expireTimer') ?? 0) as DurationInSeconds,
      expireTimerVersion: conversation.get('expireTimerVersion') ?? 1,
      timestamp: now,
      isViewOnce: false,
    };
    await handleDataMessage(message, dataMessage, noop, {}, { saveAndNotify });
  }

  // ---- helpers ------------------------------------------------------------

  #requireNode(): NativeMeshNode {
    if (!this.#node) {
      throw new Error('mesh transport is not running');
    }
    return this.#node;
  }

  #reportError(message: string): void {
    log.warn(message);
    window.reduxActions?.mesh?.setMeshLastError(message);
  }
}

export const meshService = new MeshService();
