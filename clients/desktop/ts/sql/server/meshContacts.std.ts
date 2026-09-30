// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { sql } from '../util.std.ts';

import type { ReadableDB, WritableDB } from '../Interface.std.ts';

/**
 * A mesh contact: the fingerprint <-> conversation link plus the last card
 * we hold for it. See ts/mesh/address.std.ts for the serviceId convention.
 */
export type MeshContactType = Readonly<{
  /** 32 lowercase hex characters. */
  fingerprint: string;
  conversationId: string;
  /** Encoded, signature-checked contact card (what `MeshNode_AddContact` takes). */
  card: Uint8Array;
  name: string;
  addedAt: number;
}>;

type MeshContactRow = {
  fingerprint: string;
  conversationId: string;
  card: Uint8Array;
  name: string;
  added_at: number;
};

function fromRow(row: MeshContactRow): MeshContactType {
  return {
    fingerprint: row.fingerprint,
    conversationId: row.conversationId,
    card: new Uint8Array(row.card),
    name: row.name,
    addedAt: row.added_at,
  };
}

export function getAllMeshContacts(db: ReadableDB): Array<MeshContactType> {
  return db
    .prepare('SELECT * FROM mesh_contacts ORDER BY name ASC, added_at ASC;')
    .all<MeshContactRow>()
    .map(fromRow);
}

export function getMeshContactByFingerprint(
  db: ReadableDB,
  fingerprint: string
): MeshContactType | undefined {
  const [query, params] =
    sql`SELECT * FROM mesh_contacts WHERE fingerprint = ${fingerprint};`;
  const row = db.prepare(query).get<MeshContactRow>(params);
  return row ? fromRow(row) : undefined;
}

export function getMeshContactByConversationId(
  db: ReadableDB,
  conversationId: string
): MeshContactType | undefined {
  const [query, params] =
    sql`SELECT * FROM mesh_contacts WHERE conversationId = ${conversationId};`;
  const row = db.prepare(query).get<MeshContactRow>(params);
  return row ? fromRow(row) : undefined;
}

/** Inserts or replaces the card/name; the conversation link never changes. */
export function upsertMeshContact(db: WritableDB, contact: MeshContactType): void {
  // Copy so the bound BLOB is a plain ArrayBuffer-backed Uint8Array.
  const card = new Uint8Array(contact.card);
  const [query, params] = sql`
    INSERT INTO mesh_contacts (fingerprint, conversationId, card, name, added_at)
    VALUES (
      ${contact.fingerprint},
      ${contact.conversationId},
      ${card},
      ${contact.name},
      ${contact.addedAt}
    )
    ON CONFLICT(fingerprint) DO UPDATE SET
      card = excluded.card,
      name = excluded.name;
  `;
  db.prepare(query).run(params);
}

export function removeMeshContact(db: WritableDB, fingerprint: string): void {
  const [query, params] =
    sql`DELETE FROM mesh_contacts WHERE fingerprint = ${fingerprint};`;
  db.prepare(query).run(params);
}

export function _deleteAllMeshContacts(db: WritableDB): void {
  db.prepare('DELETE FROM mesh_contacts;').run();
}
