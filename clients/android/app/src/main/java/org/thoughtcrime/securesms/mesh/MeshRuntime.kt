/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mesh.jni.MeshContactCard
import org.thoughtcrime.securesms.mesh.jni.MeshEvent
import org.thoughtcrime.securesms.mesh.jni.MeshNode
import org.thoughtcrime.securesms.mesh.link.BleLink
import org.thoughtcrime.securesms.mesh.link.LanLink
import org.thoughtcrime.securesms.mesh.link.UsbSerialLink
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.notifications.v2.ConversationId
import java.io.File
import kotlin.concurrent.thread

/**
 * Owns the meshlink node while the transport runs: creates it in external-crypto mode with its
 * state under the app's files directory, re-adds the stored contact cards, starts the link
 * transports, and runs the event loop that turns `MeshNode_NextEvent` into app actions.
 *
 * Event mapping:
 *  * `Ciphertext` -> [MeshCrypto.decrypt] -> `deliverPlaintext`, or `defer` when no session exists yet;
 *  * `Message` -> an incoming text in the mesh recipient's thread through `MessageTable.insertMessageInbox`;
 *  * `GroupMessage` / `GroupInvite` -> logged only (see the TODO in [dispatch]);
 *  * `Contact` -> [MeshContacts.upsert];
 *  * `Delivered` -> [MeshOutbox.onDelivered];
 *  * `Neighbour` / `LinkClosed` -> [MeshStatus];
 *  * `AttachmentProgress` (tag 9) -> logged;
 *  * `Attachment` (tag 10) -> an incoming media message in the sender's thread, the data going
 *    through a `BlobProvider` uri and a [UriAttachment] like any locally produced attachment;
 *  * `CallSignal` (tag 11) -> [MeshCallSignalling.onReceived].
 */
object MeshRuntime {

  private val TAG = Log.tag(MeshRuntime::class.java)

  private const val EVENT_POLL_MS = 1000
  private const val STATS_EVERY_POLLS = 5
  private const val ANTI_ENTROPY_SECS = 15

  private var node: MeshNode? = null
  private var eventThread: Thread? = null
  private var ble: BleLink? = null
  private var usb: UsbSerialLink? = null
  private var lan: LanLink? = null

  @Volatile
  private var running = false

  val isRunning: Boolean get() = running

  @WorkerThread
  @Synchronized
  fun start(context: Context) {
    if (node != null) {
      Log.i(TAG, "Already running.")
      return
    }

    val appContext = context.applicationContext
    val identity = MeshIdentityManager.getOrCreate()
    val stateDir = File(appContext.filesDir, "mesh").apply { mkdirs() }
    val statePath = File(stateDir, "node.state").absolutePath

    val created = MeshNode.create(identity, statePath, externalCrypto = true, antiEntropySecs = ANTI_ENTROPY_SECS)
    node = created
    MeshTransport.node = created
    running = true

    for (record in MeshContacts.all()) {
      try {
        created.addContact(record.card)
      } catch (e: Exception) {
        Log.w(TAG, "Stored card for ${MeshContacts.hex(record.fingerprint)} was rejected by the node", e)
      }
    }

    MeshStatus.setRunning(true)
    Log.i(TAG, "Mesh node ${MeshContacts.hex(created.fingerprint)} started; state at $statePath")

    eventThread = thread(name = "mesh-events", isDaemon = true) { eventLoop(appContext, created) }

    if (SignalStore.mesh.bleEnabled) {
      ble = BleLink(appContext, created).also { it.start() }
    }
    if (SignalStore.mesh.usbEnabled) {
      usb = UsbSerialLink(appContext, created).also { it.start() }
    }
    if (SignalStore.mesh.lanEnabled) {
      lan = LanLink(appContext, created).also { it.start() }
    }

    try {
      created.broadcastCard()
    } catch (e: Exception) {
      Log.w(TAG, "Initial beacon failed", e)
    }
  }

