// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// The offline mesh transport for Desktop (docs/offline-mesh.md), behind the
// `mesh.transport` flag. Owns the meshlink node (external crypto, state under
// userData/mesh), pumps its events and link frames, and routes text,
// attachments and call signalling between the mesh and the normal pipelines.
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
// back-pressure from a full 256-frame queue, and `MeshNode_SelfTest` blocks
// for up to its timeout by design (a diagnostics button).

import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { v4 as generateUuid } from 'uuid';

import { createLogger } from '../logging/log.std.ts';
import { handleDataMessage } from '../messages/handleDataMessage.preload.ts';
import { ReadStatus } from '../messages/MessageReadStatus.std.ts';
import { saveAndNotify } from '../messages/saveAndNotify.preload.ts';
import { SeenStatus } from '../MessageSeenStatus.std.ts';
import { MessageModel } from '../models/messages.preload.ts';
import { SignalService as Proto } from '../protobuf/index.std.ts';
import { calling } from '../services/calling.preload.ts';
import { itemStorage } from '../textsecure/Storage.preload.ts';
import { isImage, stringToMIMEType } from '../types/MIME.std.ts';
import { isVoiceMessage } from '../util/Attachment.std.ts';
import { drop } from '../util/drop.std.ts';
import { generateMessageId } from '../util/generateMessageId.node.ts';
import { incrementMessageCounter } from '../util/incrementMessageCounter.preload.ts';
import {
  processNewAttachment,
  readAttachmentData,
  writeNewAttachmentData,
} from '../util/migrations.preload.ts';
import {
  bytesToHex,
  fingerprintFromHex,
  fingerprintToHex,
} from './address.std.ts';
import {
  MESH_ANTI_ENTROPY_SECS,
  MESH_MAX_ATTACHMENT_BYTES,
  MESH_MAX_EVENTS_PER_TICK,
  MESH_MAX_FRAMES_PER_LINK_PER_TICK,
  MESH_POLL_INTERVAL_MS,
  MESH_STATE_DIRNAME,
  MeshAttachmentKind,
} from './constants.std.ts';
import {
  decodeMeshEvent,
  decodeMeshNearby,
  decodeMeshPrepared,
  decodeMeshStats,
  linkKey,
} from './encoding.std.ts';
import * as ipc from './ipc.preload.ts';
import { openBleLink } from './links/BleLink.preload.ts';
import { openSerialLink } from './links/SerialLink.preload.ts';
import {
  parseGatewayAddress,
  TcpLinkManager,
} from './links/TcpLink.preload.ts';
import { MeshContacts, toMeshContactState } from './MeshContacts.preload.ts';
import { MeshCrypto } from './MeshCrypto.preload.ts';
import {
  deriveMeshIdentityFromAci,
  hasStoredMeshIdentity,
  loadOrCreateMeshIdentity,
  storeMeshIdentity,
} from './MeshIdentity.preload.ts';
import {
  isMeshNativeAvailable,
  missingMeshV3Functions,
  NativeMeshContactCard,
  NativeMeshIdentity,
  NativeMeshNode,
} from './MeshNative.std.ts';
import { MeshOutbox } from './MeshOutbox.preload.ts';
import { setMeshRouter } from './meshRouter.std.ts';
import { RADIO_CONFIG_EU_LONG_RANGE } from './kiss.std.ts';

import type { LinkOptionsType } from './constants.std.ts';
import type { LinkIdType, MeshEvent } from './encoding.std.ts';
import type { MeshLanPeerType } from './ipc.preload.ts';
import type {
  AttachedLink,
  LinkHost,
  MeshLinkTransport,
} from './links/Link.std.ts';
import type { RadioConfigType } from './kiss.std.ts';
import type { MeshContactType } from '../sql/server/meshContacts.std.ts';
import type { MeshContactStateType } from '../state/ducks/mesh.std.ts';
import type {
  ProcessedDataMessage,
  ProcessedEnvelope,
} from '../textsecure/Types.d.ts';
import type { MessageAttributesType } from '../model-types.d.ts';
import type { AttachmentType } from '../types/Attachment.std.ts';
import type { ServiceIdString } from '../types/ServiceId.std.ts';
import type { DurationInSeconds } from '../util/durations/index.std.ts';
import type {
  ReceivedTimestampMs,
  SentTimestampMs,
  ServerTimestampMs,
} from '@signalapp/types';

