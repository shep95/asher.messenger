// Copyright 2026 Asher Messenger contributors
// SPDX-License-Identifier: AGPL-3.0-only

import type { WritableDB } from '../Interface.std.ts';
import type { LoggerType } from '../../types/Logging.std.ts';

// Offline mesh contacts (docs/offline-mesh.md). One row per contact card we
// hold. `fingerprint` is the 32-hex meshlink fingerprint (the address the
// node routes on); `conversationId` is the private conversation created for
// it, whose serviceId is the fingerprint rendered as a v4-shaped UUID (see
// ts/mesh/address.std.ts). This table is the only place the two are linked;
// the serviceId is never parsed back into a fingerprint.
export default function updateToSchemaVersion1810(
  db: WritableDB,
  logger: LoggerType
): void {
  db.exec(`
    CREATE TABLE mesh_contacts (
      fingerprint TEXT NOT NULL PRIMARY KEY,
      conversationId TEXT NOT NULL UNIQUE,
      card BLOB NOT NULL,
      name TEXT NOT NULL,
      added_at INTEGER NOT NULL
    ) STRICT;
  `);
  logger.info('updateToSchemaVersion1810: created mesh_contacts');
}
