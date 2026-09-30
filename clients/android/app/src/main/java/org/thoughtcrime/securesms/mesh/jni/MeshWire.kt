/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.jni

import java.nio.ByteBuffer

/**
 * Decoders for the compact byte strings the meshlink bridge returns
 * (`rust/bridge/shared/types/src/mesh.rs`: `encode_event`, `encode_prepared`, `encode_stats`).
 * Integers are big-endian; fixed fields have no prefix; variable fields carry a u16 length.
 */
private class WireReader(bytes: ByteArray) {
  private val buf: ByteBuffer = ByteBuffer.wrap(bytes)

  fun u8(): Int = buf.get().toInt() and 0xFF
  fun u16(): Int = buf.short.toInt() and 0xFFFF
  fun u64(): Long = buf.long
  fun fixed(n: Int): ByteArray = ByteArray(n).also { buf.get(it) }
  fun bytes(): ByteArray = fixed(u16())
  val remaining: Int get() = buf.remaining()
}

/** One event from `MeshNode_NextEvent`. Tags follow `event_tag` in the bridge. */
sealed class MeshEvent {
  /** External-crypto mode: decrypt [ciphertext] for [from] and answer with `deliverPlaintext` or `defer`. */
  data class Ciphertext(
    val from: ByteArray,
    val bundleId: ByteArray,
    val ackCommit: ByteArray,
    /** libsignal `CiphertextMessage` type: 3 = PreKeySignalMessage, 2 = SignalMessage. */
    val messageType: Int,
    val ciphertext: ByteArray
  ) : MeshEvent()

  /** A one-to-one message for us was decrypted (after `deliverPlaintext`). [plaintext] is the envelope body. */
  data class Message(val from: ByteArray, val bundleId: ByteArray, val knownSender: Boolean, val plaintext: ByteArray) : MeshEvent()

  data class GroupMessage(val group: ByteArray, val from: ByteArray, val bundleId: ByteArray, val plaintext: ByteArray) : MeshEvent()

  data class GroupInvite(val group: ByteArray, val from: ByteArray) : MeshEvent()

  /** A contact card was learned (beacon, share or explicit add). */
  data class Contact(val fingerprint: ByteArray) : MeshEvent()

  /** A bundle we sent was acknowledged by its recipient. */
  data class Delivered(val bundleId: ByteArray) : MeshEvent()

  /** A neighbour identified itself on a link (unauthenticated). */
  data class Neighbour(val link: Long, val fingerprint: ByteArray) : MeshEvent()

  data class LinkClosed(val link: Long) : MeshEvent()

  companion object {
    const val TAG_CIPHERTEXT = 1
    const val TAG_MESSAGE = 2
    const val TAG_GROUP_MESSAGE = 3
    const val TAG_GROUP_INVITE = 4
    const val TAG_CONTACT = 5
    const val TAG_DELIVERED = 6
    const val TAG_NEIGHBOUR = 7
    const val TAG_LINK_CLOSED = 8

    /** Returns null for an empty buffer (timeout) or an unknown tag. */
    fun decode(bytes: ByteArray): MeshEvent? {
      if (bytes.isEmpty()) return null
      val r = WireReader(bytes)
      return when (r.u8()) {
        TAG_CIPHERTEXT -> Ciphertext(from = r.fixed(16), bundleId = r.fixed(16), ackCommit = r.fixed(16), messageType = r.u8(), ciphertext = r.bytes())
        TAG_MESSAGE -> Message(from = r.fixed(16), bundleId = r.fixed(16), knownSender = r.u8() != 0, plaintext = r.bytes())
        TAG_GROUP_MESSAGE -> GroupMessage(group = r.fixed(16), from = r.fixed(16), bundleId = r.fixed(16), plaintext = r.bytes())
        TAG_GROUP_INVITE -> GroupInvite(group = r.fixed(16), from = r.fixed(16))
        TAG_CONTACT -> Contact(fingerprint = r.fixed(16))
        TAG_DELIVERED -> Delivered(bundleId = r.fixed(16))
        TAG_NEIGHBOUR -> Neighbour(link = r.u64(), fingerprint = r.fixed(16))
        TAG_LINK_CLOSED -> LinkClosed(link = r.u64())
        else -> null
      }
    }
  }
}

/** One item of a prepared list: the padded plaintext the app must encrypt for [to], and the commitment to hand back. */
data class MeshPrepared(val to: ByteArray, val commit: ByteArray, val plaintext: ByteArray) {
  companion object {
    /** `[count u16]` then `[to 16][commit 16][plaintext u16-len]` per item. */
    fun decodeList(bytes: ByteArray): List<MeshPrepared> {
      val r = WireReader(bytes)
      val count = r.u16()
      return List(count) { MeshPrepared(to = r.fixed(16), commit = r.fixed(16), plaintext = r.bytes()) }
    }
  }
}

/** Node counters, eighteen big-endian u64 in the order of the Rust `Stats` fields. */
data class MeshStats(
  val framesIn: Long,
  val framesDroppedRate: Long,
  val framesDroppedInvalid: Long,
  val bundlesIn: Long,
  val bundlesDroppedInvalid: Long,
  val bundlesDroppedRate: Long,
  val bundlesDroppedQuota: Long,
  val bundlesForwarded: Long,
  val messagesDelivered: Long,
  val messagesUndecryptable: Long,
  val messagesDeferred: Long,
  val acksRejected: Long,
  val acksVerified: Long,
  val bytesOut: Long,
  val links: Long,
  val storeBundles: Long,
  val storeBytes: Long,
  val outstanding: Long
) {
  companion object {
    private const val FIELD_COUNT = 18

    fun decode(bytes: ByteArray): MeshStats? {
      if (bytes.size < FIELD_COUNT * 8) return null
      val r = WireReader(bytes)
      val v = LongArray(FIELD_COUNT) { r.u64() }
      return MeshStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17])
    }
  }
}

/** Link pacing presets, mirroring `LinkOptions::ble` / `LinkOptions::lora` in `transport/mod.rs`. */
data class MeshLinkOptions(val mtu: Int, val maxBytesPerSec: Int, val maxFramesPerSec: Int) {
  companion object {
    /** meshlink's smallest supported frame. */
    const val MIN_MTU = 64

    fun ble(mtu: Int): MeshLinkOptions = MeshLinkOptions(mtu = maxOf(mtu, MIN_MTU), maxBytesPerSec = 16 * 1024, maxFramesPerSec = 200)

    fun lora(mtu: Int = 200): MeshLinkOptions = MeshLinkOptions(mtu = maxOf(mtu, MIN_MTU), maxBytesPerSec = 200, maxFramesPerSec = 20)
  }
}
