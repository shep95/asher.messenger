// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Outgoing text over the mesh and the bundle id -> message map that turns
// `Event.Delivered` into a delivery receipt on the right message.

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
  readonly #pendingByConversation = new Map<string, number>();

  #bump(conversationId: string, delta: number): void {
    const next = (this.#pendingByConversation.get(conversationId) ?? 0) + delta;
    if (next > 0) {
      this.#pendingByConversation.set(conversationId, next);
    } else {
      this.#pendingByConversation.delete(conversationId);
    }
    window.reduxActions?.mesh?.setMeshCarrying(conversationId, Math.max(next, 0));
  }

  /** Bundles sent to this conversation that no ack has come back for. */
  outstandingFor(conversationId: string): number {
    return this.#pendingByConversation.get(conversationId) ?? 0;
  }

  /**
   * prepare -> encrypt -> send. Marks the message Sent on success (the mesh
   * has it; delivery is confirmed by the ack) or Failed on any error.
   */
  async sendText(
    node: NativeMeshNode,
    crypto: MeshCrypto,
    contact: MeshContactType,
    fingerprint: Uint8Array,
    message: MessageModel,
    body: string
  ): Promise<void> {
    const ourConversationId =
      window.ConversationController.getOurConversationIdOrThrow();
    try {
      const [prepared] = decodeMeshPrepared(
        node.prepareText(fingerprint, new TextEncoder().encode(body))
      );
      if (!prepared) {
        throw new Error('MeshNode_PrepareText returned no item');
      }
      const { messageType, ciphertext } = await crypto.encrypt(
        fingerprint,
        contact.card,
        prepared.plaintext
      );
      const bundleId = node.sendCiphertext(
        prepared.to,
        prepared.commit,
        messageType,
        ciphertext
      );
      const key = bytesToHex(bundleId);
      this.#pending.set(key, {
        messageId: message.id,
        conversationId: contact.conversationId,
      });
      this.#bump(contact.conversationId, 1);
      await applySendAction(
        message,
        [contact.conversationId, ourConversationId],
        SendActionType.Sent
      );
      log.info(`sent bundle ${key} for message ${message.id}`);
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

  /** A message the mesh cannot carry (attachments, empty body). */
  async markFailed(message: MessageModel, conversationId: string): Promise<void> {
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
    const message = window.MessageCache.getById(pending.messageId);
    if (!message) {
      log.warn(`delivered bundle ${key} but message ${pending.messageId} is gone`);
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
    for (const conversationId of Array.from(this.#pendingByConversation.keys())) {
      window.reduxActions?.mesh?.setMeshCarrying(conversationId, 0);
    }
    this.#pending.clear();
    this.#pendingByConversation.clear();
  }
}
