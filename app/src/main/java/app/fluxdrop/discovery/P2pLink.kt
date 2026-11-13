package app.fluxdrop.discovery

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import app.fluxdrop.core.Diag
import app.fluxdrop.core.TLS_PORT
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Прямое соединение без роутера: Wi-Fi Direct (P2P).
 *
 * Сценарий: устройства уже нашли друг друга по BLE/UDP, но IP-адрес недоступен
 * (разные сети или вообще нет Wi-Fi). Тогда поднимаем P2P-группу, узнаём адрес
 * владельца группы и гоняем тот же TLS-поток по интерфейсу p2p0.
 *
 * Роли: если владелец группы — мы, клиент получает 192.168.49.2;
 * если владелец — собеседник, его адрес приходит в WifiP2pInfo.
 */
class P2pLink(private val context: Context) {

    private val manager: WifiP2pManager? =
        runCatching { context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }.getOrNull()

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var groupInfo: WifiP2pInfo? = null

    @Volatile
    private var peers: List<WifiP2pDevice> = emptyList()

    fun isSupported(): Boolean = manager != null

    fun release() {
        runCatching { manager?.removeGroup(channel, null) }
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { channel?.close() }
        channel = null
        groupInfo = null
        peers = emptyList()
    }

    /** Возвращает IP, по которому доступен собеседник, либо null. */
    suspend fun ensureLink(peerName: String, timeoutMs: Long = 30_000L): String? =
        withTimeoutOrNull(timeoutMs) {
            val manager = manager ?: return@withTimeoutOrNull null
            if (!hasPermissions()) {
                Diag.log("p2p", "нет разрешений на Wi-Fi Direct")
                return@withTimeoutOrNull null
            }
            val channel = channel(manager) ?: return@withTimeoutOrNull null

            // Уже в группе — просто берём адрес.
            currentGroup(manager, channel)?.let { info ->
                if (info.groupFormed) {
                    val address = addressOf(info)
                    if (address != null && reachable(address)) {
                        Diag.log("p2p", "уже в группе: $address")
                        return@withTimeoutOrNull address
                    }
                }
            }

            Diag.log("p2p", "ищу «$peerName» по Wi-Fi Direct")
            startDiscovery(manager, channel)
            val device = awaitPeer(manager, channel, peerName)
            stopDiscovery(manager, channel)

            if (device == null) {
                Diag.log("p2p", "устройство не найдено по Wi-Fi Direct")
                return@withTimeoutOrNull null
            }

            Diag.log("p2p", "подключаюсь к ${device.deviceName} (${device.deviceAddress})")
            if (!connect(manager, channel, device)) return@withTimeoutOrNull null

            val info = awaitGroupFormed()
            stopDiscovery(manager, channel)
            if (info == null) {
                Diag.log("p2p", "группа не сформировалась")
                return@withTimeoutOrNull null
            }

            val address = addressOf(info)
            Diag.log(
                "p2p",
                "группа готова, owner=${info.isGroupOwner}, адрес=$address",
            )
            address?.takeIf { reachable(it) }
        }

    // ── адресация ────────────────────────────────────────────────────────

    private fun addressOf(info: WifiP2pInfo): String? {
        if (!info.groupFormed) return null
        return if (info.isGroupOwner) {
            // Мы владелец: клиент получает следующий адрес в подсети 192.168.49.x.
            "192.168.49.2"
        } else {
            info.groupOwnerAddress?.hostAddress
        }
    }

    /** Быстрая проверка, что по адресу действительно слушает наш TLS-порт. */
    private fun reachable(ip: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, TLS_PORT), 2_500)
        }
        true
    }.getOrDefault(false)

    // ── поиск и подключение ──────────────────────────────────────────────

    private suspend fun awaitPeer(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        name: String,
    ): WifiP2pDevice? {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            requestPeers(manager, channel)
            delay(500)
            val exact = peers.firstOrNull { it.deviceName.equals(name, ignoreCase = true) }
            if (exact != null) return exact
            val loose = peers.firstOrNull {
                it.deviceName.contains(name, ignoreCase = true) ||
                    name.contains(it.deviceName, ignoreCase = true)
            }
            if (loose != null) return loose
        }
        return peers.firstOrNull()
    }

    private suspend fun connect(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        device: WifiP2pDevice,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        runCatching {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Diag.log("p2p", "запрос на соединение отправлен")
                    if (cont.isActive) cont.resume(true)
                }

                override fun onFailure(reason: Int) {
                    Diag.log("p2p", "connect failed: $reason")
                    if (cont.isActive) cont.resume(false)
                }
            })
        }.onFailure {
            Diag.log("p2p", "connect error: $it")
            if (cont.isActive) cont.resume(false)
        }
    }

    private suspend fun awaitGroupFormed(): WifiP2pInfo? {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            groupInfo?.let { if (it.groupFormed) return it }
            delay(300)
        }
        return null
    }

    private suspend fun currentGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): WifiP2pInfo? = suspendCancellableCoroutine { cont ->
        runCatching {
            manager.requestConnectionInfo(channel) { info ->
                if (cont.isActive) cont.resume(info?.takeIf { it.groupFormed })
            }
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    // ── системные вызовы ─────────────────────────────────────────────────

    private fun hasPermissions(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun channel(manager: WifiP2pManager): WifiP2pManager.Channel? {
        channel?.let { return it }
        val created = runCatching {
            manager.initialize(context, Looper.getMainLooper()) { Diag.log("p2p", "канал потерян") }
        }.getOrNull() ?: return null
        channel = created
        registerReceiver()
        return created
    }

    private fun startDiscovery(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        runCatching {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Diag.log("p2p", "поиск запущен")
                override fun onFailure(reason: Int) = Diag.log("p2p", "поиск не запустился: $reason")
            })
        }
    }

    private fun stopDiscovery(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        runCatching { manager.stopPeerDiscovery(channel, null) }
        runCatching { manager.cancelConnect(channel, null) }
    }

    private fun requestPeers(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        runCatching {
            manager.requestPeers(channel) { list -> peers = list?.deviceList?.toList().orEmpty() }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerReceiver() {
        if (receiver != null) return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val m = manager ?: return
                        val ch = channel ?: return
                        requestPeers(m, ch)
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info: WifiP2pInfo? =
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        if (info != null) groupInfo = info
                    }

                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        Diag.log("p2p", "состояние Wi-Fi Direct: $state")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        runCatching {
            ContextCompat.registerReceiver(context, listener, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = listener
        }.onFailure { Diag.log("p2p", "слушатель не зарегистрирован: $it") }
    }
}