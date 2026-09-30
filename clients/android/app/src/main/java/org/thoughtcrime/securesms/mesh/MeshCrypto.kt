/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mesh.jni.MeshContactCard

/**
 * Signal Protocol for mesh traffic, using the app's own ACI stores so a mesh session and an
 * internet session with the same device would be one ratchet, not two.
 *
 * Addresses: `SignalProtocolAddress(MeshContacts.protocolAddressName(fingerprint), 1)`; see
 * [MeshContacts] for why the UUID rendering of the fingerprint is used.
 */
object MeshCrypto {

  private val TAG = Log.tag(MeshCrypto::class.java)

  /** libsignal `CiphertextMessage` types as carried in the bundle payload. */
  const val TYPE_SIGNAL_MESSAGE = CiphertextMessage.WHISPER_TYPE
  const val TYPE_PREKEY_MESSAGE = CiphertextMessage.PREKEY_TYPE

  /** Thrown when a message cannot be decrypted yet; the caller should `defer` the bundle. */
  class NotYetDecryptable(message: String, cause: Throwable?) : Exception(message, cause)

  private val store get() = AppDependencies.protocolStore.aci()

  private fun localAddress(): SignalProtocolAddress = SignalStore.account.requireAci().toProtocolAddress(SignalStore.account.deviceId)

  private fun remoteAddress(fingerprint: ByteArray): SignalProtocolAddress = SignalProtocolAddress(MeshContacts.protocolAddressName(fingerprint), 1)

  /**
   * Encrypts a prepared plaintext for [fingerprint]. Starts the session from the contact card's
   * prekey bundle when none exists (PQXDH; the first message is a PreKeySignalMessage).
   */
  @WorkerThread
  @Throws(NoSessionException::class)
  fun encrypt(fingerprint: ByteArray, card: MeshContactCard?, plaintext: ByteArray): CiphertextMessage {
    val remote = remoteAddress(fingerprint)
    ReentrantSessionLock.INSTANCE.acquire().use {
      if (!store.containsSession(remote)) {
        val bundle = (card ?: MeshContacts.cardFor(fingerprint))?.preKeyBundle()
          ?: throw NoSessionException("No session and no contact card for ${MeshContacts.hex(fingerprint)}")
        Log.i(TAG, "Starting mesh session with ${MeshContacts.hex(fingerprint)} from its card")
        SessionBuilder(store, remote, localAddress()).process(bundle)
      }
      return SessionCipher(store, localAddress(), remote).encrypt(plaintext)
    }
  }

  /**
   * Decrypts an incoming mesh ciphertext. Throws [NotYetDecryptable] when there is no usable
   * session or the message references a prekey we do not hold, so the bundle can be deferred and
   * re-announced later; other failures propagate.
   */
  @WorkerThread
  @Throws(NotYetDecryptable::class)
  fun decrypt(fingerprint: ByteArray, messageType: Int, ciphertext: ByteArray): ByteArray {
    val remote = remoteAddress(fingerprint)
    ReentrantSessionLock.INSTANCE.acquire().use {
      val cipher = SessionCipher(store, localAddress(), remote)
      try {
        return when (messageType) {
          TYPE_PREKEY_MESSAGE -> cipher.decrypt(PreKeySignalMessage(ciphertext))
          TYPE_SIGNAL_MESSAGE -> cipher.decrypt(SignalMessage(ciphertext))
          else -> throw InvalidMessageException("Unsupported mesh message type $messageType")
        }
      } catch (e: NoSessionException) {
        throw NotYetDecryptable("No session with ${MeshContacts.hex(fingerprint)}", e)
      } catch (e: InvalidKeyIdException) {
        // Our mesh prekeys may have been pruned; reinstall and let the sender's retry find them.
        MeshIdentityManager.reset()
        runCatching { MeshIdentityManager.getOrCreate() }
        throw NotYetDecryptable("Unknown prekey id in message from ${MeshContacts.hex(fingerprint)}", e)
      } catch (e: InvalidMessageException) {
        throw NotYetDecryptable("Invalid message from ${MeshContacts.hex(fingerprint)}", e)
      }
    }
  }
}
