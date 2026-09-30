// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// The one seam the normal send paths know about. MeshService registers a
// router while the transport is running; `ConversationModel.enqueueMessageForSend`
// asks it whether a conversation is a mesh contact and, if so, hands the
// saved outgoing message over instead of queueing the network job, and
// `CallingClass.#handleOutgoingSignaling` (ts/services/calling.preload.ts)
// does the same with the serialized `CallMessage` proto. Keeping this a tiny
// registry avoids an import cycle between those modules and the mesh service.

import type { MessageModel } from '../models/messages.preload.ts';

export type MeshRouter = Readonly<{
  /** True when `conversationId` is a mesh contact and the transport is up. */
  shouldRoute: (conversationId: string) => boolean;
  /** Encrypt and hand the (already saved) message to the mesh. */
  send: (conversationId: string, message: MessageModel) => Promise<void>;
  /**
   * Carry serialized call signalling (`Proto.CallMessage` bytes) to a mesh
   * contact: `MeshNode_PrepareCallSignal` -> encrypt -> `SendCiphertext`.
   */
  sendCallSignal: (
    conversationId: string,
    callMessage: Uint8Array
  ) => Promise<void>;
}>;

let router: MeshRouter | undefined;

export function setMeshRouter(next: MeshRouter | undefined): void {
  router = next;
}

export function getMeshRouter(): MeshRouter | undefined {
  return router;
}
