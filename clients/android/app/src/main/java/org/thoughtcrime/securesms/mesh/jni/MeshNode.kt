/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.jni

import org.signal.libsignal.internal.Native
import org.signal.libsignal.internal.NativeHandleGuard

/**
 * A running meshlink node (its own small tokio runtime inside libsignal), driven by blocking poll
 * calls from app threads. Wraps the Rust `MeshNode` handle; [close] destroys it.
 *
 * In external-crypto mode (the only mode the app uses) the node never sees a Signal session:
 *  * send: [prepareText] -> app encrypts `plaintext` for `to` -> [sendCiphertext] with the same `commit`;
 *  * receive: [MeshEvent.Ciphertext] -> app decrypts -> [deliverPlaintext] (or [defer] when there is no session yet).
 */
class MeshNode private constructor(handle: Long) : NativeHandleGuard.CloseableOwner(handle) {

  override fun release(nativeHandle: Long) {
    Native.MeshNode_Destroy(nativeHandle)
  }

  val fingerprint: ByteArray
    get() = guardedMap { Native.MeshNode_Fingerprint(it) }

  val cardBytes: ByteArray
    get() = guardedMap { Native.MeshNode_Card(it) }

  // ---- links --------------------------------------------------------------

  /** Registers a byte pipe; returns the link id used by [linkRead] / [linkWrite] and in [MeshEvent.Neighbour]. */
  fun attachLink(options: MeshLinkOptions): Long {
    return guardedMap { Native.MeshNode_AttachLink(it, options.mtu, options.maxBytesPerSec, options.maxFramesPerSec) }
  }

  fun detachLink(link: Long) {
    guardedRun { Native.MeshNode_DetachLink(it, link) }
  }

  /** A frame received from the wire. False if the link is gone or the node applied back-pressure for a second. */
  fun linkWrite(link: Long, frame: ByteArray): Boolean = guardedMap { Native.MeshNode_LinkWrite(it, link, frame) }

  /** The next frame to transmit on [link]; empty after [timeoutMs] with nothing to send, or if the link is gone. */
  fun linkRead(link: Long, timeoutMs: Int): ByteArray = guardedMap { Native.MeshNode_LinkRead(it, link, timeoutMs) }

  // ---- events -------------------------------------------------------------

  /** The next event, or null after [timeoutMs]. */
  fun nextEvent(timeoutMs: Int): MeshEvent? = MeshEvent.decode(guardedMap { Native.MeshNode_NextEvent(it, timeoutMs) })

  // ---- contacts -----------------------------------------------------------

  /** Verifies and stores an encoded card; returns its fingerprint. */
  fun addContact(card: ByteArray): ByteArray = guardedMap { Native.MeshNode_AddContact(it, card) }

  fun removeContact(fingerprint: ByteArray): Boolean = guardedMap { Native.MeshNode_RemoveContact(it, fingerprint) }

  /** The contact's encoded card, or null if unknown. */
  fun contact(fingerprint: ByteArray): ByteArray? = guardedMap { Native.MeshNode_Contact(it, fingerprint) }.takeIf { it.isNotEmpty() }

  fun contacts(): List<ByteArray> = guardedMap { Native.MeshNode_Contacts(it) }.toList()

  fun safetyNumber(fingerprint: ByteArray): String = guardedMap { Native.MeshNode_SafetyNumber(it, fingerprint) }

  fun rename(name: String) {
    guardedRun { Native.MeshNode_Rename(it, name) }
  }

  /** Broadcasts our card as a beacon; returns the bundle id. */
  fun broadcastCard(): ByteArray = guardedMap { Native.MeshNode_BroadcastCard(it) }

  // ---- messages (external crypto) ------------------------------------------

  /** The padded plaintext to encrypt for [to]; exactly one item for a text. */
  fun prepareText(to: ByteArray, plaintext: ByteArray): MeshPrepared {
    return MeshPrepared.decodeList(guardedMap { Native.MeshNode_PrepareText(it, to, plaintext) }).first()
  }

  /** Hands the app-encrypted message to the mesh; [messageType] is the libsignal ciphertext type. Returns the bundle id. */
  fun sendCiphertext(to: ByteArray, commit: ByteArray, messageType: Int, ciphertext: ByteArray): ByteArray {
    return guardedMap { Native.MeshNode_SendCiphertext(it, to, commit, messageType, ciphertext) }
  }