const log = createLogger('MeshService');

const STATS_EVERY_TICKS = 20;
/** Do not redial a LAN peer that refused us sooner than this. */
const LAN_RETRY_MS = 60_000;
const SELF_TEST_TIMEOUT_MS = 15_000;

type LinkRecord = {
  key: string;
  linkId: LinkIdType;
  transport: MeshLinkTransport;
  options: LinkOptionsType;
  detached: boolean;
};

type LanPeerState = {
  connecting: boolean;
  lastAttemptAt: number;
};

/** One attachment read from disk and ready for `MeshNode_PrepareAttachment`. */
type OutgoingAttachment = Readonly<{
  kind: number;
  name: string;
  mime: string;
  data: Uint8Array;
}>;

function noop(): void {
  // The synthetic incoming message has no envelope cache entry to confirm.
}

function attachmentKindFor(attachment: AttachmentType): number {
  if (isVoiceMessage(attachment)) {
    return MeshAttachmentKind.VoiceNote;
  }
  if (isImage(attachment.contentType)) {
    return MeshAttachmentKind.Image;
  }
  return MeshAttachmentKind.File;
}

function defaultMimeFor(kind: number): string {
  switch (kind) {
    case MeshAttachmentKind.Image:
      return 'image/jpeg';
    case MeshAttachmentKind.VoiceNote:
      return 'audio/aac';
    default:
      return 'application/octet-stream';
  }
}

export class MeshService implements LinkHost {
  #node: NativeMeshNode | undefined;
  #crypto: MeshCrypto | undefined;
  #fingerprintHex: string | undefined;
  readonly #contacts = new MeshContacts();
  readonly #outbox = new MeshOutbox();
  readonly #links = new Map<string, LinkRecord>();
  readonly #tcp = new TcpLinkManager(this);
  /** fingerprint hex -> link key, from `Event.Neighbour`. */
  readonly #neighbourLinks = new Map<string, string>();
  readonly #lanPeers = new Map<string, LanPeerState>();
  #listenPort: number | undefined;
  #timer: ReturnType<typeof setInterval> | undefined;
  #unsubscribeDevices: (() => void) | undefined;
  #unsubscribeMdns: (() => void) | undefined;
  #eventChain: Promise<void> = Promise.resolve();
  #starting: Promise<void> | undefined;
  #tickCount = 0;
  #lastSentAt = 0;
  #v3Warned = false;

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
    const missingV3 = missingMeshV3Functions();
    if (missingV3.length > 0) {
      log.warn(
        `libsignal build predates meshlink v3 (missing ${missingV3.join(', ')}); ` +
          'attachments, calls, nearby, backup and self-test are unavailable'
      );
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
    this.#crypto = new MeshCrypto(
      ourAci,
      itemStorage.user.getCheckedDeviceId()
    );
    this.#fingerprintHex = fingerprintToHex(node.fingerprint());

    await this.#contacts.load();
    for (const contact of this.#contacts.all()) {
      if (contact.card.length > 0) {
        try {
          node.addContact(contact.card);
        } catch (error) {
          log.warn(
            `node rejected stored card ${contact.fingerprint}: ${error}`
          );
        }
      }
    }

