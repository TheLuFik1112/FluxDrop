package app.fluxdrop.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.fluxdrop.core.Beacon
import app.fluxdrop.core.DISCOVERY_PORT
import app.fluxdrop.core.PeerInfo
import app.fluxdrop.core.TLS_PORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

typealias PeerCallback = (PeerInfo, String, String) -> Unit

class PeerDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val self: () -> PeerInfo,
    private val onPeer: PeerCallback,
) {
    private val udp = UdpDiscovery(scope, self, onPeer)
    private val nsd = NsdDiscovery(context, self, onPeer)
    private val ble = BlePresence(context, scope, self, onPeer)

    private var started = false

    fun start() {
        if (started) return
        started = true
        runCatching { udp.start() }
        runCatching { nsd.start() }
        runCatching { ble.start() }
    }

    fun stop() {
        started = false
        udp.stop()
        nsd.stop()
        ble.stop()
    }
}

class UdpDiscovery(
    private val scope: CoroutineScope,
    private val self: () -> PeerInfo,
    private val onPeer: PeerCallback,
) {
    private var job: Job? = null
    private var socket: MulticastSocket? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val sock = runCatching {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    runCatching { joinGroup(InetSocketAddress(GROUP, DISCOVERY_PORT), null) }
                }
            }.getOrNull() ?: return@launch

            socket = sock
            launch { beaconLoop(sock) }

            val buffer = ByteArray(2048)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val beacon = runCatching { json.decodeFromString(Beacon.serializer(), text) }.getOrNull()
                        ?: continue
                    if (beacon.id == self().id) continue

                    val peer = PeerInfo(
                        id = beacon.id,
                        name = beacon.name,
                        platform = beacon.platform,
                        port = beacon.port,
                        certFingerprint = beacon.fp,
                    )
                    onPeer(peer, packet.address?.hostAddress.orEmpty(), "udp")
                } catch (_: Exception) {
                    // мусорный пакет или закрытый сокет — продолжаем
                }
            }
        }
    }

    private suspend fun beaconLoop(sock: MulticastSocket) {
        while (scope.isActive) {
            val me = self()
            val beacon = Beacon(
                id = me.id,
                name = me.name,
                platform = me.platform,
                port = me.port,
                fp = me.certFingerprint,
            )
            val payload = json.encodeToString(Beacon.serializer(), beacon).encodeToByteArray()

            listOf(BROADCAST, GROUP).forEach { address ->
                runCatching {
                    sock.send(
                        DatagramPacket(payload, payload.size, InetAddress.getByName(address), DISCOVERY_PORT)
                    )
                }
            }
            delay(2_000)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val BROADCAST = "255.255.255.255"
        const val GROUP = "239.255.42.99"
    }
}

@Suppress("DEPRECATION")
class NsdDiscovery(
    private val context: Context,
    private val self: () -> PeerInfo,
    private val onPeer: PeerCallback,
) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolving = mutableSetOf<String>()

    fun start() {
        val manager = nsd ?: return
        register(manager)
        discover(manager)
    }

    private fun register(manager: NsdManager) {
        val me = self()
        val info = NsdServiceInfo().apply {
            serviceName = "FluxDrop-${me.id.take(8)}"
            serviceType = SERVICE_TYPE
            port = me.port
            runCatching {
                setAttribute("id", me.id)
                setAttribute("name", me.name)
                setAttribute("fp", me.certFingerprint)
                setAttribute("platform", me.platform)
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun discover(manager: NsdManager) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != SERVICE_TYPE) return
                synchronized(resolving) { if (!resolving.add(info.serviceName)) return }

                runCatching {
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            synchronized(resolving) { resolving.remove(serviceInfo.serviceName) }
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            synchronized(resolving) { resolving.remove(serviceInfo.serviceName) }
                            val attributes = serviceInfo.attributes ?: return
                            val id = attributes["id"]?.toString(Charsets.UTF_8) ?: return
                            if (id == self().id) return
                            val peer = PeerInfo(
                                id = id,
                                name = attributes["name"]?.toString(Charsets.UTF_8) ?: serviceInfo.serviceName,
                                platform = attributes["platform"]?.toString(Charsets.UTF_8) ?: "android",
                                port = serviceInfo.port.takeIf { it > 0 } ?: TLS_PORT,
                                certFingerprint = attributes["fp"]?.toString(Charsets.UTF_8) ?: id,
                            )
                            onPeer(peer, serviceInfo.host?.hostAddress.orEmpty(), "nsd")
                        }
                    })
                }.onFailure {
                    synchronized(resolving) { resolving.remove(info.serviceName) }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stop() {
        val manager = nsd ?: return
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery = null
        registration = null
        synchronized(resolving) { resolving.clear() }
    }

    companion object {
        const val SERVICE_TYPE = "_fluxdrop._tcp."
    }
}
