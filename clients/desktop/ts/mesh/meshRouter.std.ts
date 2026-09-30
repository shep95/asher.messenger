// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// The one seam the normal send path knows about. MeshService registers a
// router while the transport is running; `ConversationModel.enqueueMessageForSend`
// asks it whether a conversation is a mesh contact and, if so, hands the
// saved outgoing message over instead of queueing the network job. Keeping
// this a tiny registry avoids an import cycle between the conversation model
// and the mesh service.

import type { MessageModel } from '../models/messages.preload.ts';

export type MeshRouter = Readonly<{
  /** True when `conversationId` is a mesh contact and the transport is up. */
  shouldRoute: (conversationId: string) => boolean;
  /** Encrypt and hand the (already saved) message to the mesh. */
  send: (conversationId: string, message: MessageModel) => Promise<void>;
}>;

let router: MeshRouter | undefined;

export function setMeshRouter(next: MeshRouter | undefined): void {
  router = next;
}

export function getMeshRouter(): MeshRouter | undefined {
  return router;
}
