/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.mesh.MeshStatus
import org.thoughtcrime.securesms.mesh.MeshTransport
import org.thoughtcrime.securesms.mesh.jni.MeshLinkOptions
import org.thoughtcrime.securesms.mesh.jni.MeshNode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Phone-to-phone Bluetooth LE link. Every device is both a GATT peripheral (advertising the
 * meshlink service, accepting frames written into [INBOUND_UUID] and pushing frames out as
 * notifications on [OUTBOUND_UUID]) and a GATT central (scanning for the service, writing frames
 * without response into the peer's inbound characteristic and subscribing to its outbound one).
 *
 * The UUIDs are shared by all three platforms and must not change:
 *  * service `6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e1`
 *  * inbound (write without response, frames written INTO the peripheral) `...c0e2`
 *  * outbound (notify, frames the peripheral sends out) `...c0e3`
 *
 * One meshlink frame per characteristic write or notification, no extra framing. The MTU is
 * negotiated at 512 and the meshlink link MTU is `mtu - 3` (ATT header), never below 64. On
 * subscription the link is attached with the `ble` preset (16 KiB/s, 200 frames/s); on
 * disconnect it is detached.
 */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context, private val node: MeshNode) {

  companion object {
    private val TAG = Log.tag(BleLink::class.java)

    val SERVICE_UUID: UUID = UUID.fromString("6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e1")
    val INBOUND_UUID: UUID = UUID.fromString("6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e2")
    val OUTBOUND_UUID: UUID = UUID.fromString("6b1a5e4e-2f1c-4d61-9c3b-a1d3a5f5c0e3")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val REQUESTED_MTU = 512
    private const val DEFAULT_MTU = 23
    private const val ATT_HEADER = 3
    private const val WRITE_GATE_TIMEOUT_MS = 2000L
  }

  private enum class Role { PERIPHERAL, CENTRAL }

  private inner class Peer(val device: BluetoothDevice, val role: Role) {
    @Volatile var mtu: Int = DEFAULT_MTU
    @Volatile var linkId: Long = -1
    @Volatile var gatt: BluetoothGatt? = null
    @Volatile var inboundChar: BluetoothGattCharacteristic? = null
    @Volatile var pump: LinkPump? = null
    val writeGate = Semaphore(1)
    val address: String get() = device.address
  }

  private val manager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val adapter get() = manager?.adapter

  private var gattServer: BluetoothGattServer? = null
  private var outboundChar: BluetoothGattCharacteristic? = null
  private var advertiser: BluetoothLeAdvertiser? = null
  private var scanner: BluetoothLeScanner? = null
  private val peers = ConcurrentHashMap<String, Peer>()
  private val notifyLock = Any()

  @Volatile
  private var started = false

  fun start() {
    if (started) return
    val adapter = adapter
    if (adapter == null || !adapter.isEnabled) {
      Log.w(TAG, "Bluetooth unavailable or off; BLE link not started")
      MeshStatus.onError("Bluetooth is off")
      return
    }
    if (!MeshTransport.hasBlePermissions(context)) {
      Log.w(TAG, "Missing Bluetooth permissions; BLE link not started")
      MeshStatus.onError("Bluetooth permission not granted")
      return
    }
    started = true
    startServer()
    startAdvertising()
    startScanning()
  }

  fun stop() {
    if (!started) return
    started = false
    runCatching { scanner?.stopScan(scanCallback) }
    runCatching { advertiser?.stopAdvertising(advertiseCallback) }
    for (peer in peers.values.toList()) {
      detach(peer)
      peer.gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
    }
    peers.clear()
    runCatching { gattServer?.close() }
    gattServer = null
  }

  // ---- peripheral role -----------------------------------------------------

  private fun startServer() {
    val manager = manager ?: return
    val server = manager.openGattServer(context, serverCallback)
    if (server == null) {
      Log.w(TAG, "openGattServer returned null")
      return
    }
    val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    val inbound = BluetoothGattCharacteristic(
      INBOUND_UUID,
      BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
      BluetoothGattCharacteristic.PERMISSION_WRITE
    )
    val outbound = BluetoothGattCharacteristic(
      OUTBOUND_UUID,
      BluetoothGattCharacteristic.PROPERTY_NOTIFY,
      BluetoothGattCharacteristic.PERMISSION_READ
    )
    outbound.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
    service.addCharacteristic(inbound)
    service.addCharacteristic(outbound)
    server.addService(service)
    outboundChar = outbound
    gattServer = server
  }

  private fun startAdvertising() {
    val advertiser = adapter?.bluetoothLeAdvertiser
    if (advertiser == null) {
      Log.w(TAG, "BLE advertising not supported on this device; peripheral role disabled")
      return
    }
    this.advertiser = advertiser
    val settings = AdvertiseSettings.Builder()
      .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
      .setConnectable(true)
      .setTimeout(0)
      .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
      .build()
    val data = AdvertiseData.Builder()
      .setIncludeDeviceName(false)
      .addServiceUuid(ParcelUuid(SERVICE_UUID))
      .build()
    advertiser.startAdvertising(settings, data, advertiseCallback)
  }

  private val advertiseCallback = object : AdvertiseCallback() {
    override fun onStartFailure(errorCode: Int) {
      Log.w(TAG, "Advertising failed: $errorCode")
    }
  }

  private val serverCallback = object : BluetoothGattServerCallback() {
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
      when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
          if (!peers.containsKey(device.address)) {
            peers[device.address] = Peer(device, Role.PERIPHERAL)
            Log.i(TAG, "Central ${device.address} connected")
          }
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          peers.remove(device.address)?.takeIf { it.role == Role.PERIPHERAL }?.let { detach(it) }
        }
      }
    }

    override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
      peers[device.address]?.mtu = mtu
    }

    override fun onCharacteristicWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      characteristic: BluetoothGattCharacteristic,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray
    ) {
      if (characteristic.uuid == INBOUND_UUID) {
        val peer = peers[device.address]
        if (peer != null && peer.linkId >= 0 && !preparedWrite) {
          node.linkWrite(peer.linkId, value)
        }
      }
      if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
      }
    }

    override fun onDescriptorWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      descriptor: BluetoothGattDescriptor,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray
    ) {
      if (descriptor.uuid == CCCD_UUID && descriptor.characteristic.uuid == OUTBOUND_UUID) {
        val peer = peers.getOrPut(device.address) { Peer(device, Role.PERIPHERAL) }
        if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
          attach(peer) { frame -> sendNotification(peer, frame) }
        } else {
          detach(peer)
        }
      }
      if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
      }
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
      peers[device.address]?.writeGate?.release()
    }
  }

  private fun sendNotification(peer: Peer, frame: ByteArray): Boolean {
    val server = gattServer ?: return false
    val characteristic = outboundChar ?: return false
    if (!peer.writeGate.tryAcquire(WRITE_GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      Log.w(TAG, "notify to ${peer.address} timed out waiting for the previous one")
      peer.writeGate.drainPermits()
      peer.writeGate.release()
      return false
    }
    val ok = synchronized(notifyLock) {
      if (Build.VERSION.SDK_INT >= 33) {
        server.notifyCharacteristicChanged(peer.device, characteristic, false, frame) == BluetoothGatt.GATT_SUCCESS
      } else {
        @Suppress("DEPRECATION")
        characteristic.value = frame
        @Suppress("DEPRECATION")
        server.notifyCharacteristicChanged(peer.device, characteristic, false)
      }
    }
    if (!ok) peer.writeGate.release()
    return ok
  }

  // ---- central role --------------------------------------------------------

  private fun startScanning() {
    val scanner = adapter?.bluetoothLeScanner
    if (scanner == null) {
      Log.w(TAG, "BLE scanner unavailable; central role disabled")
      return
    }
    this.scanner = scanner
    val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
    scanner.startScan(filters, settings, scanCallback)
  }

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      val device = result.device ?: return
      if (!started || peers.containsKey(device.address)) return
      val peer = Peer(device, Role.CENTRAL)
      if (peers.putIfAbsent(device.address, peer) != null) return
      Log.i(TAG, "Connecting to peripheral ${device.address}")
      peer.gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun onScanFailed(errorCode: Int) {
      Log.w(TAG, "Scan failed: $errorCode")
    }
  }

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      val peer = peers[gatt.device.address]
      when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
          if (!gatt.requestMtu(REQUESTED_MTU)) gatt.discoverServices()
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          peers.remove(gatt.device.address)
          peer?.let { detach(it) }
          gatt.close()
        }
      }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) peers[gatt.device.address]?.mtu = mtu
      gatt.discoverServices()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      val peer = peers[gatt.device.address] ?: return
      val service = gatt.getService(SERVICE_UUID)
      val inbound = service?.getCharacteristic(INBOUND_UUID)
      val outbound = service?.getCharacteristic(OUTBOUND_UUID)
      if (service == null || inbound == null || outbound == null) {
        Log.w(TAG, "Peripheral ${peer.address} lacks the meshlink service; disconnecting")
        gatt.disconnect()
        return
      }
      peer.inboundChar = inbound
      gatt.setCharacteristicNotification(outbound, true)
      val cccd = outbound.getDescriptor(CCCD_UUID)
      if (cccd == null) {
        Log.w(TAG, "Peripheral ${peer.address} has no CCCD on the outbound characteristic")
        gatt.disconnect()
        return
      }
      if (Build.VERSION.SDK_INT >= 33) {
        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
      } else {
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(cccd)
      }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
      val peer = peers[gatt.device.address] ?: return
      if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
        attach(peer) { frame -> writeFrame(peer, frame) }
      }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      peers[gatt.device.address]?.writeGate?.release()
    }

    // API 33+ signature.
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
      inbound(gatt, characteristic, value)
    }

    @Deprecated("Pre-33 signature")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      if (Build.VERSION.SDK_INT < 33) {
        @Suppress("DEPRECATION")
        val value = characteristic.value ?: return
        inbound(gatt, characteristic, value)
      }
    }

    private fun inbound(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
      if (characteristic.uuid != OUTBOUND_UUID) return
      val peer = peers[gatt.device.address] ?: return
      if (peer.linkId >= 0) node.linkWrite(peer.linkId, value)
    }
  }

  private fun writeFrame(peer: Peer, frame: ByteArray): Boolean {
    val gatt = peer.gatt ?: return false
    val characteristic = peer.inboundChar ?: return false
    if (!peer.writeGate.tryAcquire(WRITE_GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      Log.w(TAG, "write to ${peer.address} timed out waiting for the previous one")
      peer.writeGate.drainPermits()
      peer.writeGate.release()
      return false
    }
    val ok = if (Build.VERSION.SDK_INT >= 33) {
      gatt.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
    } else {
      characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      @Suppress("DEPRECATION")
      characteristic.value = frame
      @Suppress("DEPRECATION")
      gatt.writeCharacteristic(characteristic)
    }
    if (!ok) peer.writeGate.release()
    return ok
  }

  // ---- link lifecycle ------------------------------------------------------

  private fun attach(peer: Peer, sink: (ByteArray) -> Boolean) {
    synchronized(peer) {
      if (peer.linkId >= 0) return
      val mtu = maxOf(MeshLinkOptions.MIN_MTU, peer.mtu - ATT_HEADER)
      val linkId = node.attachLink(MeshLinkOptions.ble(mtu))
      peer.linkId = linkId
      peer.pump = LinkPump(node, linkId, "mesh-ble-${peer.address}", sink).start()
      MeshStatus.onLinkAttached(MeshStatus.LinkInfo(linkId, MeshStatus.LinkKind.BLE, "${peer.role.name.lowercase()} ${peer.address}", mtu))
      Log.i(TAG, "Attached BLE link $linkId to ${peer.address} as ${peer.role} (mtu $mtu)")
    }
  }

  private fun detach(peer: Peer) {
    synchronized(peer) {
      val linkId = peer.linkId
      if (linkId < 0) return
      peer.linkId = -1
      peer.pump?.stop()
      peer.pump = null
      runCatching { node.detachLink(linkId) }
      MeshStatus.onLinkDetached(linkId)
      Log.i(TAG, "Detached BLE link $linkId from ${peer.address}")
    }
  }
}
