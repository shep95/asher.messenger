// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Mesh contacts and the fingerprint <-> conversation link.
//
// Convention: a mesh contact is a private conversation whose `serviceId` is
// the fingerprint rendered as a v4-shaped UUID (ts/mesh/address.std.ts has
// the exact rule and why). The `mesh_contacts` table is the source of truth
// for the link in both directions; nothing parses a serviceId back into a
// fingerprint. The conversation's display name comes from the card.

import { createLogger } from '../logging/log.std.ts';
import { DataReader, DataWriter } from '../sql/Client.preload.ts';
import {
  fingerprintFromHex,
  fingerprintToHex,
  fingerprintToMeshServiceId,
} from './address.std.ts';
import type { NativeMeshContactCard } from './MeshNative.std.ts';

import type { MeshContactType } from '../sql/server/meshContacts.std.ts';
import type { ServiceIdString } from '../types/ServiceId.std.ts';
import type { ConversationModel } from '../models/conversations.preload.ts';
import type { MeshContactStateType } from '../state/ducks/mesh.std.ts';

const log = createLogger('MeshContacts');

export function toMeshContactState(
  contact: MeshContactType
): MeshContactStateType {
  return {
    conversationId: contact.conversationId,
    fingerprintHex: contact.fingerprint,
    name: contact.name,
    addedAt: contact.addedAt,
  };
}

export class MeshContacts {
  readonly #byFingerprint = new Map<string, MeshContactType>();
  readonly #byConversationId = new Map<string, MeshContactType>();

  async load(): Promise<void> {
    const rows = await DataReader.getAllMeshContacts();
    this.#byFingerprint.clear();
    this.#byConversationId.clear();
    for (const row of rows) {
      this.#index(row);
    }
    log.info(`loaded ${rows.length} mesh contacts`);
  }

  #index(contact: MeshContactType): void {
    this.#byFingerprint.set(contact.fingerprint, contact);
    this.#byConversationId.set(contact.conversationId, contact);
  }

  all(): Array<MeshContactType> {
    return Array.from(this.#byFingerprint.values());
  }

  getByConversationId(conversationId: string): MeshContactType | undefined {
    return this.#byConversationId.get(conversationId);
  }

  getByFingerprint(fingerprint: Uint8Array | string): MeshContactType | undefined {
    const hex =
      typeof fingerprint === 'string'
        ? fingerprint.toLowerCase()
        : fingerprintToHex(fingerprint);
    return this.#byFingerprint.get(hex);
  }

  isMeshConversation(conversationId: string): boolean {
    return this.#byConversationId.has(conversationId);
  }

  /** Finds or creates the private conversation for a fingerprint. */
  async #conversationFor(
    fingerprint: Uint8Array,
    name: string
  ): Promise<ConversationModel> {
    const serviceId = fingerprintToMeshServiceId(fingerprint) as ServiceIdString;
    const existing = window.ConversationController.get(serviceId);
    if (existing) {
      return existing;
    }
    // `getOrCreate` files identifiers that are not v4/v7 UUIDs as e164s, so
    // the serviceId is passed explicitly and e164 cleared; the rendering above
    // guarantees `validateConversation` accepts it.
    return window.ConversationController.getOrCreateAndWait(
      serviceId,
      'private',
      {
        serviceId,
        e164: undefined,
        profileName: name || undefined,
        profileSharing: true,
        active_at: Date.now(),
      }
    );
  }

  /**
   * Adds or refreshes a contact from a verified card. Returns the row; the
   * caller also hands the card to the node (`MeshNode_AddContact`).
   */
  async addCard(card: NativeMeshContactCard): Promise<MeshContactType> {
    const fingerprint = card.fingerprint();
    const hex = fingerprintToHex(fingerprint);
    const name = card.name();
    const existing = this.#byFingerprint.get(hex);

    const conversation = await this.#conversationFor(fingerprint, name);
    if (name && conversation.get('profileName') !== name) {
      conversation.set({ profileName: name });
      await DataWriter.updateConversation(conversation.attributes);
    }

    const contact: MeshContactType = {
      fingerprint: hex,
      conversationId: conversation.id,
      card: card.encode(),
      name,
      addedAt: existing?.addedAt ?? Date.now(),
    };
    await DataWriter.upsertMeshContact(contact);
    this.#index(contact);
    window.reduxActions?.mesh?.upsertMeshContact(toMeshContactState(contact));
    log.info(`${existing ? 'updated' : 'added'} mesh contact ${hex}`);
    return contact;
  }

  /**
   * A sender we have no card for (accepted because the crate delivers from
   * unknown senders): the conversation exists so the message has somewhere
   * to land, with an empty card until a beacon or a scanned card arrives.
   * Without a card we can still reply once their first message established
   * a session.
   */
  async ensureForFingerprint(fingerprint: Uint8Array): Promise<MeshContactType> {
    const hex = fingerprintToHex(fingerprint);
    const existing = this.#byFingerprint.get(hex);
    if (existing) {
      return existing;
    }
    const name = `Mesh ${hex.slice(0, 8)}`;
    const conversation = await this.#conversationFor(fingerprint, name);
    const contact: MeshContactType = {
      fingerprint: hex,
      conversationId: conversation.id,
      card: new Uint8Array(0),
      name,
      addedAt: Date.now(),
    };
    await DataWriter.upsertMeshContact(contact);
    this.#index(contact);
    window.reduxActions?.mesh?.upsertMeshContact(toMeshContactState(contact));
    log.info(`created placeholder mesh contact ${hex}`);
    return contact;
  }

  async remove(fingerprintHex: string): Promise<void> {
    const contact = this.#byFingerprint.get(fingerprintHex);
    if (!contact) {
      return;
    }
    await DataWriter.removeMeshContact(fingerprintHex);
    this.#byFingerprint.delete(fingerprintHex);
    this.#byConversationId.delete(contact.conversationId);
  }

  fingerprintBytes(contact: MeshContactType): Uint8Array {
    return fingerprintFromHex(contact.fingerprint);
  }
}
