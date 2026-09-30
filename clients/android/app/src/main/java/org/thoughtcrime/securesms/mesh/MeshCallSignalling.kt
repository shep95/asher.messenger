/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import androidx.annotation.WorkerThread
import okio.ByteString.Companion.toByteString
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.messages.CallMessageProcessor
import org.thoughtcrime.securesms.recipients.Recipient
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.Envelope
import java.util.UUID

/**
 * Call signalling (offer / answer / ICE / hangup / busy / opaque) over the mesh, so two mesh
 * contacts on the same LAN can ring each other with no server. The media itself still flows over
 * IP: RingRTC negotiates with ICE host candidates once the signalling has crossed the mesh.
 *
 * Outgoing (hooked in `SignalCallManager.sendCallMessage`): the same `CallMessage` proto the server
 * path would put in a `Content` is serialised here ([toProtoBytes] mirrors the private
 * `SignalServiceMessageSender.createCallContent`), handed to `MeshNode_PrepareCallSignal`,
 * encrypted with the app's Signal session ([MeshCrypto]) and sent as one high-priority bundle.
 *
 * Incoming (`MeshEvent.CallSignal`, tag 11): the bytes are parsed back into a `CallMessage` and
 * routed into [CallMessageProcessor.process], the same entry point the server path uses from
 * `MessageContentProcessor`, with a synthesised envelope: sender = the mesh recipient, device 1,
 * timestamps = now.
 *
 * Routing rule ([shouldRoute]): the callee is a mesh contact and either the device has no chat
 * server connection or the callee is mesh-only (no phone number and no username), all behind the
 * mesh flag and the user's switch.
 */
object MeshCallSignalling {

  private val TAG = Log.tag(MeshCallSignalling::class.java)

  /** True when this call message should travel over the mesh instead of the chat server. */
  @JvmStatic
  @WorkerThread
  fun shouldRoute(recipient: Recipient): Boolean {
    if (!MeshTransport.isEnabled() || MeshTransport.node == null) return false
    if (recipient.isGroup || recipient.isSelf || !MeshContacts.isMeshContact(recipient)) return false
    return isMeshOnly(recipient) || !isServerReachable()
  }

  /** A recipient that exists only as a mesh contact: no phone number and no username to reach it through the server. */
  @JvmStatic
  fun isMeshOnly(recipient: Recipient): Boolean = !recipient.hasE164 && !recipient.username.isPresent

  /** Whether the authenticated chat web socket is currently connected. */
  @JvmStatic
  fun isServerReachable(): Boolean {
    return try {
      AppDependencies.authWebSocket.stateSnapshot == WebSocketConnectionState.CONNECTED
    } catch (e: Exception) {
      Log.w(TAG, "Could not read the web socket state; assuming offline", e)
      false
    }
  }

  /**
   * Sends [callMessage] to [recipient] over the mesh. Returns true when the bundle was handed to
   * the node (delivery is best effort; the bundle lives 90 s), false on any failure.
   */
  @JvmStatic
  @WorkerThread
  fun send(recipient: Recipient, callMessage: SignalServiceCallMessage): Boolean {
    val node = MeshTransport.node ?: return false
    val fingerprint = MeshContacts.fingerprintFor(recipient) ?: return false
    return try {
      val bundleId = MeshOutbox.sendCallSignal(node, fingerprint, toProtoBytes(callMessage))
      Log.i(TAG, "Call message ${describe(callMessage)} for ${MeshContacts.hex(fingerprint)} sent as bundle ${MeshContacts.hex(bundleId)}")
      true
    } catch (e: Exception) {
      Log.w(TAG, "Failed to send call message over the mesh", e)
      MeshStatus.onError("Call signalling failed: ${e.message ?: e.javaClass.simpleName}")
      false
    }
  }

