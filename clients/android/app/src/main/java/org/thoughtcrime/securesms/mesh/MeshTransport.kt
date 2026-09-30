/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mesh.jni.MeshNode
import org.thoughtcrime.securesms.util.RemoteConfig

/**
 * Entry point for the offline mesh transport. Everything is behind the `mesh.transport` flag
 * ([RemoteConfig.meshTransport]) and the user's switch ([SignalStore.mesh]); with either off,
 * nothing here runs and the app behaves exactly as before.
 */
object MeshTransport {

  private val TAG = Log.tag(MeshTransport::class.java)

  /** The running node, owned by [MeshRuntime]; null while the transport is stopped. */
  @Volatile
  @JvmStatic
  var node: MeshNode? = null
    internal set

  /** The feature flag. */
  @JvmStatic
  fun isAvailable(): Boolean = RemoteConfig.meshTransport

  /** Flag on and user switch on. */
  @JvmStatic
  fun isEnabled(): Boolean = isAvailable() && SignalStore.mesh.enabled

  /** Called at app start; starts the foreground service if the user left the mesh on. */
  @JvmStatic
  fun startIfEnabled(context: Context) {
    if (!isEnabled()) return
    if (!SignalStore.account.isRegistered) {
      Log.i(TAG, "Not registered; mesh stays off.")
      return
    }
    MeshTransportService.start(context)
  }

  @JvmStatic
  fun setEnabled(context: Context, enabled: Boolean) {
    SignalStore.mesh.enabled = enabled
    if (enabled && isAvailable()) {
      MeshTransportService.start(context)
    } else {
      MeshTransportService.stop(context)
    }
  }

  /** Runtime permissions the Bluetooth link needs on this Android version. */
  @JvmStatic
  fun blePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 31) {
      arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }

  @JvmStatic
  fun hasBlePermissions(context: Context): Boolean {
    return blePermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
  }
}
