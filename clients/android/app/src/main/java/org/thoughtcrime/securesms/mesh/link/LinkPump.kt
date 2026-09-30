/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.link

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.mesh.jni.MeshNode

/**
 * The outbound half of a link: a thread that loops `MeshNode_LinkRead(link, 500)` and hands each
 * frame to the transport's [sink] (a BLE notification, a characteristic write, a KISS frame on a
 * serial port). The inbound half is the transport calling `MeshNode_LinkWrite` directly.
 */
class LinkPump(
  private val node: MeshNode,
  val linkId: Long,
  name: String,
  private val sink: (ByteArray) -> Boolean
) {
  companion object {
    private val TAG = Log.tag(LinkPump::class.java)
    private const val READ_TIMEOUT_MS = 500
  }

  @Volatile
  private var running = true

  private val thread = Thread({
    var failures = 0
    while (running && !Thread.currentThread().isInterrupted) {
      val frame = try {
        node.linkRead(linkId, READ_TIMEOUT_MS)
      } catch (e: Exception) {
        Log.w(TAG, "link $linkId read failed", e)
        break
      }
      if (frame.isEmpty()) continue
      if (sink(frame)) {
        failures = 0
      } else if (++failures >= 8) {
        Log.w(TAG, "link $linkId: sink failed $failures times in a row; giving up")
        break
      }
    }
  }, name).apply { isDaemon = true }

  fun start(): LinkPump {
    thread.start()
    return this
  }

  /** Stops the loop and waits briefly so no read is in flight when the node is destroyed. */
  fun stop() {
    running = false
    thread.interrupt()
    if (Thread.currentThread() !== thread) {
      runCatching { thread.join(READ_TIMEOUT_MS * 2L) }
    }
  }
}
