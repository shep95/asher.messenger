// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { createSelector } from 'reselect';

import type { StateType } from '../reducer.preload.ts';
import type {
  MeshContactStateType,
  MeshStateType,
} from '../ducks/mesh.std.ts';
import type { SceneState } from '../../components/conversation/SceneIndicator.dom.tsx';

export const getMesh = (state: StateType): MeshStateType => state.mesh;

export const getMeshIsRunning = createSelector(
  getMesh,
  ({ isRunning }) => isRunning
);

export const getMeshContactsByConversationId = createSelector(
  getMesh,
  ({ contactsByConversationId }) => contactsByConversationId
);

export const getMeshContacts = createSelector(
  getMeshContactsByConversationId,
  (byId): ReadonlyArray<MeshContactStateType> =>
    Object.values(byId).sort((a, b) => a.name.localeCompare(b.name))
);

export const getMeshContactSelector = createSelector(
  getMeshContactsByConversationId,
  byId =>
    (conversationId: string): MeshContactStateType | undefined =>
      byId[conversationId]
);

export const getMeshHasNeighbour = createSelector(
  getMesh,
  ({ isRunning, neighbours }) =>
    isRunning && Object.keys(neighbours).length > 0
);

/**
 * The scene for a conversation, or undefined when the mesh has nothing to
 * say about it (not a mesh contact, or the transport is off) and the caller
 * should fall back to Orbit / Out of range.
 *
 * `carrying` wins over `mesh`: bundles the outbox still holds for this contact
 * mean the message is with us (or a relay), not with them yet. Hops: 1 when
 * the contact is a direct neighbour; otherwise the node does not expose route
 * length (any relay is at least one more hop) so 2 is shown as a lower bound.
 */
export const getMeshSceneStateSelector = createSelector(
  getMesh,
  mesh =>
    (conversationId: string): SceneState | undefined => {
      if (!mesh.isRunning) {
        return undefined;
      }
      const contact = mesh.contactsByConversationId[conversationId];
      if (!contact) {
        return undefined;
      }
      if ((mesh.carryingByConversationId[conversationId] ?? 0) > 0) {
        return { kind: 'carrying' };
      }
      const neighbourCount = Object.keys(mesh.neighbours).length;
      if (neighbourCount === 0) {
        return undefined;
      }
      const isDirect = mesh.neighbours[contact.fingerprintHex] != null;
      return { kind: 'mesh', hops: isDirect ? 1 : 2 };
    }
);
