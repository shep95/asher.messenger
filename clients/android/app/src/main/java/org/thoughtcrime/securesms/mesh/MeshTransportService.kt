/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import org.signal.core.util.SafeForegroundService
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.NotificationIds

/**
 * Foreground service (type `connectedDevice`) that keeps the mesh node, its radios and the event
 * loop alive while the user has the mesh switched on. The work itself lives in [MeshRuntime].
 */
class MeshTransportService : SafeForegroundService() {

  companion object {
    private val TAG = Log.tag(MeshTransportService::class.java)

    @JvmStatic
    fun start(context: Context) {
      if (!SafeForegroundService.start(context, MeshTransportService::class.java)) {
        Log.w(TAG, "Unable to start the mesh foreground service")
        MeshStatus.onError("Could not start the mesh service")
      }
    }

    @JvmStatic
    fun stop(context: Context) {
      SafeForegroundService.stop(context, MeshTransportService::class.java)
    }
  }

  override val tag: String
    get() = TAG

  override val notificationId: Int
    get() = NotificationIds.MESH_TRANSPORT

  override fun serviceType(intent: Intent): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

  override fun getForegroundNotification(intent: Intent): Notification {
    val open = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return NotificationCompat.Builder(this, NotificationChannels.getInstance().OTHER)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(R.string.MeshTransport__notification_title))
      .setContentText(getString(R.string.MeshTransport__notification_text))
      .setContentIntent(open)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .build()
  }

  override fun onServiceStartCommandReceived(intent: Intent) {
    SignalExecutors.BOUNDED.execute {
      try {
        MeshRuntime.start(applicationContext)
      } catch (e: Exception) {
        Log.w(TAG, "Mesh runtime failed to start", e)
        MeshStatus.onError(e.message ?: e.javaClass.simpleName)
        stop(applicationContext)
      }
    }
  }

  override fun onServiceStopCommandReceived(intent: Intent) {
    SignalExecutors.BOUNDED.execute { MeshRuntime.stop() }
  }

  override fun onDestroy() {
    SignalExecutors.BOUNDED.execute { MeshRuntime.stop() }
    super.onDestroy()
  }
}
