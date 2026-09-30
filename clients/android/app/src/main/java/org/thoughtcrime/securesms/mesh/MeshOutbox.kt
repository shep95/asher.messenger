/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.notifications.v2.ConversationId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends the app's outgoing text messages over the mesh instead of the chat server.
 *
 * Flow per message: `MeshNode_PrepareText` (pads the plaintext into a meshlink envelope and
 * returns the ack commitment) -> [MeshCrypto.encrypt] with the app's session -> `MeshNode_SendCiphertext`.
 * The returned bundle id is remembered so `Event::Delivered` can mark the message delivered.
 * The map is in memory only: after a process restart an ack for an older message is ignored.
 */
object MeshOutbox {

  private val TAG = Log.tag(MeshOutbox::class.java)

  private data class Pending(val messageId: Long, val recipientId: RecipientId, val dateSent: Long)

  /** bundle id (hex) -> message. */
  private val pending = ConcurrentHashMap<String, Pending>()

  /** True when the flag is on, the node is running and [recipient] is a mesh contact. */
  @JvmStatic
  @WorkerThread
  fun shouldRoute(recipient: Recipient): Boolean {
    if (!MeshTransport.isEnabled() || recipient.isGroup || recipient.isSelf) return false
    return MeshContacts.isMeshContact(recipient)
  }

  /**
   * Routes an already-inserted outgoing message (see `MessageSender.sendMessageInternal`) over
   * the mesh. Attachments are not carried: only the body travels.
   */
  @JvmStatic
  @WorkerThread
  fun send(context: Context, recipient: Recipient, messageId: Long) {
    val node = MeshTransport.node
    if (node == null) {
      Log.w(TAG, "Mesh node not running; failing message $messageId")
      fail(context, recipient, messageId)
      return
    }

    val fingerprint = MeshContacts.fingerprintFor(recipient)
    if (fingerprint == null) {
      Log.w(TAG, "Recipient ${recipient.id} is not a mesh contact; failing message $messageId")
      fail(context, recipient, messageId)
      return
    }

    try {
      val message = SignalDatabase.messages.getOutgoingMessage(messageId)
      val record = SignalDatabase.messages.getMessageRecord(messageId)
      SignalDatabase.messages.markAsSending(messageId)

      if (message.attachments.isNotEmpty()) {
        Log.w(TAG, "Message $messageId has ${message.attachments.size} attachment(s); only the text body travels over the mesh")
      }

      val card = MeshContacts.cardFor(fingerprint)
      if (card != null && node.contact(fingerprint) == null) {
        node.addContact(card.encode())
      }

      val prepared = node.prepareText(fingerprint, message.body.toByteArray(Charsets.UTF_8))
      val ciphertext = MeshCrypto.encrypt(fingerprint, card, prepared.plaintext)
      val bundleId = node.sendCiphertext(fingerprint, prepared.commit, ciphertext.type, ciphertext.serialize())

      pending[MeshContacts.hex(bundleId)] = Pending(messageId, recipient.id, record.dateSent)
      MeshStatus.onCarryingChanged(recipient.id, pendingCountFor(recipient.id))

      SignalDatabase.messages.markAsSent(messageId)
      SignalDatabase.threads.updateSilently(record.threadId, false)
      Log.i(TAG, "Message $messageId handed to the mesh as bundle ${MeshContacts.hex(bundleId)}")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to send message $messageId over the mesh", e)
      MeshStatus.onError(e.message ?: e.javaClass.simpleName)
      fail(context, recipient, messageId)
    }
  }

  /** `Event::Delivered`: the recipient acknowledged the bundle. */
  @WorkerThread
  fun onDelivered(bundleId: ByteArray) {
    val entry = pending.remove(MeshContacts.hex(bundleId)) ?: return
    SignalDatabase.messages.incrementDeliveryReceiptCount(entry.dateSent, entry.recipientId, System.currentTimeMillis())
    MeshStatus.onCarryingChanged(entry.recipientId, pendingCountFor(entry.recipientId))
    Log.i(TAG, "Message ${entry.messageId} delivered over the mesh")
  }

  fun pendingCountFor(recipientId: RecipientId): Int = pending.values.count { it.recipientId == recipientId }

  private fun fail(context: Context, recipient: Recipient, messageId: Long) {
    SignalDatabase.messages.markAsSentFailed(messageId)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    AppDependencies.messageNotifier.notifyMessageDeliveryFailed(context, recipient, ConversationId.forConversation(threadId))
  }
}
