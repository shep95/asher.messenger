/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.link

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mesh.MeshStatus
import org.thoughtcrime.securesms.mesh.jni.MeshLinkOptions
import org.thoughtcrime.securesms.mesh.jni.MeshNode
import kotlin.concurrent.thread

/**
 * RNode-class LoRa boards over USB CDC-ACM (a board whose USB port is a native CDC device, e.g.
 * an ESP32-S3; FTDI/CP210x/CH34x bridges need a vendor driver that is not included). Talks raw
 * USB through `android.hardware.usb`: claims the CDC control and data interfaces, sets the line
 * coding to 115200 8N1 and DTR/RTS, then moves KISS frames over the bulk endpoints.
 *
 * Outbound meshlink frames are KISS-encoded as data frames; inbound bytes are KISS-decoded and
 * every data frame becomes a `MeshNode_LinkWrite`. The link is attached with the `lora` preset
 * (MTU 200, 200 B/s, 20 frames/s). With `SignalStore.mesh.configureRadio` on, the
 * `RadioConfig.EU_LONG_RANGE` command frames are sent first.
 */
class UsbSerialLink(private val context: Context, private val node: MeshNode) {

  companion object {
    private val TAG = Log.tag(UsbSerialLink::class.java)

    const val ACTION_USB_PERMISSION = "org.thoughtcrime.securesms.mesh.USB_PERMISSION"

    private const val BAUD_RATE = 115_200
    private const val USB_CLASS_COMM = 0x02
    private const val USB_CLASS_CDC_DATA = 0x0A
    private const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
    private const val SET_LINE_CODING = 0x20
    private const val SET_CONTROL_LINE_STATE = 0x22
    private const val CONTROL_DTR_RTS = 0x03
    private const val READ_TIMEOUT_MS = 200
    private const val WRITE_TIMEOUT_MS = 2000
    private const val READ_BUFFER = 4096

    /** True when the device looks like a CDC-ACM serial port. */
    fun isCdcAcm(device: UsbDevice): Boolean {
      var comm = false
      var data = false
      for (i in 0 until device.interfaceCount) {
        when (device.getInterface(i).interfaceClass) {
          USB_CLASS_COMM -> comm = true
          USB_CLASS_CDC_DATA -> data = true
        }
      }
      return data && (comm || device.deviceClass == USB_CLASS_COMM)
    }
  }

  private inner class Session(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val control: UsbInterface,
    val data: UsbInterface,
    val input: UsbEndpoint,
    val output: UsbEndpoint
  ) {
    @Volatile var running = true
    var linkId: Long = -1
    var pump: LinkPump? = null
    var reader: Thread? = null
  }

  private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

  @Volatile
  private var session: Session? = null

