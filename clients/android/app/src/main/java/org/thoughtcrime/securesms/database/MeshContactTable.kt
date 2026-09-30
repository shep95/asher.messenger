/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.content.Context
import org.signal.core.util.delete
import org.signal.core.util.insertInto
import org.signal.core.util.readToList
import org.signal.core.util.readToSingleObject
import org.signal.core.util.Hex
import org.signal.core.util.requireLong
import org.signal.core.util.requireLongOrNull
import org.signal.core.util.requireNonNullBlob
import org.signal.core.util.requireNonNullString
import org.signal.core.util.requireString
import org.signal.core.util.select
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * Contact cards learned over the mesh or scanned from a QR code, keyed by the 16-byte device
 * fingerprint (stored as its 32-character hex, since the query helpers bind text). The mapping
 * between a fingerprint and the app's [RecipientId] lives in
 * `org.thoughtcrime.securesms.mesh.MeshContacts`; this table only stores what was learned.
 */
class MeshContactTable(context: Context, databaseHelper: SignalDatabase) : DatabaseTable(context, databaseHelper) {

  companion object {
    const val TABLE_NAME = "mesh_contact"

    const val ID = "_id"
    const val FINGERPRINT = "fingerprint"
    const val CARD = "card"
    const val NAME = "name"
    const val ADDED_AT = "added_at"
    const val RECIPIENT_ID = "recipient_id"

    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $FINGERPRINT TEXT NOT NULL UNIQUE,
        $CARD BLOB NOT NULL,
        $NAME TEXT DEFAULT NULL,
        $ADDED_AT INTEGER NOT NULL,
        $RECIPIENT_ID INTEGER DEFAULT NULL
      )
    """

    val CREATE_INDEXES = arrayOf(
      "CREATE INDEX IF NOT EXISTS mesh_contact_recipient_id_index ON $TABLE_NAME ($RECIPIENT_ID)"
    )
  }

  data class MeshContactRecord(
    val fingerprint: ByteArray,
    val card: ByteArray,
    val name: String?,
    val addedAt: Long,
    val recipientId: RecipientId?
  )

  /** Inserts or replaces the card for [fingerprint]; the first `added_at` is kept. */
  fun upsert(fingerprint: ByteArray, card: ByteArray, name: String?, recipientId: RecipientId?) {
    val existing = getByFingerprint(fingerprint)
    writableDatabase
      .insertInto(TABLE_NAME)
      .values(
        FINGERPRINT to Hex.toStringCondensed(fingerprint),
        CARD to card,
        NAME to name,
        ADDED_AT to (existing?.addedAt ?: System.currentTimeMillis()),
        RECIPIENT_ID to (recipientId ?: existing?.recipientId)?.serialize()
      )
      .run(SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun getByFingerprint(fingerprint: ByteArray): MeshContactRecord? {
    return readableDatabase
      .select()
      .from(TABLE_NAME)
      .where("$FINGERPRINT = ?", Hex.toStringCondensed(fingerprint))
      .run()
      .readToSingleObject { it.toRecord() }
  }

  fun getByRecipientId(recipientId: RecipientId): MeshContactRecord? {
    return readableDatabase
      .select()
      .from(TABLE_NAME)
      .where("$RECIPIENT_ID = ?", recipientId.serialize())
      .run()
      .readToSingleObject { it.toRecord() }
  }

  fun getAll(): List<MeshContactRecord> {
    return readableDatabase
      .select()
      .from(TABLE_NAME)
      .orderBy("$ADDED_AT DESC")
      .run()
      .readToList { it.toRecord() }
  }

  fun remove(fingerprint: ByteArray) {
    writableDatabase
      .delete(TABLE_NAME)
      .where("$FINGERPRINT = ?", Hex.toStringCondensed(fingerprint))
      .run()
  }

  private fun android.database.Cursor.toRecord(): MeshContactRecord {
    return MeshContactRecord(
      fingerprint = Hex.fromStringCondensed(requireNonNullString(FINGERPRINT)),
      card = requireNonNullBlob(CARD),
      name = requireString(NAME),
      addedAt = requireLong(ADDED_AT),
      recipientId = requireLongOrNull(RECIPIENT_ID)?.let { RecipientId.from(it) }
    )
  }
}
