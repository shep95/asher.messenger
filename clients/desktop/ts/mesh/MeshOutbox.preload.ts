// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Outgoing bundles over the mesh and the bundle id -> message map that turns
// `Event.Delivered` into a delivery receipt on the right message.
//
// A message may become several bundles: one for its text and, per
// attachment, a manifest plus N chunks (`MeshNode_PrepareAttachment`). Only
// the text bundle and each attachment's manifest are tracked; the message is
// "delivered" once every tracked bundle has been acknowledged (the core
// only acks a manifest after the transfer completed, MESH_CONTRACT_V3).

import { createLogger } from '../logging/log.std.ts';
import {
  SendActionType,
  SendStatus,
  sendStateReducer,
} from '../messages/MessageSendState.std.ts';
import { bytesToHex } from './address.std.ts';
import { decodeMeshPrepared } from './encoding.std.ts';

import type { MessageModel } from '../models/messages.preload.ts';
import type { MeshContactType } from '../sql/server/meshContacts.std.ts';
import type { MeshPrepared } from './encoding.std.ts';
import type { MeshCrypto } from './MeshCrypto.preload.ts';
import type { NativeMeshNode } from './MeshNative.std.ts';

const log = createLogger('MeshOutbox');

type PendingBundle = Readonly<{ messageId: string; conversationId: string }>;

async function applySendAction(
  message: MessageModel,
  conversationIds: ReadonlyArray<string>,
  type: SendActionType
): Promise<void> {
  const now = Date.now();
  const sendStateByConversationId = {
    ...(message.get('sendStateByConversationId') ?? {}),
  };
  for (const conversationId of conversationIds) {
    const previous = sendStateByConversationId[conversationId] ?? {
      status: SendStatus.Pending,
      updatedAt: now,
    };
    sendStateByConversationId[conversationId] = sendStateReducer(previous, {
      type,
      updatedAt: now,
    });
  }
  message.set({ sendStateByConversationId });
  await window.MessageCache.saveMessage(message);
}

export class MeshOutbox {
  readonly #pending = new Map<string, PendingBundle>();
  /** messageId -> tracked bundles still waiting for an ack. */
  readonly #remainingByMessage = new Map<string, number>();
  readonly #pendingByConversation = new Map<string, number>();

  #bump(conversationId: string, delta: number): void {
    const next = (this.#pendingByConversation.get(conversationId) ?? 0) + delta;
    if (next > 0) {
      this.#pendingByConversation.set(conversationId, next);
    } else {
      this.#pendingByConversation.delete(conversationId);
    }
    window.reduxActions?.mesh?.setMeshCarrying(
      conversationId,
      Math.max(next, 0)
    );
  }

  /** Bundles sent to this conversation that no ack has come back for. */
  outstandingFor(conversationId: string): number {
    return this.#pendingByConversation.get(conversationId) ?? 0;
  }

  /**
   * Encrypts every entry of a prepared list with the contact's session, in
   * order, and hands each to the node. Returns the bundle ids in the same
   * order (index 0 is the text bundle or the attachment manifest).
   */
  async sendPrepared(
    node: NativeMeshNode,
    crypto: MeshCrypto,
    contact: MeshContactType,
    fingerprint: Uint8Array,
    prepared: ReadonlyArray<MeshPrepared>
  ): Promise<Array<Uint8Array>> {
    const bundleIds: Array<Uint8Array> = [];
    for (const item of prepared) {
      // Sequential on purpose: the Double Ratchet must see the chunks in the
      // order the core numbered them.
      // eslint-disable-next-line no-await-in-loop
      const { messageType, ciphertext } = await crypto.encrypt(
        fingerprint,
        contact.card,
        item.plaintext
      );
      bundleIds.push(
        node.sendCiphertext(item.to, item.commit, messageType, ciphertext)
      );
    }
    return bundleIds;
  }

  /**
   * Sends one message. `produce` puts the bundles on the mesh and returns
   * the ids to track for delivery. Marks the message Sent on success (the
   * mesh has it; delivery is confirmed by the acks) or Failed on any error.
   */
  async sendMessage(
    message: MessageModel,
    contact: MeshContactType,
    produce: () => Promise<Array<Uint8Array>>
  ): Promise<void> {
    const ourConversationId =
      window.ConversationController.getOurConversationIdOrThrow();
    try {
      const tracked = await produce();
      if (tracked.length === 0) {
        throw new Error('nothing was sent');
      }
      for (const bundleId of tracked) {
        this.#pending.set(bytesToHex(bundleId), {
          messageId: message.id,
          conversationId: contact.conversationId,
        });
      }
      this.#remainingByMessage.set(message.id, tracked.length);
      this.#bump(contact.conversationId, tracked.length);
      await applySendAction(
        message,
        [contact.conversationId, ourConversationId],
        SendActionType.Sent
      );
      log.info(
        `sent ${tracked.length} tracked bundle(s) for message ${message.id}: ` +
          tracked.map(bytesToHex).join(', ')
      );
    } catch (error) {
      log.error(`send failed for message ${message.id}: ${error}`);
      await applySendAction(
        message,
        [contact.conversationId],
        SendActionType.Failed
      );
      throw error;
    }
  }

  /** prepare -> encrypt -> send for a text-only message (one bundle). */
  async sendText(
    node: NativeMeshNode,
    crypto: MeshCrypto,
    contact: MeshContactType,
    fingerprint: Uint8Array,
    message: MessageModel,
    body: string
  ): Promise<void> {
    await this.sendMessage(message, contact, async () => {
      const prepared = decodeMeshPrepared(
        node.prepareText(fingerprint, new TextEncoder().encode(body))
      );
      if (prepared.length === 0) {
        throw new Error('MeshNode_PrepareText returned no item');
      }
      return this.sendPrepared(node, crypto, contact, fingerprint, prepared);
    });
  }

  /** A message the mesh cannot carry (too large, empty). */
  async markFailed(
    message: MessageModel,
    conversationId: string
  ): Promise<void> {
    await applySendAction(message, [conversationId], SendActionType.Failed);
  }

  /** `Event.Delivered`: the recipient's ack for one of our bundles. */
  async onDelivered(bundleId: Uint8Array): Promise<void> {
    const key = bytesToHex(bundleId);
    const pending = this.#pending.get(key);
    if (!pending) {
      return;
    }
    this.#pending.delete(key);
    this.#bump(pending.conversationId, -1);
    const remaining =
      (this.#remainingByMessage.get(pending.messageId) ?? 1) - 1;
    if (remaining > 0) {
      this.#remainingByMessage.set(pending.messageId, remaining);
      log.info(
        `bundle ${key} delivered; ${remaining} more for message ${pending.messageId}`
      );
      return;
    }
    this.#remainingByMessage.delete(pending.messageId);
    const message = window.MessageCache.getById(pending.messageId);
    if (!message) {
      log.warn(
        `delivered bundle ${key} but message ${pending.messageId} is gone`
      );
      return;
    }
    await applySendAction(
      message,
      [pending.conversationId],
      SendActionType.GotDeliveryReceipt
    );
    log.info(`bundle ${key} delivered (message ${pending.messageId})`);
  }

  clear(): void {
    for (const conversationId of Array.from(
      this.#pendingByConversation.keys()
    )) {
      window.reduxActions?.mesh?.setMeshCarrying(conversationId, 0);
    }
    this.#pending.clear();
    this.#remainingByMessage.clear();
    this.#pendingByConversation.clear();
  }
}
