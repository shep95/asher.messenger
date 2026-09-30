/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.mesh.MeshContacts
import org.thoughtcrime.securesms.mesh.MeshStatus
import org.thoughtcrime.securesms.mesh.jni.MeshLinkOptions
import org.thoughtcrime.securesms.mesh.jni.MeshNode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Phone-to-phone (or phone-to-laptop) link over the local network with no radios: a TCP server on
 * [DEFAULT_PORT] announced through DNS-SD as `_asher-mesh._tcp` with a `fp=<fingerprint hex>` TXT
 * record, NSD discovery of other nodes, and one TCP link per discovered peer. It is the software
 * end-to-end test path: two phones on one Wi-Fi (or a phone hotspot) mesh with zero hardware.
 *
 * Framing is the meshlinkd / Desktop TCP framing: `u16 big-endian length` then the frame, length
 * 0 rejected, at most [MAX_FRAME] bytes (`libs/libsignal/rust/meshlink/src/transport/tcp.rs`).
 * Links are attached with [MeshLinkOptions.lan] (`AttachLink(1500, 262144, 500)`).
 *
 * To avoid two links between the same pair, only the node with the lexicographically smaller
 * fingerprint hex dials; the other only accepts. A peer is dialled again every [REDIAL_MS] while
 * its service is still advertised and no link to it is up.
 */
class LanLink(private val context: Context, private val node: MeshNode) {

  companion object {
    private val TAG = Log.tag(LanLink::class.java)

    /** Shared by all platforms; must not change. */
    const val SERVICE_TYPE = "_asher-mesh._tcp"
    const val DEFAULT_PORT = 7788
    const val TXT_FINGERPRINT = "fp"

    /** `node::MAX_FRAME_LEN`. */
    const val MAX_FRAME = 8192

    private const val MAX_PEERS = 16
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val REDIAL_MS = 20_000L
  }

  private inner class Peer(val socket: Socket, val label: String, val fingerprintHex: String?) {
    @Volatile var linkId: Long = -1
    @Volatile var pump: LinkPump? = null
    val output = DataOutputStream(socket.getOutputStream().buffered())
    val writeLock = Any()
    val closed = AtomicBoolean(false)
  }

  private val nsd: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
  private val myHex: String = MeshContacts.hex(node.fingerprint)

  private var server: ServerSocket? = null
  private var acceptThread: Thread? = null
  private var redialThread: Thread? = null
  private var multicastLock: WifiManager.MulticastLock? = null

  /** Peers with a live socket, keyed by "host:port" for accepted ones and by fingerprint hex for dialled ones. */
  private val peers = ConcurrentHashMap<String, Peer>()

  /** Peers NSD currently advertises that we should dial (smaller fingerprint dials): hex -> address. */
  private val discovered = ConcurrentHashMap<String, InetSocketAddress>()

  /** Peers with a dial in flight, so the redial loop and the resolver do not race. */
  private val dialing = ConcurrentHashMap.newKeySet<String>()

  /** DNS-SD service name -> fingerprint hex, so `onServiceLost` (which carries no TXT record) can forget the peer. */
  private val namesToHex = ConcurrentHashMap<String, String>()

  @Volatile private var registeredName: String? = null
  @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
  @Volatile private var discoveryListener: NsdManager.DiscoveryListener? = null

  @Volatile
  private var started = false

  fun start() {
    if (started) return
    val nsd = nsd
    if (nsd == null) {
      Log.w(TAG, "NsdManager unavailable; LAN link not started")
      MeshStatus.onError("Network service discovery unavailable")
      return
    }
    started = true

    acquireMulticastLock()

    val server = try {
      openServer()
    } catch (e: IOException) {
      Log.w(TAG, "Could not open the LAN listener", e)
      MeshStatus.onError("Could not listen on the LAN: ${e.message}")
      started = false
      releaseMulticastLock()
      return
    }
    this.server = server
    Log.i(TAG, "LAN listener on port ${server.localPort} as $myHex")

    acceptThread = thread(name = "mesh-lan-accept", isDaemon = true) { acceptLoop(server) }
    register(nsd, server.localPort)
    discover(nsd)
    redialThread = thread(name = "mesh-lan-redial", isDaemon = true) { redialLoop() }
  }

  fun stop() {
    if (!started) return
    started = false

    nsd?.let { manager ->
      discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
      registrationListener?.let { runCatching { manager.unregisterService(it) } }
    }
    discoveryListener = null
    registrationListener = null

    runCatching { server?.close() }
    server = null
    redialThread?.interrupt()
    redialThread = null

    for (peer in peers.values.toList()) close(peer, "stopping")
    peers.clear()
    discovered.clear()
    dialing.clear()
    namesToHex.clear()

    acceptThread?.let { runCatching { it.join(1000) } }
    acceptThread = null
    releaseMulticastLock()
  }

  // ---- listener ------------------------------------------------------------

