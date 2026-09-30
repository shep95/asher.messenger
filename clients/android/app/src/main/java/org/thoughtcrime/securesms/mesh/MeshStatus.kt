/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.thoughtcrime.securesms.components.SceneIndicatorView
import org.thoughtcrime.securesms.mesh.jni.MeshStats
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * What the mesh looks like right now, for the scene indicator and the settings screen. Updated
 * by the node's event loop and the link transports; read from the UI as a [StateFlow].
 */
object MeshStatus {

  enum class LinkKind { BLE, USB_SERIAL }

  data class LinkInfo(val id: Long, val kind: LinkKind, val label: String, val mtu: Int, val since: Long = System.currentTimeMillis())

  data class Snapshot(
    val running: Boolean = false,
    val links: Map<Long, LinkInfo> = emptyMap(),
    /** link id -> neighbour fingerprint (from `Event::Neighbour`, unauthenticated). */
    val neighbours: Map<Long, ByteArray> = emptyMap(),
    /** Recipients with bundles sent and not yet acknowledged. */
    val carrying: Map<RecipientId, Int> = emptyMap(),
    val stats: MeshStats? = null,
    val lastError: String? = null
  ) {
    val neighbourCount: Int get() = neighbours.size
    fun isNeighbour(fingerprint: ByteArray): Boolean = neighbours.values.any { it.contentEquals(fingerprint) }
  }

  /** The indicator state for one conversation. */
  data class Scene(val state: SceneIndicatorView.State, val detail: String?)

  private val _state = MutableStateFlow(Snapshot())
  val state: StateFlow<Snapshot> = _state

  val snapshot: Snapshot get() = _state.value

  fun setRunning(running: Boolean) {
    _state.update { if (running) it.copy(running = true, lastError = null) else Snapshot() }
  }

  fun onLinkAttached(info: LinkInfo) {
    _state.update { it.copy(links = it.links + (info.id to info)) }
  }

  fun onLinkDetached(id: Long) {
    _state.update { it.copy(links = it.links - id, neighbours = it.neighbours - id) }
  }

  fun onNeighbour(link: Long, fingerprint: ByteArray) {
    _state.update { it.copy(neighbours = it.neighbours + (link to fingerprint)) }
  }

  fun onCarryingChanged(recipientId: RecipientId, count: Int) {
    _state.update { current ->
      val next = if (count > 0) current.carrying + (recipientId to count) else current.carrying - recipientId
      current.copy(carrying = next)
    }
  }

  fun onStats(stats: MeshStats?) {
    _state.update { it.copy(stats = stats) }
  }

  fun onError(message: String) {
    _state.update { it.copy(lastError = message) }
  }

  /**
   * The scene for [recipient], or null when it is not a mesh contact (the caller then keeps the
   * connectivity-driven Orbit / Out of range state).
   *
   * Mesh · 1 hop when the contact is itself a neighbour; Mesh · N nearby when other neighbours
   * can carry for us (meshlink has no per-destination hop count, so the neighbour count is what
   * we know); Carrying while bundles for the contact are unacknowledged and nobody is in range;
   * Out of range otherwise.
   */
  fun sceneFor(recipient: Recipient?, snapshot: Snapshot = _state.value): Scene? {
    if (recipient == null || !MeshTransport.isEnabled()) return null
    val fingerprint = MeshContacts.fingerprintFor(recipient) ?: return null
    val carrying = snapshot.carrying[recipient.id] ?: 0
    return when {
      !snapshot.running -> Scene(SceneIndicatorView.State.OUT_OF_RANGE, null)
      snapshot.isNeighbour(fingerprint) -> Scene(SceneIndicatorView.State.MESH, "1 hop")
      snapshot.neighbourCount > 0 -> Scene(SceneIndicatorView.State.MESH, "${snapshot.neighbourCount} nearby")
      carrying > 0 -> Scene(SceneIndicatorView.State.CARRYING, if (carrying == 1) null else "$carrying")
      else -> Scene(SceneIndicatorView.State.OUT_OF_RANGE, null)
    }
  }
}
