package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.thoughtcrime.securesms.database.SQLiteDatabase

/** Asher offline mesh: contact cards learned over meshlink, keyed by device fingerprint. */
@Suppress("ClassName")
object V329_AddMeshContactTable : SignalDatabaseMigration {

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS mesh_contact (
        _id INTEGER PRIMARY KEY AUTOINCREMENT,
        fingerprint TEXT NOT NULL UNIQUE,
        card BLOB NOT NULL,
        name TEXT DEFAULT NULL,
        added_at INTEGER NOT NULL,
        recipient_id INTEGER DEFAULT NULL
      )
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS mesh_contact_recipient_id_index ON mesh_contact (recipient_id)")
  }
}