  @Synchronized
  fun stop() {
    val current = node ?: return
    Log.i(TAG, "Stopping mesh node.")
    running = false

    ble?.stop()
    ble = null
    usb?.stop()
    usb = null
    lan?.stop()
    lan = null

    eventThread?.let { t ->
      t.interrupt()
      runCatching { t.join(3000) }
    }
    eventThread = null

    runCatching { current.flush() }.onFailure { Log.w(TAG, "Flush failed", it) }
    MeshTransport.node = null
    node = null
    current.close()
    MeshStatus.setRunning(false)
  }

  /** Re-broadcasts our card so nearby nodes learn it (settings screen button). */
  fun broadcastCard(): Boolean {
    val current = node ?: return false
    return runCatching { current.broadcastCard() }.isSuccess
  }

  /**
   * Waits up to [timeoutMs] for the node to be up (the foreground service starts it on a worker
   * thread), for callers that just switched the mesh on and need the node right away (restore).
   */
  @WorkerThread
  fun awaitNode(timeoutMs: Long): MeshNode? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      MeshTransport.node?.let { return it }
      try {
        Thread.sleep(200)
      } catch (e: InterruptedException) {
        return null
      }
    }
    return MeshTransport.node
  }

  private fun eventLoop(context: Context, node: MeshNode) {
    var polls = 0
    while (running && !Thread.currentThread().isInterrupted) {
      val event = try {
        node.nextEvent(EVENT_POLL_MS)
      } catch (e: Exception) {
        if (!running) return
        Log.w(TAG, "Event poll failed", e)
        null
      }

      if (event != null) {
        try {
          dispatch(context, node, event)
        } catch (e: Exception) {
          Log.w(TAG, "Event dispatch failed for ${event.javaClass.simpleName}", e)
        }
      }

      if (++polls % STATS_EVERY_POLLS == 0 && running) {
        runCatching { MeshStatus.onStats(node.stats()) }
      }
    }
  }

  @WorkerThread
  private fun dispatch(context: Context, node: MeshNode, event: MeshEvent) {
    when (event) {
      is MeshEvent.Ciphertext -> {
        try {
          val plaintext = MeshCrypto.decrypt(event.from, event.messageType, event.ciphertext)
          node.deliverPlaintext(event.bundleId, plaintext)
        } catch (e: MeshCrypto.NotYetDecryptable) {
          Log.i(TAG, "Deferring bundle ${MeshContacts.hex(event.bundleId)}: ${e.message}")
          node.defer(event.bundleId)
        } catch (e: Exception) {
          Log.w(TAG, "Could not decrypt bundle ${MeshContacts.hex(event.bundleId)}; deferring", e)
          node.defer(event.bundleId)
        }
      }

      is MeshEvent.Message -> insertIncomingText(context, event)

      is MeshEvent.GroupMessage -> {
        // TODO(mesh-groups): map the 16-byte mesh group id to a local group (a GroupId.V1-style
        // local-only group keyed by the mesh id, members from `MeshNode_Group`) and insert the
        // text into that thread with `from` as the author. Mesh groups have no UI yet.
        Log.i(TAG, "Group message for mesh group ${MeshContacts.hex(event.group)} from ${MeshContacts.hex(event.from)} (${event.plaintext.size} bytes) ignored: mesh groups are not mapped yet")
      }

      is MeshEvent.GroupInvite -> {
        // TODO(mesh-groups): create the local group from `MeshNode_Group(event.group)` (name,
        // members, creator) and add every member as a mesh contact via the card shares that
        // accompany the invite.
        Log.i(TAG, "Invite to mesh group ${MeshContacts.hex(event.group)} from ${MeshContacts.hex(event.from)} ignored: mesh groups are not mapped yet")
      }

      is MeshEvent.Contact -> {
        val bytes = node.contact(event.fingerprint) ?: return
        val card = MeshContactCard.decode(bytes)
        MeshContacts.upsert(card)
      }

      is MeshEvent.Delivered -> MeshOutbox.onDelivered(event.bundleId)

      is MeshEvent.Neighbour -> {
        Log.i(TAG, "Neighbour ${MeshContacts.hex(event.fingerprint)} on link ${event.link}")
        MeshStatus.onNeighbour(event.link, event.fingerprint)
      }

      is MeshEvent.LinkClosed -> MeshStatus.onLinkDetached(event.link)

      is MeshEvent.AttachmentProgress -> {
        Log.i(TAG, "Attachment ${MeshContacts.hex(event.transfer)} from ${MeshContacts.hex(event.from)}: ${event.received}/${event.total} chunks")
      }

      is MeshEvent.Attachment -> insertIncomingAttachment(context, event)

      is MeshEvent.CallSignal -> MeshCallSignalling.onReceived(event.from, event.data)
    }
  }

  /**
   * A complete attachment (already verified against its manifest by the core). The bytes are
   * parked in an in-memory blob so `MessageTable.insertMessageInbox` can pull them through
   * `PartAuthority` exactly as it does for any attachment that already has local data.
   */
  @WorkerThread
  private fun insertIncomingAttachment(context: Context, event: MeshEvent.Attachment) {
    val senderId = MeshContacts.recipientIdFor(event.from) ?: MeshContacts.ensureRecipient(event.from, null)
    val now = System.currentTimeMillis()
    val mime = event.mime.ifBlank { "application/octet-stream" }
    val fileName = event.name.ifBlank { defaultFileName(event.kind, mime) }
    val voiceNote = event.kind == MeshEvent.ATTACHMENT_KIND_VOICE_NOTE

    val blobs = AppDependencies.blobs
    val uri = blobs.forData(event.data)
      .withMimeType(mime)
      .withFileName(fileName)
      .createForSingleSessionInMemory()

    try {
      val attachment = UriAttachment(
        uri,
        mime,
        AttachmentTable.TRANSFER_PROGRESS_DONE,
        event.data.size.toLong(),
        fileName,
        voiceNote,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null
      )

      val incoming = IncomingMessage(
        type = MessageType.NORMAL,
        from = senderId,
        sentTimeMillis = now,
        serverTimeMillis = -1,
        receivedTimeMillis = now,
        body = null,
        attachments = listOf(attachment)
      )

      val result = SignalDatabase.messages.insertMessageInbox(incoming).orElse(null)
      if (result == null) {
        Log.w(TAG, "Incoming mesh attachment from ${MeshContacts.hex(event.from)} was not inserted")
        return
      }

      AppDependencies.messageNotifier.updateNotification(context, ConversationId.forConversation(result.threadId))
      Log.i(TAG, "Inserted mesh attachment ${MeshContacts.hex(event.transfer)} ($mime, ${event.data.size} bytes, kind ${event.kind}) as message ${result.messageId}")
    } finally {
      runCatching { blobs.delete(context, uri) }
    }
  }

  private fun defaultFileName(kind: Int, mime: String): String {
    val ext = mime.substringAfter('/', "bin").substringBefore(';').ifBlank { "bin" }
    return when (kind) {
      MeshEvent.ATTACHMENT_KIND_IMAGE -> "image.$ext"
      MeshEvent.ATTACHMENT_KIND_VOICE_NOTE -> "voice-note.$ext"
      else -> "file.$ext"
    }
  }

  @WorkerThread
  private fun insertIncomingText(context: Context, event: MeshEvent.Message) {
    val body = String(event.plaintext, Charsets.UTF_8)
    val senderId = MeshContacts.recipientIdFor(event.from) ?: MeshContacts.ensureRecipient(event.from, null)
    val now = System.currentTimeMillis()

    val incoming = IncomingMessage(
      type = MessageType.NORMAL,
      from = senderId,
      sentTimeMillis = now,
      serverTimeMillis = -1,
      receivedTimeMillis = now,
      body = body
    )

    val result = SignalDatabase.messages.insertMessageInbox(incoming).orElse(null)
    if (result == null) {
      Log.w(TAG, "Incoming mesh message from ${MeshContacts.hex(event.from)} was not inserted")
      return
    }

    AppDependencies.messageNotifier.updateNotification(context, ConversationId.forConversation(result.threadId))
    Log.i(TAG, "Inserted mesh message ${result.messageId} from ${MeshContacts.hex(event.from)} (known sender: ${event.knownSender})")
  }
}