  fun deliverPlaintext(bundleId: ByteArray, plaintext: ByteArray) {
    guardedRun { Native.MeshNode_DeliverPlaintext(it, bundleId, plaintext) }
  }

  fun defer(bundleId: ByteArray) {
    guardedRun { Native.MeshNode_Defer(it, bundleId) }
  }

  // ---- attachments and call signalling (v3, external crypto) ---------------

  /**
   * The manifest plaintext followed by the chunk plaintexts for one attachment; the app encrypts
   * and sends every entry in order. [kind]: 1 file, 2 image, 3 voice note (`MeshEvent.ATTACHMENT_KIND_*`).
   * Fails with `TooLarge` beyond 4 MiB of [data]. The first entry is the manifest; when its bundle
   * is delivered the attachment counts as delivered.
   */
  fun prepareAttachment(to: ByteArray, kind: Int, name: String, mime: String, data: ByteArray): List<MeshPrepared> {
    return MeshPrepared.decodeList(guardedMap { Native.MeshNode_PrepareAttachment(it, to, kind, name, mime, data) })
  }

  /** One prepared entry carrying opaque call signalling [data] (TTL 90 s, high priority). */
  fun prepareCallSignal(to: ByteArray, data: ByteArray): MeshPrepared {
    return MeshPrepared.decodeList(guardedMap { Native.MeshNode_PrepareCallSignal(it, to, data) }).first()
  }

  // ---- discovery, backup, self-test (v3) -----------------------------------

  /** Cards seen on the mesh in the last 24 h, most recent first. */
  fun nearby(): List<MeshNearbyEntry> = MeshNearbyEntry.decodeList(guardedMap { Native.MeshNode_Nearby(it) })

  /** "ASHB" v1 blob: identity export + full snapshot, encrypted under [passphrase]. */
  fun exportBackup(passphrase: String): ByteArray = guardedMap { Native.MeshNode_ExportBackup(it, passphrase) }

  /** Merges contacts, groups and carried bundles from a backup made by this same identity (`IdentityMismatch` otherwise). */
  fun importBackup(passphrase: String, blob: ByteArray) {
    guardedRun { Native.MeshNode_ImportBackup(it, passphrase, blob) }
  }

  /** Loopback end-to-end test with in-process nodes; returns a multi-line "PASS/FAIL" report and never throws for a test failure. */
  fun selfTest(timeoutMs: Int): String = guardedMap { Native.MeshNode_SelfTest(it, timeoutMs) }

  // ---- groups (not wired to UI yet; see MeshRuntime) -----------------------

  /** `[group id 16][prepared list]`. */
  fun prepareGroupCreate(name: String, members: List<ByteArray>): Pair<ByteArray, List<MeshPrepared>> {
    val out = guardedMap { Native.MeshNode_PrepareGroupCreate(it, name, concat(members)) }
    return out.copyOfRange(0, 16) to MeshPrepared.decodeList(out.copyOfRange(16, out.size))
  }

  fun prepareGroupText(group: ByteArray, plaintext: ByteArray): List<MeshPrepared> {
    return MeshPrepared.decodeList(guardedMap { Native.MeshNode_PrepareGroupText(it, group, plaintext) })
  }

  fun groups(): List<ByteArray> = guardedMap { Native.MeshNode_Groups(it) }.toList()

  fun group(id: ByteArray): ByteArray? = guardedMap { Native.MeshNode_Group(it, id) }.takeIf { it.isNotEmpty() }

  // ---- diagnostics --------------------------------------------------------

  fun stats(): MeshStats? = MeshStats.decode(guardedMap { Native.MeshNode_Stats(it) })

  /** Writes the carry store and contacts through the persistence layer now. */
  fun flush() {
    guardedRun { Native.MeshNode_Flush(it) }
  }

  companion object {
    /**
     * @param statePath file the node persists its store and contacts to; null for nothing persisted.
     * @param antiEntropySecs summary exchange period; 0 keeps the Rust default (15 s).
     */
    fun create(identity: MeshIdentity, statePath: String?, externalCrypto: Boolean, antiEntropySecs: Int): MeshNode {
      return MeshNode(identity.guardedMap { Native.MeshNode_New(it, statePath, externalCrypto, antiEntropySecs) })
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
      val out = ByteArray(parts.sumOf { it.size })
      var pos = 0
      for (p in parts) {
        System.arraycopy(p, 0, out, pos, p.size)
        pos += p.size
      }
      return out
    }
  }
}