  @Volatile
  private var registered = false

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
      val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
      when (intent.action) {
        UsbManager.ACTION_USB_DEVICE_ATTACHED -> device?.let { onDeviceAttached(it) }
        UsbManager.ACTION_USB_DEVICE_DETACHED -> device?.let { onDeviceDetached(it) }
        ACTION_USB_PERMISSION -> {
          if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            open(device)
          } else {
            Log.w(TAG, "USB permission denied for ${device?.deviceName}")
          }
        }
      }
    }
  }

  fun start() {
    val manager = usbManager ?: run {
      Log.w(TAG, "No USB service; serial link disabled")
      return
    }
    if (!registered) {
      val filter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        addAction(ACTION_USB_PERMISSION)
      }
      ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
      registered = true
    }
    manager.deviceList.values.firstOrNull { isCdcAcm(it) }?.let { onDeviceAttached(it) }
  }

  fun stop() {
    if (registered) {
      runCatching { context.unregisterReceiver(receiver) }
      registered = false
    }
    close()
  }

  /** From the receiver or `MeshUsbAttachedActivity`. */
  fun onDeviceAttached(device: UsbDevice) {
    val manager = usbManager ?: return
    if (!isCdcAcm(device)) {
      Log.i(TAG, "Ignoring non-CDC USB device ${device.deviceName} (${device.vendorId}:${device.productId})")
      return
    }
    if (session != null) {
      Log.i(TAG, "A serial session is already open; ignoring ${device.deviceName}")
      return
    }
    if (manager.hasPermission(device)) {
      open(device)
    } else {
      val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
      val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
      manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }
  }

  private fun onDeviceDetached(device: UsbDevice) {
    if (session?.device?.deviceId == device.deviceId) close()
  }

  @Synchronized
  private fun open(device: UsbDevice) {
    if (session != null) return
    val manager = usbManager ?: return

    var control: UsbInterface? = null
    var data: UsbInterface? = null
    for (i in 0 until device.interfaceCount) {
      val iface = device.getInterface(i)
      when (iface.interfaceClass) {
        USB_CLASS_COMM -> if (control == null) control = iface
        USB_CLASS_CDC_DATA -> if (data == null) data = iface
      }
    }
    if (data == null) {
      Log.w(TAG, "${device.deviceName} has no CDC data interface")
      return
    }
    var input: UsbEndpoint? = null
    var output: UsbEndpoint? = null
    for (i in 0 until data.endpointCount) {
      val ep = data.getEndpoint(i)
      if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
      if (ep.direction == UsbConstants.USB_DIR_IN) input = input ?: ep else output = output ?: ep
    }
    if (input == null || output == null) {
      Log.w(TAG, "${device.deviceName} lacks bulk in/out endpoints")
      return
    }

    val connection = manager.openDevice(device)
    if (connection == null) {
      Log.w(TAG, "openDevice failed for ${device.deviceName}")
      return
    }
    val controlIface = control ?: data
    if (!connection.claimInterface(controlIface, true) || (control != null && !connection.claimInterface(data, true))) {
      Log.w(TAG, "Could not claim interfaces on ${device.deviceName}")
      connection.close()
      return
    }

    // CDC line coding: dwDTERate (LE), bCharFormat (0 = 1 stop bit), bParityType (0 = none), bDataBits.
    val lineCoding = byteArrayOf(
      (BAUD_RATE and 0xFF).toByte(),
      ((BAUD_RATE shr 8) and 0xFF).toByte(),
      ((BAUD_RATE shr 16) and 0xFF).toByte(),
      ((BAUD_RATE shr 24) and 0xFF).toByte(),
      0,
      0,
      8
    )
    connection.controlTransfer(REQUEST_TYPE_CLASS_INTERFACE_OUT, SET_LINE_CODING, 0, controlIface.id, lineCoding, lineCoding.size, WRITE_TIMEOUT_MS)
    connection.controlTransfer(REQUEST_TYPE_CLASS_INTERFACE_OUT, SET_CONTROL_LINE_STATE, CONTROL_DTR_RTS, controlIface.id, null, 0, WRITE_TIMEOUT_MS)

    val s = Session(device, connection, controlIface, data, input, output)
    session = s

    val radio = Kiss.RadioConfig.EU_LONG_RANGE
    if (SignalStore.mesh.configureRadio) {
      for (frame in radio.toFrames()) {
        writeRaw(s, frame)
      }
    }

    val options = MeshLinkOptions.lora(radio.frameMtu())
    s.linkId = node.attachLink(options)
    MeshStatus.onLinkAttached(MeshStatus.LinkInfo(s.linkId, MeshStatus.LinkKind.USB_SERIAL, device.productName ?: device.deviceName, options.mtu))
    s.pump = LinkPump(node, s.linkId, "mesh-usb-out") { frame -> writeRaw(s, Kiss.encode(Kiss.CMD_DATA, frame)) }.start()
    s.reader = thread(name = "mesh-usb-in", isDaemon = true) { readLoop(s) }
    Log.i(TAG, "Opened serial link ${s.linkId} on ${device.deviceName} (mtu ${options.mtu})")
  }

  private fun readLoop(s: Session) {
    val decoder = Kiss.Decoder()
    val buffer = ByteArray(READ_BUFFER)
    var errors = 0
    while (s.running) {
      val n = s.connection.bulkTransfer(s.input, buffer, buffer.size, READ_TIMEOUT_MS)
      if (n < 0) {
        // Timeout and I/O errors both come back negative; a detach shows up as a burst of them.
        if (++errors > 50 && s.running && usbManager?.deviceList?.values?.none { it.deviceId == s.device.deviceId } == true) {
          Log.w(TAG, "Serial device went away")
          close()
          return
        }
        continue
      }
      errors = 0
      for ((command, payload) in decoder.feed(buffer, n)) {
        when (command) {
          Kiss.CMD_DATA -> if (s.linkId >= 0) node.linkWrite(s.linkId, payload)
          Kiss.CMD_READY, Kiss.CMD_DETECT -> Log.d(TAG, "RNode command 0x${Integer.toHexString(command)}")
          else -> Unit
        }
      }
    }
  }

  private fun writeRaw(s: Session, bytes: ByteArray): Boolean {
    var offset = 0
    while (offset < bytes.size) {
      val chunk = minOf(bytes.size - offset, s.output.maxPacketSize.coerceAtLeast(64))
      val written = s.connection.bulkTransfer(s.output, bytes, offset, chunk, WRITE_TIMEOUT_MS)
      if (written < 0) {
        Log.w(TAG, "Serial write failed at offset $offset")
        return false
      }
      offset += written
    }
    return true
  }

  @Synchronized
  private fun close() {
    val s = session ?: return
    session = null
    s.running = false
    s.pump?.stop()
    if (s.linkId >= 0) {
      runCatching { node.detachLink(s.linkId) }
      MeshStatus.onLinkDetached(s.linkId)
    }
    if (Thread.currentThread() !== s.reader) {
      runCatching { s.reader?.join(READ_TIMEOUT_MS * 2L) }
    }
    runCatching { s.connection.releaseInterface(s.data) }
    if (s.control !== s.data) runCatching { s.connection.releaseInterface(s.control) }
    runCatching { s.connection.close() }
    Log.i(TAG, "Closed serial link on ${s.device.deviceName}")
  }
}