  private fun openServer(): ServerSocket {
    return try {
      ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(DEFAULT_PORT))
      }
    } catch (e: IOException) {
      Log.w(TAG, "Port $DEFAULT_PORT busy (${e.message}); using an ephemeral port")
      ServerSocket(0)
    }
  }

  private fun acceptLoop(server: ServerSocket) {
    while (started && !server.isClosed) {
      val socket = try {
        server.accept()
      } catch (e: IOException) {
        if (started) Log.w(TAG, "accept failed", e)
        return
      }
      if (peers.size >= MAX_PEERS) {
        Log.w(TAG, "Too many LAN peers; refusing ${socket.remoteSocketAddress}")
        runCatching { socket.close() }
        continue
      }
      val label = "${socket.inetAddress.hostAddress}:${socket.port}"
      attach(socket, key = label, label = "in $label", fingerprintHex = null)
    }
  }

  // ---- DNS-SD --------------------------------------------------------------

  private fun register(nsd: NsdManager, port: Int) {
    val info = NsdServiceInfo().apply {
      serviceName = "asher-mesh-${myHex.take(8)}"
      serviceType = SERVICE_TYPE
      setPort(port)
      setAttribute(TXT_FINGERPRINT, myHex)
    }
    val listener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
        registeredName = serviceInfo.serviceName
        Log.i(TAG, "Registered $SERVICE_TYPE as ${serviceInfo.serviceName}")
      }

      override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "NSD registration failed: $errorCode")
        MeshStatus.onError("LAN announcement failed ($errorCode)")
      }

      override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

      override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "NSD unregistration failed: $errorCode")
      }
    }
    registrationListener = listener
    try {
      nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    } catch (e: Exception) {
      Log.w(TAG, "registerService threw", e)
      registrationListener = null
    }
  }

  private fun discover(nsd: NsdManager) {
    val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType: String) {
        Log.i(TAG, "Discovering $serviceType")
      }

      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        Log.w(TAG, "NSD discovery failed to start: $errorCode")
        MeshStatus.onError("LAN discovery failed ($errorCode)")
      }

      override fun onDiscoveryStopped(serviceType: String) = Unit

      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
        Log.w(TAG, "NSD discovery failed to stop: $errorCode")
      }

      override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        if (!started) return
        if (serviceInfo.serviceName == registeredName) return
        if (!serviceInfo.serviceType.trimEnd('.').endsWith(SERVICE_TYPE)) return
        resolve(nsd, serviceInfo)
      }

      override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        namesToHex.remove(serviceInfo.serviceName)?.let { discovered.remove(it) }
        Log.i(TAG, "Service ${serviceInfo.serviceName} lost")
      }
    }
    discoveryListener = listener
    try {
      nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    } catch (e: Exception) {
      Log.w(TAG, "discoverServices threw", e)
      discoveryListener = null
    }
  }

  /**
   * `resolveService` is deprecated from API 34 in favour of `registerServiceInfoCallback`, but it
   * still works on every release and needs no executor plumbing. One listener per call: the
   * framework rejects a reused one.
   */
  @Suppress("DEPRECATION")
  private fun resolve(nsd: NsdManager, found: NsdServiceInfo) {
    val listener = object : NsdManager.ResolveListener {
      override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "Could not resolve ${serviceInfo.serviceName}: $errorCode")
      }

      override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
        onResolved(serviceInfo)
      }
    }
    try {
      nsd.resolveService(found, listener)
    } catch (e: Exception) {
      Log.w(TAG, "resolveService threw for ${found.serviceName}", e)
    }
  }

  @Suppress("DEPRECATION")
  private fun onResolved(info: NsdServiceInfo) {
    val theirHex = info.attributes[TXT_FINGERPRINT]?.let { String(it, Charsets.UTF_8).lowercase() }
    if (theirHex == null || theirHex.length != MeshContacts.FINGERPRINT_LENGTH * 2) {
      Log.w(TAG, "Service ${info.serviceName} has no usable fp TXT record; ignoring")
      return
    }
    if (theirHex == myHex) return
    namesToHex[info.serviceName] = theirHex
    val host: InetAddress = info.host ?: run {
      Log.w(TAG, "Service ${info.serviceName} resolved without a host")
      return
    }
    val port = info.port
    if (port <= 0) return

    if (myHex >= theirHex) {
      // They dial us. Nothing to do but note the neighbour for the log.
      Log.i(TAG, "Peer $theirHex at ${host.hostAddress}:$port will dial us")
      return
    }
    discovered[theirHex] = InetSocketAddress(host, port)
    dial(theirHex)
  }

  // ---- dialling --------------------------------------------------------------

  private fun dial(theirHex: String) {
    if (!started || peers.containsKey(theirHex) || !dialing.add(theirHex)) return
    val address = discovered[theirHex] ?: run { dialing.remove(theirHex); return }
    thread(name = "mesh-lan-dial-${theirHex.take(8)}", isDaemon = true) {
      try {
        if (peers.size >= MAX_PEERS) return@thread
        val socket = Socket()
        socket.connect(address, CONNECT_TIMEOUT_MS)
        val label = "${address.address.hostAddress}:${address.port}"
        attach(socket, key = theirHex, label = "out $label", fingerprintHex = theirHex)
      } catch (e: IOException) {
        Log.w(TAG, "Dial to $theirHex at $address failed: ${e.message}")
      } finally {
        dialing.remove(theirHex)
      }
    }
  }

  private fun redialLoop() {
    while (started && !Thread.currentThread().isInterrupted) {
      try {
        Thread.sleep(REDIAL_MS)
      } catch (e: InterruptedException) {
        return
      }
      if (!started) return
      for (theirHex in discovered.keys.toList()) {
        if (!peers.containsKey(theirHex)) dial(theirHex)
      }
    }
  }

  // ---- link lifecycle --------------------------------------------------------

  private fun attach(socket: Socket, key: String, label: String, fingerprintHex: String?) {
    val peer = try {
      socket.tcpNoDelay = true
      socket.keepAlive = true
      Peer(socket, label, fingerprintHex)
    } catch (e: IOException) {
      Log.w(TAG, "Could not set up socket for $label", e)
      runCatching { socket.close() }
      return
    }
    if (peers.putIfAbsent(key, peer) != null) {
      Log.i(TAG, "Already linked with $key; dropping the duplicate socket")
      runCatching { socket.close() }
      return
    }

    val options = MeshLinkOptions.lan()
    val linkId = node.attachLink(options)
    peer.linkId = linkId
    peer.pump = LinkPump(node, linkId, "mesh-lan-out-$label") { frame -> write(peer, frame) }.start()
    MeshStatus.onLinkAttached(MeshStatus.LinkInfo(linkId, MeshStatus.LinkKind.LAN, label, options.mtu))
    Log.i(TAG, "Attached LAN link $linkId to $label")

    thread(name = "mesh-lan-in-$label", isDaemon = true) { readLoop(peer, key) }
  }

  private fun readLoop(peer: Peer, key: String) {
    val input = DataInputStream(peer.socket.getInputStream().buffered())
    val header = ByteArray(2)
    try {
      while (started && !peer.closed.get()) {
        input.readFully(header)
        val n = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        if (n == 0 || n > MAX_FRAME) {
          Log.w(TAG, "LAN peer ${peer.label} sent a frame length of $n; closing")
          break
        }
        val frame = ByteArray(n)
        input.readFully(frame)
        if (peer.linkId >= 0) node.linkWrite(peer.linkId, frame)
      }
    } catch (e: EOFException) {
      Log.i(TAG, "LAN peer ${peer.label} closed the connection")
    } catch (e: SocketException) {
      if (!peer.closed.get()) Log.i(TAG, "LAN peer ${peer.label}: ${e.message}")
    } catch (e: IOException) {
      Log.w(TAG, "LAN peer ${peer.label} read failed", e)
    } finally {
      peers.remove(key, peer)
      close(peer, "read loop ended")
    }
  }

  private fun write(peer: Peer, frame: ByteArray): Boolean {
    if (frame.isEmpty() || frame.size > MAX_FRAME) {
      Log.w(TAG, "Refusing to send a ${frame.size}-byte frame on the LAN link")
      return false
    }
    return try {
      synchronized(peer.writeLock) {
        peer.output.writeShort(frame.size)
        peer.output.write(frame)
        peer.output.flush()
      }
      true
    } catch (e: IOException) {
      if (!peer.closed.get()) Log.w(TAG, "write to ${peer.label} failed: ${e.message}")
      false
    }
  }

  private fun close(peer: Peer, why: String) {
    if (!peer.closed.compareAndSet(false, true)) return
    val linkId = peer.linkId
    peer.linkId = -1
    peer.pump?.stop()
    peer.pump = null
    runCatching { peer.socket.close() }
    if (linkId >= 0) {
      runCatching { node.detachLink(linkId) }
      MeshStatus.onLinkDetached(linkId)
      Log.i(TAG, "Detached LAN link $linkId from ${peer.label} ($why)")
    }
  }

  // ---- multicast -------------------------------------------------------------

  /**
   * NsdManager does its own mDNS in the system service, but some Wi-Fi drivers drop multicast
   * for apps unless a lock is held, which makes discovery flaky. Needs CHANGE_WIFI_MULTICAST_STATE.
   */
  private fun acquireMulticastLock() {
    try {
      val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
      multicastLock = wifi.createMulticastLock("asher-mesh-lan").apply {
        setReferenceCounted(false)
        acquire()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Multicast lock unavailable", e)
    }
  }

  private fun releaseMulticastLock() {
    multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
    multicastLock = null
  }
}
