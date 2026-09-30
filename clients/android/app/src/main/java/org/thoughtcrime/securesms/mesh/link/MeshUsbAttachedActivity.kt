/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.link

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.mesh.MeshTransport
import org.thoughtcrime.securesms.mesh.MeshTransportService

/**
 * Invisible activity the system launches when a matching USB serial device is plugged in
 * (`ACTION_USB_DEVICE_ATTACHED` with `res/xml/mesh_usb_device_filter.xml`). It only makes sure
 * the mesh service is running; [UsbSerialLink] picks the device up through its own receiver and
 * the device list.
 */
class MeshUsbAttachedActivity : Activity() {

  companion object {
    private val TAG = Log.tag(MeshUsbAttachedActivity::class.java)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val device: UsbDevice? = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    if (MeshTransport.isEnabled()) {
      Log.i(TAG, "USB device attached (${device?.deviceName}); ensuring the mesh service runs")
      MeshTransportService.start(this)
    } else {
      Log.i(TAG, "USB device attached but the mesh is off; ignoring")
    }
    finish()
  }
}
