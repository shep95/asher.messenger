/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mesh.jni.MeshContactCard
import org.thoughtcrime.securesms.mesh.jni.MeshEvent
import org.thoughtcrime.securesms.mesh.jni.MeshNode
import org.thoughtcrime.securesms.mesh.jni.MeshPrepared
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.notifications.v2.ConversationId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends the app's outgoing messages over the mesh instead of the chat server.
 *
 * Flow per message: `MeshNode_PrepareText` for the body and `MeshNode_PrepareAttachment` for each
 * attachment (a manifest plus chunk plaintexts, each with its own ack commitment) ->
 * [MeshCrypto.encrypt] with the app's session for every prepared entry, in order ->
 * `MeshNode_SendCiphertext`. The bundle ids of the text and of every attachment MANIFEST are
 * remembered; the message is marked delivered when all of them are acknowledged (chunk acks are
 * ignored, per the v3 contract). The map is in memory only: after a process restart an ack for
 * an older message is ignored.
 *
 * Attachments are read in full from the app's attachment store (`PartAuthority`) up to
 * [MAX_ATTACHMENT_BYTES]; a larger one fails the whole message with a visible error, because the
 * core refuses it (`MeshError::TooLarge`) and there is no partial send.
 */
object MeshOutbox {

  private val TAG = Log.tag(MeshOutbox::class.java)

  /** The core's limit for one attachment (`MeshNode_PrepareAttachment`). */
  const val MAX_ATTACHMENT_BYTES = 4 * 1024 * 1024

  private class Pending(val messageId: Long, val recipientId: RecipientId, val dateSent: Long, tracked: Int) {
    val remaining = AtomicInteger(tracked)
  }

  /** Thrown when an attachment cannot travel over the mesh; the message fails with its message. */
  class AttachmentTooLarge(message: String) : Exception(message)

  /** bundle id (hex) -> message. Several bundles may point at the same [Pending]. */
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
   * the mesh: the body as a text envelope and every attachment as a manifest plus chunks.
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

      val card = ensureCard(node, fingerprint)

      // Everything is prepared (and so every size check happens) before the first bundle is handed
      // to the mesh, so a message that cannot travel leaves nothing half-sent behind.
      val batches = ArrayList<List<MeshPrepared>>()
      val body = message.body
      if (body.isNotEmpty() || message.attachments.isEmpty()) {
        batches += listOf(node.prepareText(fingerprint, body.toByteArray(Charsets.UTF_8)))
      }
      for (attachment in message.attachments) {
        batches += prepareAttachment(context, node, fingerprint, attachment)
      }

      val tracked = Pending(messageId, recipient.id, record.dateSent, tracked = batches.size)
      for (batch in batches) {
        val bundleIds = sendPrepared(node, fingerprint, card, batch)
        // The first entry of a batch is the text itself, or the attachment's manifest.
        pending[MeshContacts.hex(bundleIds.first())] = tracked
      }

      for (attachment in message.attachments) {
        val id = (attachment as? DatabaseAttachment)?.attachmentId ?: continue
        SignalDatabase.attachments.setTransferState(messageId, id, AttachmentTable.TRANSFER_PROGRESS_DONE)
      }

