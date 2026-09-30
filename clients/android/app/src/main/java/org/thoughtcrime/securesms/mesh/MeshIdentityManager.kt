/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mesh.jni.MeshContactCard
import org.thoughtcrime.securesms.mesh.jni.MeshIdentity
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Owns the device's mesh identity: the account's ACI identity key pair plus one signed prekey and
 * one Kyber prekey that are advertised in the contact card.
 *
 * The identity is built once with `MeshIdentity_FromIdentityKeyPair`, exported into
 * [SignalStore.mesh] and re-imported on later starts so the card (and its prekey ids) stays
 * stable. Both prekey records are installed into the app's ACI prekey tables under their ids, so
 * a PreKeySignalMessage that references them decrypts through the normal stores. Installation is
 * idempotent and repeated on every start because the app's prekey housekeeping may prune old
 * signed prekeys; the Kyber key is stored as last-resort so it survives use.
 */
object MeshIdentityManager {

  private val TAG = Log.tag(MeshIdentityManager::class.java)

  /** `identity::MAX_NAME_BYTES`. */
  private const val MAX_NAME_BYTES = 64

  @Volatile
  private var identity: MeshIdentity? = null

  @WorkerThread
  @Synchronized
  fun getOrCreate(): MeshIdentity {
    identity?.let { return it }

    val store = SignalStore.mesh
    val accountIdentity = SignalStore.account.aciIdentityKey

    var result: MeshIdentity? = store.identityExport?.let { blob ->
      try {
        val imported = MeshIdentity.import(blob)
        if (imported.card().identityKey == accountIdentity.publicKey) {
          imported
        } else {
          Log.w(TAG, "Stored mesh identity does not match the account identity key; rebuilding.")
          null
        }
      } catch (e: Exception) {
        Log.w(TAG, "Stored mesh identity failed to import; rebuilding.", e)
        null
      }
    }

    if (result == null) {
      result = MeshIdentity.fromIdentityKeyPair(accountIdentity, SignalStore.account.registrationId, profileName())
      store.identityExport = result.export()
      Log.i(TAG, "Created mesh identity ${MeshContacts.hex(result.fingerprint)}")
    }

    installPreKeys(result)
    identity = result
    return result
  }

  /** Forgets the cached identity (after re-registration); the next [getOrCreate] rebuilds it if the key changed. */
  @Synchronized
  fun reset() {
    identity = null
  }

  @WorkerThread
  fun fingerprint(): ByteArray = getOrCreate().fingerprint

  @WorkerThread
  fun card(): MeshContactCard = getOrCreate().card()

  /** What the QR screen shows: `MeshContactCard_ToBase64` of our card. */
  @WorkerThread
  fun contactCardBase64(): String = card().toBase64()

  @WorkerThread
  private fun installPreKeys(identity: MeshIdentity) {
    val aci = SignalStore.account.aci
    if (aci == null) {
      Log.w(TAG, "No ACI yet; mesh prekeys not installed.")
      return
    }

    val signed = identity.signedPreKeyRecord()
    val kyber = identity.kyberPreKeyRecord()

    SignalDatabase.signedPreKeys.insert(aci, signed.id, signed)
    SignalDatabase.kyberPreKeys.insert(aci, kyber.id, kyber, lastResort = true)

    SignalStore.mesh.signedPreKeyId = signed.id
    SignalStore.mesh.kyberPreKeyId = kyber.id
  }

  private fun profileName(): String {
    val name = try {
      Recipient.self().profileName.toString().trim()
    } catch (e: Exception) {
      ""
    }
    return truncateUtf8(name.ifEmpty { "Asher" }, MAX_NAME_BYTES)
  }

  private fun truncateUtf8(text: String, maxBytes: Int): String {
    var out = text
    while (out.toByteArray(Charsets.UTF_8).size > maxBytes) {
      out = out.dropLast(1)
    }
    return out
  }
}
