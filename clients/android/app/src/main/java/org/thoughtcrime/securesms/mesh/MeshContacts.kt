/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import androidx.annotation.WorkerThread
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.Hex
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.MeshContactTable.MeshContactRecord
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.mesh.jni.MeshContactCard
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Mesh contacts and how they appear in the rest of the app.
 *
 * Convention: a mesh contact is surfaced as an ordinary [Recipient] whose ACI is the 16-byte
 * device fingerprint rendered as a UUID string (no version or variant bits are adjusted, so the
 * mapping is a bijection and [aciToFingerprint] inverts [fingerprintToAci]). The card's name is
 * stored as the recipient's profile name, so the normal conversation list and thread UI work
 * without knowing about the mesh. Because the fingerprint is derived from the identity key,
 * two devices cannot collide on a recipient unless they share an identity key.
 *
 * The Signal Protocol session for a mesh contact is stored under
 * `SignalProtocolAddress(<that UUID string>, 1)`. meshlink's internal-crypto mode would use the
 * bare hex (`MeshContactCard_AddressName`); the app uses the dashed UUID form of the same bytes
 * because its identity store parses address names as service ids. Nothing on the wire depends on
 * the name, only the app's own stores do.
 */
object MeshContacts {

  private val TAG = Log.tag(MeshContacts::class.java)

  const val FINGERPRINT_LENGTH = 16

  @JvmStatic
  fun fingerprintToAci(fingerprint: ByteArray): ACI {
    require(fingerprint.size == FINGERPRINT_LENGTH) { "fingerprint must be 16 bytes" }
    val buffer = ByteBuffer.wrap(fingerprint)
    return ACI.from(UUID(buffer.long, buffer.long))
  }

  @JvmStatic
  fun aciToFingerprint(aci: ACI): ByteArray {
    val uuid = aci.rawUuid
    return ByteBuffer.allocate(FINGERPRINT_LENGTH).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
  }

  /** The `SignalProtocolAddress` name the app stores a mesh contact's session under. */
  @JvmStatic
  fun protocolAddressName(fingerprint: ByteArray): String = fingerprintToAci(fingerprint).toString()

  @JvmStatic
  fun hex(fingerprint: ByteArray): String = Hex.toStringCondensed(fingerprint).lowercase()

  /** "ab12 cd34 ef56 ..." style grouping for display. */
  @JvmStatic
  fun displayHex(fingerprint: ByteArray): String = hex(fingerprint).chunked(4).joinToString(" ")

  /** Stores the card and creates or updates the recipient that represents it. */
  @WorkerThread
  @JvmStatic
  fun upsert(card: MeshContactCard): RecipientId {
    val fingerprint = card.fingerprint
    val name = card.name.trim().ifEmpty { null }
    val recipientId = ensureRecipient(fingerprint, name)
    SignalDatabase.meshContacts.upsert(fingerprint, card.encode(), name, recipientId)
    Log.i(TAG, "Upserted mesh contact ${hex(fingerprint)} -> $recipientId")
    return recipientId
  }

  /**
   * The recipient for a fingerprint, created if needed. Used for the sender of a message whose
   * card we do not hold yet, so the thread can exist before the beacon arrives.
   */
  @WorkerThread
  @JvmStatic
  fun ensureRecipient(fingerprint: ByteArray, name: String?): RecipientId {
    val aci = fingerprintToAci(fingerprint)
    val recipientId = SignalDatabase.recipients.getOrInsertFromServiceId(aci)
    SignalDatabase.recipients.markRegistered(recipientId, aci)
    val displayName = name ?: "Mesh ${hex(fingerprint).take(8)}"
    val current = Recipient.resolved(recipientId)
    if (name != null || current.profileName.isEmpty) {
      SignalDatabase.recipients.setProfileName(recipientId, ProfileName.fromParts(displayName, null))
    }
    return recipientId
  }

  @WorkerThread
  @JvmStatic
  fun recipientIdFor(fingerprint: ByteArray): RecipientId? {
    return SignalDatabase.meshContacts.getByFingerprint(fingerprint)?.recipientId
      ?: SignalDatabase.recipients.getByAci(fingerprintToAci(fingerprint)).orElse(null)
  }

  /** The fingerprint for a recipient that is a mesh contact, else null. */
  @WorkerThread
  @JvmStatic
  fun fingerprintFor(recipient: Recipient): ByteArray? {
    return SignalDatabase.meshContacts.getByRecipientId(recipient.id)?.fingerprint
      ?: recipient.aci.orElse(null)?.let { aci ->
        val candidate = aciToFingerprint(aci)
        if (SignalDatabase.meshContacts.getByFingerprint(candidate) != null) candidate else null
      }
  }

  @WorkerThread
  @JvmStatic
  fun cardFor(fingerprint: ByteArray): MeshContactCard? {
    return SignalDatabase.meshContacts.getByFingerprint(fingerprint)?.card?.let { bytes ->
      runCatching { MeshContactCard.decode(bytes) }.getOrElse {
        Log.w(TAG, "Stored card for ${hex(fingerprint)} no longer decodes", it)
        null
      }
    }
  }

  @WorkerThread
  @JvmStatic
  fun isMeshContact(recipient: Recipient): Boolean = fingerprintFor(recipient) != null

  @WorkerThread
  @JvmStatic
  fun all(): List<MeshContactRecord> = SignalDatabase.meshContacts.getAll()

  @WorkerThread
  @JvmStatic
  fun remove(fingerprint: ByteArray) {
    SignalDatabase.meshContacts.remove(fingerprint)
    MeshTransport.node?.let { runCatching { it.removeContact(fingerprint) } }
  }
}