  /** `MeshEvent.CallSignal`: parse and dispatch exactly as an incoming server call message. */
  @WorkerThread
  fun onReceived(from: ByteArray, data: ByteArray) {
    val callMessage = try {
      CallMessage.ADAPTER.decode(data)
    } catch (e: Exception) {
      Log.w(TAG, "Call signal from ${MeshContacts.hex(from)} is not a CallMessage proto (${data.size} bytes)", e)
      return
    }

    if (callMessage.destinationDeviceId != null && callMessage.destinationDeviceId != SignalStore.account.deviceId) {
      Log.i(TAG, "Ignoring mesh call message for device ${callMessage.destinationDeviceId}")
      return
    }

    val senderId = MeshContacts.recipientIdFor(from) ?: MeshContacts.ensureRecipient(from, null)
    val sender = Recipient.resolved(senderId)
    val senderAci = MeshContacts.fingerprintToAci(from)
    val selfAci = SignalStore.account.requireAci()
    val now = System.currentTimeMillis()

    val envelope = Envelope.Builder()
      .type(Envelope.Type.DOUBLE_RATCHET)
      .sourceServiceId(senderAci.toString())
      .sourceDeviceId(1)
      .destinationServiceId(selfAci.toString())
      .clientTimestamp(now)
      .serverTimestamp(now)
      .serverGuid(UUID.randomUUID().toString())
      .urgent(true)
      .build()

    val metadata = EnvelopeMetadata(
      sourceServiceId = senderAci,
      sourceE164 = null,
      sourceDeviceId = 1,
      sealedSender = false,
      groupId = null,
      destinationServiceId = selfAci,
      ciphertextMessageType = CiphertextMessage.WHISPER_TYPE
    )

    val content = Content.Builder().callMessage(callMessage).build()

    Log.i(TAG, "Dispatching mesh call message from ${MeshContacts.hex(from)}: ${describe(callMessage)}")
    CallMessageProcessor.process(sender, envelope, content, metadata, now)
  }

  /** The `CallMessage` proto bytes for [callMessage], built the way `SignalServiceMessageSender.createCallContent` does. */
  @JvmStatic
  fun toProtoBytes(callMessage: SignalServiceCallMessage): ByteArray {
    val builder = CallMessage.Builder()

    if (callMessage.offerMessage.isPresent) {
      val offer = callMessage.offerMessage.get()
      val offerBuilder = CallMessage.Offer.Builder().id(offer.id).type(offer.type.protoType)
      offer.opaque?.let { offerBuilder.opaque(it.toByteString()) }
      builder.offer(offerBuilder.build())
    } else if (callMessage.answerMessage.isPresent) {
      val answer = callMessage.answerMessage.get()
      val answerBuilder = CallMessage.Answer.Builder().id(answer.id)
      answer.opaque?.let { answerBuilder.opaque(it.toByteString()) }
      builder.answer(answerBuilder.build())
    } else if (callMessage.iceUpdateMessages.isPresent) {
      val updates = callMessage.iceUpdateMessages.get().map { update ->
        val iceBuilder = CallMessage.IceUpdate.Builder().id(update.id)
        update.opaque?.let { iceBuilder.opaque(it.toByteString()) }
        iceBuilder.build()
      }
      builder.iceUpdate(updates)
    } else if (callMessage.hangupMessage.isPresent) {
      val hangup = callMessage.hangupMessage.get()
      val protoType = hangup.type.protoType
      val hangupBuilder = CallMessage.Hangup.Builder().type(protoType).id(hangup.id)
      if (protoType != CallMessage.Hangup.Type.HANGUP_NORMAL) {
        hangupBuilder.deviceId(hangup.deviceId)
      }
      builder.hangup(hangupBuilder.build())
    } else if (callMessage.busyMessage.isPresent) {
      builder.busy(CallMessage.Busy.Builder().id(callMessage.busyMessage.get().id).build())
    } else if (callMessage.opaqueMessage.isPresent) {
      val opaque = callMessage.opaqueMessage.get()
      builder.opaque(CallMessage.Opaque.Builder().data_(opaque.opaque.toByteString()).urgency(opaque.urgency.toProto()).build())
    }

    if (callMessage.destinationDeviceId.isPresent) {
      builder.destinationDeviceId(callMessage.destinationDeviceId.get())
    }

    return builder.build().encode()
  }

  private fun describe(message: SignalServiceCallMessage): String = when {
    message.offerMessage.isPresent -> "offer"
    message.answerMessage.isPresent -> "answer"
    message.iceUpdateMessages.isPresent -> "ice x${message.iceUpdateMessages.get().size}"
    message.hangupMessage.isPresent -> "hangup"
    message.busyMessage.isPresent -> "busy"
    message.opaqueMessage.isPresent -> "opaque"
    else -> "empty"
  }

  private fun describe(message: CallMessage): String = when {
    message.offer != null -> "offer"
    message.answer != null -> "answer"
    message.iceUpdate.isNotEmpty() -> "ice x${message.iceUpdate.size}"
    message.hangup != null -> "hangup"
    message.busy != null -> "busy"
    message.opaque != null -> "opaque"
    else -> "empty"
  }
}