      MeshStatus.onCarryingChanged(recipient.id, pendingCountFor(recipient.id))
      SignalDatabase.messages.markAsSent(messageId)
      SignalDatabase.threads.updateSilently(record.threadId, false)
      Log.i(TAG, "Message $messageId handed to the mesh in ${batches.size} bundle group(s) (${message.attachments.size} attachment(s))")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to send message $messageId over the mesh", e)
      MeshStatus.onError(e.message ?: e.javaClass.simpleName)
      fail(context, recipient, messageId)
    }
  }

  /**
   * Sends opaque call signalling to a mesh contact (see [MeshCallSignalling]). Returns the bundle
   * id, or throws; nothing is tracked for delivery because call messages are fire-and-forget.
   */
  @WorkerThread
  fun sendCallSignal(node: MeshNode, fingerprint: ByteArray, data: ByteArray): ByteArray {
    val card = ensureCard(node, fingerprint)
    val prepared = node.prepareCallSignal(fingerprint, data)
    return sendPrepared(node, fingerprint, card, listOf(prepared)).first()
  }

  /** `Event::Delivered`: the recipient acknowledged the bundle. */
  @WorkerThread
  fun onDelivered(bundleId: ByteArray) {
    val entry = pending.remove(MeshContacts.hex(bundleId)) ?: return
    val left = entry.remaining.decrementAndGet()
    if (left > 0) {
      Log.i(TAG, "Message ${entry.messageId}: one bundle acknowledged, $left to go")
      return
    }
    SignalDatabase.messages.incrementDeliveryReceiptCount(entry.dateSent, entry.recipientId, System.currentTimeMillis())
    MeshStatus.onCarryingChanged(entry.recipientId, pendingCountFor(entry.recipientId))
    Log.i(TAG, "Message ${entry.messageId} delivered over the mesh")
  }

  fun pendingCountFor(recipientId: RecipientId): Int = pending.values.filter { it.recipientId == recipientId }.distinct().size

  // ---- helpers ---------------------------------------------------------------

  /** Makes sure the node holds the contact's card (it may have been pruned) and returns it for the session start. */
  @WorkerThread
  private fun ensureCard(node: MeshNode, fingerprint: ByteArray): MeshContactCard? {
    val card = MeshContacts.cardFor(fingerprint)
    if (card != null && node.contact(fingerprint) == null) {
      node.addContact(card.encode())
    }
    return card
  }

  /** Encrypts and hands over every prepared entry in order; returns the bundle ids in the same order. */
  @WorkerThread
  private fun sendPrepared(node: MeshNode, fingerprint: ByteArray, card: MeshContactCard?, prepared: List<MeshPrepared>): List<ByteArray> {
    return prepared.map { entry ->
      val ciphertext = MeshCrypto.encrypt(fingerprint, card, entry.plaintext)
      node.sendCiphertext(fingerprint, entry.commit, ciphertext.type, ciphertext.serialize())
    }
  }

  @WorkerThread
  @Throws(AttachmentTooLarge::class, IOException::class)
  private fun prepareAttachment(context: Context, node: MeshNode, fingerprint: ByteArray, attachment: Attachment): List<MeshPrepared> {
    val uri = attachment.uri ?: throw IOException("Attachment has no local data")
    val name = attachment.fileName ?: ""
    if (attachment.size > MAX_ATTACHMENT_BYTES) {
      throw AttachmentTooLarge("${name.ifEmpty { "Attachment" }} is ${attachment.size / 1024} KiB; the mesh carries at most ${MAX_ATTACHMENT_BYTES / 1024 / 1024} MiB")
    }

    val data = PartAuthority.getAttachmentStream(context, uri).use { readUpTo(it, MAX_ATTACHMENT_BYTES + 1) }
    if (data.size > MAX_ATTACHMENT_BYTES) {
      throw AttachmentTooLarge("${name.ifEmpty { "Attachment" }} is larger than ${MAX_ATTACHMENT_BYTES / 1024 / 1024} MiB; the mesh cannot carry it")
    }

    val mime = attachment.contentType ?: "application/octet-stream"
    val kind = when {
      attachment.voiceNote -> MeshEvent.ATTACHMENT_KIND_VOICE_NOTE
      MediaUtil.isImageType(mime) -> MeshEvent.ATTACHMENT_KIND_IMAGE
      else -> MeshEvent.ATTACHMENT_KIND_FILE
    }
    val prepared = node.prepareAttachment(fingerprint, kind, name, mime, data)
    check(prepared.isNotEmpty()) { "PrepareAttachment returned no entries" }
    Log.i(TAG, "Attachment ${name.ifEmpty { mime }} (${data.size} bytes, kind $kind) prepared as ${prepared.size} bundle(s)")
    return prepared
  }

  private fun readUpTo(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (total < limit) {
      val n = input.read(buffer, 0, minOf(buffer.size, limit - total))
      if (n < 0) break
      out.write(buffer, 0, n)
      total += n
    }
    return out.toByteArray()
  }

  private fun fail(context: Context, recipient: Recipient, messageId: Long) {
    SignalDatabase.messages.markAsSentFailed(messageId)
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    AppDependencies.messageNotifier.notifyMessageDeliveryFailed(context, recipient, ConversationId.forConversation(threadId))
  }
}