    const card = NativeMeshContactCard.decode(node.card());
    const actions = window.reduxActions?.mesh;
    actions?.setMeshIdentity(this.#fingerprintHex, card.toBase64());
    actions?.setMeshContacts(this.#contacts.all().map(toMeshContactState));
    actions?.setMeshLastError(undefined);
    actions?.setMeshRunning(true);

    setMeshRouter({
      shouldRoute: conversationId => this.shouldRoute(conversationId),
      send: (conversationId, message) =>
        this.sendMessage(conversationId, message),
      sendCallSignal: (conversationId, callMessage) =>
        this.sendCallSignal(conversationId, callMessage),
    });

    this.#tcp.start();
    this.#unsubscribeDevices = ipc.subscribeDevices({
      onSerialPorts: choices => actions?.setMeshSerialPortChoices(choices),
      onBluetoothDevices: choices =>
        actions?.setMeshBluetoothDeviceChoices(choices),
    });
    this.#unsubscribeMdns = ipc.subscribeMdns(peer => {
      drop(this.#onLanPeer(peer));
    });
    this.#timer = setInterval(() => this.#tick(), MESH_POLL_INTERVAL_MS);

    log.info(`started; fingerprint ${this.#fingerprintHex}`);

    const gateway = parseGatewayAddress(itemStorage.get('meshGatewayAddress'));
    if (gateway) {
      drop(this.connectGateway(`${gateway.host}:${gateway.port}`));
    }
    const listenPort = itemStorage.get('meshListenPort');
    if (listenPort) {
      drop(this.listen(listenPort));
    } else {
      drop(this.#restartDiscovery());
    }
    this.refreshNearby();
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
    this.#unsubscribeMdns?.();
    this.#unsubscribeMdns = undefined;
    await ipc.mdnsStop().catch(noop);
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
    this.#neighbourLinks.clear();
    this.#lanPeers.clear();
    this.#node = undefined;
    this.#crypto = undefined;
    this.#fingerprintHex = undefined;
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

  attachLink(
    transport: MeshLinkTransport,
    options: LinkOptionsType
  ): AttachedLink {
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
    this.#forgetNeighboursOn(record.key);
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

  #forgetNeighboursOn(key: string): void {
    for (const [fingerprintHex, linkKeyOf] of Array.from(
      this.#neighbourLinks
    )) {
      if (linkKeyOf === key) {
        this.#neighbourLinks.delete(fingerprintHex);
      }
    }
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
    this.#listenPort = port;
    await this.#restartDiscovery();
  }

  async stopListening(): Promise<void> {
    await this.#tcp.stopListening();
    this.#listenPort = undefined;
    if (this.#node) {
      await this.#restartDiscovery();
    }
  }

  // ---- LAN discovery (mDNS, no hardware) ----------------------------------

  /**
   * (Re)starts the mDNS responder in main with our fingerprint and listen
   * port. Without a listen port we only browse and always dial. Failure to
   * bind 5353 is reported but not fatal: manual host:port still works.
   */
  async #restartDiscovery(): Promise<void> {
    const fingerprintHex = this.#fingerprintHex;
    if (!fingerprintHex) {
      return;
    }
    try {
      await ipc.mdnsStart(fingerprintHex, this.#listenPort);
    } catch (error) {
      this.#reportError(`LAN discovery: ${error}`);
    }
  }

  /**
   * A peer advertised itself. To avoid two sockets per pair, only the
   * lexicographically smaller fingerprint dials when both listen; a peer
   * that is not listening has to dial regardless.
   */
  async #onLanPeer(peer: MeshLanPeerType): Promise<void> {
    const ourHex = this.#fingerprintHex;
    if (!this.#node || !ourHex || peer.fingerprintHex === ourHex) {
      return;
    }
    if (this.#neighbourLinks.has(peer.fingerprintHex)) {
      return;
    }
    const weDial = this.#listenPort == null || ourHex < peer.fingerprintHex;
    if (!weDial) {
      return;
    }
    const now = Date.now();
    const state = this.#lanPeers.get(peer.fingerprintHex);
    if (
      state?.connecting ||
      (state && now - state.lastAttemptAt < LAN_RETRY_MS)
    ) {
      return;
    }
    const next: LanPeerState = { connecting: true, lastAttemptAt: now };
    this.#lanPeers.set(peer.fingerprintHex, next);
    let lastError: unknown;
    try {
      for (const address of peer.addresses) {
        try {
          // eslint-disable-next-line no-await-in-loop
          await this.#tcp.connect(
            address,
            peer.port,
            `LAN ${peer.fingerprintHex.slice(0, 8)} (${address}:${peer.port})`
          );
          log.info(
            `dialled LAN peer ${peer.fingerprintHex} at ${address}:${peer.port}`
          );
          return;
        } catch (error) {
          lastError = error;
        }
      }
      log.warn(
        `LAN peer ${peer.fingerprintHex} unreachable: ${String(lastError)}`
      );
    } finally {
      next.connecting = false;
    }
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
    this.refreshNearby();
    return toMeshContactState(contact);
  }

  /**
   * "Add" from the Nearby list: the node holds the card it saw (broadcast,
   * card share or neighbour); adding it goes through the same path as a
   * pasted card.
   */
  async addNearbyContact(
    fingerprintHex: string
  ): Promise<MeshContactStateType> {
    const node = this.#requireNode();
    const fingerprint = fingerprintFromHex(fingerprintHex);
    const card = node.contact(fingerprint);
    if (card.length === 0) {
      throw new Error(
        'Their card has not reached this device yet; ask them to broadcast or paste it'
      );
    }
    const decoded = NativeMeshContactCard.decode(card);
    node.addContact(card);
    const contact = await this.#contacts.addCard(decoded);
    this.refreshNearby();
    return toMeshContactState(contact);
  }

  getContactForConversation(
    conversationId: string
  ): MeshContactType | undefined {
    return this.#contacts.getByConversationId(conversationId);
  }

  /** `MeshNode_Nearby` -> redux; called on a timer and after contact changes. */
  refreshNearby(): void {
    const node = this.#node;
    if (!node) {
      return;
    }
    try {
      const entries = decodeMeshNearby(node.nearby());
      window.reduxActions?.mesh?.setMeshNearby(
        entries.map(entry => {
          const fingerprintHex = fingerprintToHex(entry.fingerprint);
          const known = this.#contacts.getByFingerprint(fingerprintHex);
          return {
            fingerprintHex,
            name: entry.name,
            lastSeenAt: entry.lastSeenSecs * 1000,
            direct: entry.direct || this.#neighbourLinks.has(fingerprintHex),
            isContact: known != null && known.card.length > 0,
          };
        })
      );
    } catch (error) {
      if (!this.#v3Warned) {
        this.#v3Warned = true;
        log.warn(`nearby unavailable: ${error}`);
      }
    }
  }

  // ---- sending (MeshRouter) -----------------------------------------------

  shouldRoute(conversationId: string): boolean {
    return (
      this.#node != null && this.#contacts.isMeshConversation(conversationId)
    );
  }

  /**
   * Text and attachments. Each attachment is read from disk (at most 4 MiB,
   * the core's limit), prepared as manifest + chunks, encrypted entry by
   * entry and sent; the message fails as a whole, before anything is sent,
   * when one attachment is too large or unreadable.
   */
  async sendMessage(
    conversationId: string,
    message: MessageModel
  ): Promise<void> {
    const node = this.#requireNode();
    const crypto = this.#crypto;
    const contact = this.#contacts.getByConversationId(conversationId);
    if (!crypto || !contact) {
      throw new Error(`sendMessage: ${conversationId} is not a mesh contact`);
    }
    const body = message.get('body') ?? '';
    const attachments = message.get('attachments') ?? [];
    if (!body && attachments.length === 0) {
      await this.#outbox.markFailed(message, contact.conversationId);
      throw new Error('mesh carries text and attachments only');
    }

    let outgoing: Array<OutgoingAttachment>;
    try {
      outgoing = await Promise.all(
        attachments.map(a => this.#readOutgoingAttachment(a))
      );
    } catch (error) {
      await this.#outbox.markFailed(message, contact.conversationId);
      this.#reportError(`message ${message.id}: ${error}`);
      throw error;
    }

    const fingerprint = this.#contacts.fingerprintBytes(contact);
    await this.#outbox.sendMessage(message, contact, async () => {
      const tracked: Array<Uint8Array> = [];
      if (body) {
        const prepared = decodeMeshPrepared(
          node.prepareText(fingerprint, new TextEncoder().encode(body))
        );
        const ids = await this.#outbox.sendPrepared(
          node,
          crypto,
          contact,
          fingerprint,
          prepared
        );
        tracked.push(...ids);
      }
      for (const attachment of outgoing) {
        const prepared = decodeMeshPrepared(
          node.prepareAttachment(
            fingerprint,
            attachment.kind,
            attachment.name,
            attachment.mime,
            attachment.data
          )
        );
        if (prepared.length === 0) {
          throw new Error('MeshNode_PrepareAttachment returned no item');
        }
        // eslint-disable-next-line no-await-in-loop
        const ids = await this.#outbox.sendPrepared(
          node,
          crypto,
          contact,
          fingerprint,
          prepared
        );
        const [manifestId] = ids;
        if (manifestId) {
          tracked.push(manifestId);
        }
        log.info(
          `attachment ${attachment.name || attachment.mime} (${attachment.data.length} B) ` +
            `sent as ${ids.length} bundles`
        );
      }
      return tracked;
    });
  }

  async #readOutgoingAttachment(
    attachment: AttachmentType
  ): Promise<OutgoingAttachment> {
    const label = attachment.fileName || attachment.contentType;
    if (attachment.size > MESH_MAX_ATTACHMENT_BYTES) {
      throw new Error(
        `${label} is ${attachment.size} bytes; the mesh carries at most ${MESH_MAX_ATTACHMENT_BYTES} (4 MiB)`
      );
    }
    const data = await readAttachmentData(attachment);
    if (data.length > MESH_MAX_ATTACHMENT_BYTES) {
      throw new Error(
        `${label} is ${data.length} bytes; the mesh carries at most ${MESH_MAX_ATTACHMENT_BYTES} (4 MiB)`
      );
    }
    return {
      kind: attachmentKindFor(attachment),
      name: attachment.fileName ?? '',
      mime: attachment.contentType,
      data,
    };
  }

  /**
   * Call signalling for a mesh contact (from `CallingClass.#handleOutgoingSignaling`):
   * the serialized `CallMessage` rides in a high-priority, 90 s bundle. Not
   * tracked in the outbox; RingRTC has its own retransmission.
   */
  async sendCallSignal(
    conversationId: string,
    callMessage: Uint8Array
  ): Promise<void> {
    const node = this.#requireNode();
    const crypto = this.#crypto;
    const contact = this.#contacts.getByConversationId(conversationId);
    if (!crypto || !contact) {
      throw new Error(
        `sendCallSignal: ${conversationId} is not a mesh contact`
      );
    }
    const fingerprint = this.#contacts.fingerprintBytes(contact);
    const prepared = decodeMeshPrepared(
      node.prepareCallSignal(fingerprint, callMessage)
    );
    if (prepared.length === 0) {
      throw new Error('MeshNode_PrepareCallSignal returned no item');
    }
    const [bundleId] = await this.#outbox.sendPrepared(
      node,
      crypto,
      contact,
      fingerprint,
      prepared
    );
    log.info(
      `call signal (${callMessage.length} B) to ${contact.fingerprint} as bundle ` +
        (bundleId ? bytesToHex(bundleId) : '?')
    );
  }

  // ---- diagnostics --------------------------------------------------------

  /**
   * `MeshNode_SelfTest`: two throwaway in-process nodes over an in-memory
   * pipe exchange cards and text. Blocks the renderer thread for up to 15 s.
   */
  runSelfTest(): string {
    const node = this.#requireNode();
    const started = Date.now();
    const report = node.selfTest(SELF_TEST_TIMEOUT_MS);
    log.info(`self-test finished in ${Date.now() - started} ms`);
    return report;
  }

  // ---- encrypted backup ---------------------------------------------------

  /** `MeshNode_ExportBackup` -> save dialog; the chosen path, or undefined. */
  async exportBackup(passphrase: string): Promise<string | undefined> {
    const node = this.#requireNode();
    if (passphrase.length < 6) {
      throw new Error('Choose a passphrase of at least 6 characters');
    }
    const blob = node.exportBackup(passphrase);
    const name = `asher-mesh-${(this.#fingerprintHex ?? 'backup').slice(0, 8)}.asherbackup`;
    const filePath = await ipc.saveBackupFile(blob, name);
    if (filePath) {
      log.info(`exported mesh backup (${blob.length} B) to ${filePath}`);
    }
    return filePath;
  }

  /**
   * Open dialog -> restore. With the node running (or an identity stored)
   * the snapshot is merged with `MeshNode_ImportBackup`; with no identity
   * yet, `MeshIdentity_FromBackup` recovers it first. Desktop's mesh identity
   * is its Signal identity key, so a backup made under another identity is
   * refused: the sessions MeshCrypto runs would not match the card.
   */
  async restoreBackup(passphrase: string): Promise<string | undefined> {
    const file = await ipc.openBackupFile();
    if (!file) {
      return undefined;
    }
    const { filePath, bytes } = file;

    if (!this.#node && !hasStoredMeshIdentity()) {
      const ourAci = itemStorage.user.getAci();
      if (!ourAci) {
        throw new Error('Not registered');
      }
      const restored = NativeMeshIdentity.fromBackup(passphrase, bytes);
      const expected = await deriveMeshIdentityFromAci(ourAci);
      const restoredHex = fingerprintToHex(restored.fingerprint());
      const expectedHex = fingerprintToHex(expected.fingerprint());
      if (restoredHex !== expectedHex) {
        throw new Error(
          `This backup belongs to mesh identity ${restoredHex}; this device is ${expectedHex}`
        );
      }
      await storeMeshIdentity(restored);
      log.info('restored mesh identity from backup');
    }

    if (!this.#node) {
      await this.setEnabled(true);
    }
    const node = this.#requireNode();
    node.importBackup(passphrase, bytes);
    await this.#syncContactsFromNode();
    this.refreshNearby();
    log.info(`imported mesh backup from ${filePath}`);
    return filePath;
  }

  /** Cards the node holds that `mesh_contacts` does not (after an import). */
  async #syncContactsFromNode(): Promise<void> {
    const node = this.#requireNode();
    let added = 0;
    for (const encoded of node.contacts()) {
      let card: NativeMeshContactCard;
      try {
        card = NativeMeshContactCard.decode(encoded);
      } catch (error) {
        log.warn(`node returned an undecodable card: ${error}`);
        continue;
      }
      const existing = this.#contacts.getByFingerprint(card.fingerprint());
      if (existing && existing.card.length > 0) {
        continue;
      }
      // eslint-disable-next-line no-await-in-loop
      await this.#contacts.addCard(card);
      added += 1;
    }
    log.info(`synced ${added} contact(s) from the node`);
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
        this.refreshNearby();
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
          log.info(
            `deferring bundle from ${fingerprintToHex(event.from)}: ${result.reason}`
          );
          node.defer(event.bundleId);
        }
        // 'drop': undecryptable; the node keeps it until it expires.
        return;
      }
      case 'message':
        await this.#insertIncoming(event.from, {
          body: new TextDecoder().decode(event.plaintext),
          attachments: [],
        });
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
        this.refreshNearby();
        return;
      }
      case 'delivered':
        await this.#outbox.onDelivered(event.bundleId);
        return;
      case 'neighbour': {
        const key = linkKey(event.link);
        const fingerprintHex = fingerprintToHex(event.fingerprint);
        this.#neighbourLinks.set(fingerprintHex, key);
        actions?.meshNeighbourSeen(key, fingerprintHex);
        return;
      }
      case 'linkClosed': {
        const key = linkKey(event.link);
        const record = this.#links.get(key);
        if (record) {
          this.#detach(record);
        } else {
          this.#forgetNeighboursOn(key);
          actions?.meshLinkDetached(key);
        }
        return;
      }
      case 'attachmentProgress':
        actions?.setMeshTransferProgress(bytesToHex(event.transfer), {
          fromHex: fingerprintToHex(event.from),
          received: event.received,
          total: event.total,
        });
        return;
      case 'attachment':
        await this.#onAttachment(event);
        actions?.setMeshTransferProgress(bytesToHex(event.transfer), undefined);
        return;
      case 'callSignal':
        await this.#onCallSignal(event.from, event.data);
        return;
      default:
        return;
    }
  }

  // ---- incoming -----------------------------------------------------------

  /**
   * `Event.Attachment`: the core reassembled and verified the bytes. They go
   * through the normal local-attachment writer (encrypted at rest, path +
   * localKey + plaintextHash) and land on an incoming message from the
   * mesh conversation, as if they had just been downloaded.
   */
  async #onAttachment(
    event: Extract<MeshEvent, { kind: 'attachment' }>
  ): Promise<void> {
    const kind = event.attachmentKind;
    const contentType = stringToMIMEType(event.mime || defaultMimeFor(kind));
    const local = await writeNewAttachmentData(
      new Uint8Array(event.data) as Uint8Array<ArrayBuffer>
    );
    let attachment: AttachmentType = {
      contentType,
      fileName: event.name || undefined,
      flags:
        kind === MeshAttachmentKind.VoiceNote
          ? Proto.AttachmentPointer.Flags.VOICE_MESSAGE
          : undefined,
      ...local,
    };
    if (kind === MeshAttachmentKind.Image || isImage(contentType)) {
      try {
        attachment = await processNewAttachment(attachment, 'attachment');
      } catch (error) {
        log.warn(`processNewAttachment failed, keeping raw file: ${error}`);
      }
    }
    log.info(
      `attachment ${bytesToHex(event.transfer)} from ${fingerprintToHex(event.from)}: ` +
        `${contentType}, ${local.size} B`
    );
    await this.#insertIncoming(event.from, {
      body: undefined,
      attachments: [attachment],
    });
  }

  /**
   * `Event.Message` / `Event.Attachment`: the node decoded the envelope and
   * acked; insert the content as an incoming message through the same
   * handler MessageReceiver's data messages take, with a synthetic
   * envelope-less payload.
   */
  async #insertIncoming(
    from: Uint8Array,
    content: {
      body: string | undefined;
      attachments: ReadonlyArray<AttachmentType>;
    }
  ): Promise<void> {
    const contact =
      this.#contacts.getByFingerprint(from) ??
      (await this.#contacts.ensureForFingerprint(from));
    const conversation = window.ConversationController.get(
      contact.conversationId
    );
    if (!conversation) {
      log.warn(`no conversation for mesh contact ${contact.fingerprint}`);
      return;
    }

    // The bundle does not carry the sender's clock in the event; use ours,
    // strictly increasing so handleDataMessage's (sent_at, sender) dedupe
    // never collapses two messages that arrive in the same millisecond.
    const now = this.#nextTimestamp();
    const sourceServiceId = conversation.getServiceId() as ServiceIdString;
    const { body, attachments } = content;

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
      // Already on disk: `path`/`localKey` present means nothing to download.
      attachments,
      flags: 0,
      expireTimer: (conversation.get('expireTimer') ?? 0) as DurationInSeconds,
      expireTimerVersion: conversation.get('expireTimerVersion') ?? 1,
      timestamp: now,
      isViewOnce: false,
    };
    await handleDataMessage(message, dataMessage, noop, {}, { saveAndNotify });
  }

  /**
   * `Event.CallSignal`: the peer's `CallMessage` proto, handed to the same
   * `CallingClass.handleCallingMessage` the server path uses, with a
   * synthetic envelope naming the mesh conversation's serviceId, device 1.
   * RingRTC then negotiates ICE with host candidates over the LAN.
   */
  async #onCallSignal(from: Uint8Array, data: Uint8Array): Promise<void> {
    const contact =
      this.#contacts.getByFingerprint(from) ??
      (await this.#contacts.ensureForFingerprint(from));
    const conversation = window.ConversationController.get(
      contact.conversationId
    );
    if (!conversation) {
      log.warn(`no conversation for mesh contact ${contact.fingerprint}`);
      return;
    }
    const ourAci = itemStorage.user.getCheckedAci();
    const now = this.#nextTimestamp();
    const envelope: ProcessedEnvelope = {
      id: generateUuid(),
      receivedAtCounter: incrementMessageCounter(),
      receivedAtDate: now as ReceivedTimestampMs,
      messageAgeSec: 0,
      type: Proto.Envelope.Type.DOUBLE_RATCHET,
      source: undefined,
      sourceServiceId: conversation.getServiceId() as ServiceIdString,
      sourceDevice: 1,
      destinationServiceId: ourAci,
      updatedPni: undefined,
      timestamp: now as SentTimestampMs,
      content: new Uint8Array(0),
      serverGuid: generateUuid(),
      serverTimestamp: now as ServerTimestampMs,
      groupId: undefined,
      urgent: true,
      story: false,
      reportingToken: undefined,
    };
    const callMessage = Proto.CallMessage.decode(new Uint8Array(data));
    log.info(`call signal from ${contact.fingerprint} (${data.length} B)`);
    await calling.handleCallingMessage(envelope, callMessage);
  }

  // ---- helpers ------------------------------------------------------------

  #nextTimestamp(): number {
    const now = Math.max(Date.now(), this.#lastSentAt + 1);
    this.#lastSentAt = now;
    return now;
  }

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
