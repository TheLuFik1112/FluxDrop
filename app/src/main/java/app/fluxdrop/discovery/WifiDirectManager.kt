package app.fluxdrop.discovery

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.fluxdrop.core.Diag
import app.fluxdrop.core.TLS_PORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Что мешает Wi-Fi Direct работать прямо сейчас. */
enum class WifiDirectReadiness {
    UNKNOWN,
    READY,
    MISSING_PERMISSIONS,
    LOCATION_DISABLED,
    UNSUPPORTED,
}

/** Текущее состояние прямого канала (для журнала и UI). */
sealed interface WifiDirectState {
    data object Idle : WifiDirectState
    data object Discovering : WifiDirectState
    data class Connecting(val target: String) : WifiDirectState
    data class Connected(val peerAddress: String?, val weAreGroupOwner: Boolean) : WifiDirectState
    data class Failed(val reason: String) : WifiDirectState
}

/**
 * Wi-Fi Direct без роутера.
 *
 * Почему просто `discoverPeers()` не работает:
 *  • Android 12 и ниже требует runtime-разрешение ACCESS_FINE_LOCATION **и**
 *    включённую службу геолокации;
 *  • Android 13+ требует NEARBY_WIFI_DEVICES;
 *  • список устройств приходит не сразу — его надо запрашивать через
 *    `requestPeers()` по широковещательному событию PEERS_CHANGED;
 *  • поиск «затухает», поэтому его нужно перезапускать каждые 15 секунд.
 *
 * Дополнительно мы вещаем о себе через DNS-SD (addLocalService + discoverServices),
 * чтобы находить устройство по имени, даже когда обычный peer discovery молчит.
 */
class WifiDirectManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val SERVICE_TYPE = "_fluxdrop._tcp"
        private const val REDISCOVER_INTERVAL_MS = 15_000L
        private const val PEERS_POLL_MS = 700L

        /** Адрес клиента, когда владелец группы — мы. */
        private const val CLIENT_ADDRESS = "192.168.49.2"

        /** Адрес владельца группы по умолчанию (Android всегда .1). */
        private const val GROUP_OWNER_ADDRESS = "192.168.49.1"
    }

    private val appContext: Context = context.applicationContext

    private val manager: WifiP2pManager? = runCatching {
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }.getOrNull()

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow<WifiDirectState>(WifiDirectState.Idle)
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private val peersByAddress = ConcurrentHashMap<String, WifiP2pDevice>()
    private val advertisementNames = ConcurrentHashMap<String, String>()

    @Volatile
    private var connectionInfo: WifiP2pInfo? = null

    @Volatile
    private var p2pEnabled = true

    private var discoveryLoop: Job? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var localServiceAdded = false

    // ── готовность ───────────────────────────────────────────────────────

    fun isSupported(): Boolean = manager != null

    fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
    }

    fun missingPermissions(): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
    }

    /** До Android 13 Wi-Fi Direct не ищет устройства без включённой геолокации. */
    fun isLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return true
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun readiness(): WifiDirectReadiness {
        if (manager == null) return WifiDirectReadiness.UNSUPPORTED
        if (missingPermissions().isNotEmpty()) return WifiDirectReadiness.MISSING_PERMISSIONS
        if (!isLocationEnabled()) return WifiDirectReadiness.LOCATION_DISABLED
        return WifiDirectReadiness.READY
    }

    // ── постоянное вещание о себе ────────────────────────────────────────

    /**
     * Регистрирует DNS-SD-службу и запускает поиск. Можно вызывать многократно —
     * повторный вызов ничего не дублирует.
     */
    fun startDiscovery(deviceName: String = "FluxDrop") {
        if (readiness() != WifiDirectReadiness.READY) {
            Diag.log("p2p", "поиск не запущен: ${readiness()}")
            return
        }
        val manager = manager ?: return
        val channel = ensureChannel(manager) ?: return

        registerLocalService(manager, channel, deviceName)
        startServiceDiscovery(manager, channel)
        discoverPeers(manager, channel)

        if (discoveryLoop?.isActive == true) return
        _state.value = WifiDirectState.Discovering
        discoveryLoop = scope.launch {
            while (isActive) {
                delay(REDISCOVER_INTERVAL_MS)
                // Android «забывает» про поиск через ~15 секунд — перезапускаем.
                discoverPeers(manager, channel)
                startServiceDiscovery(manager, channel)
            }
        }
    }

    fun stopDiscovery() {
        discoveryLoop?.cancel()
        discoveryLoop = null
        if (_state.value is WifiDirectState.Discovering) _state.value = WifiDirectState.Idle
    }

    private fun registerLocalService(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        deviceName: String,
    ) {
        if (localServiceAdded) return
        val instance = "FD-${safeName(deviceName)}"
        val record = mapOf(
            "n" to deviceName.take(40),
            "p" to TLS_PORT.toString(),
        )
        val info = WifiP2pDnsSdServiceInfo.newInstance(instance, SERVICE_TYPE, record)
        runCatching {
            manager.addLocalService(channel, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    localServiceAdded = true
                    Diag.log("p2p", "DNS-SD служба зарегистрирована: $instance")
                }

                override fun onFailure(reason: Int) {
                    Diag.log("p2p", "addLocalService failed: $reason")
                }
            })
        }
    }

    private fun startServiceDiscovery(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        setServiceListeners(manager, channel)
        if (serviceRequest == null) {
            val request = WifiP2pDnsSdServiceRequest.newInstance()
            serviceRequest = request
            runCatching {
                manager.addServiceRequest(channel, request, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Diag.log("p2p", "service request добавлен")
                    override fun onFailure(reason: Int) =
                        Diag.log("p2p", "addServiceRequest failed: $reason")
                })
            }
        }
        runCatching {
            manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Diag.log("p2p", "discoverServices ok")
                override fun onFailure(reason: Int) =
                    Diag.log("p2p", "discoverServices failed: $reason")
            })
        }
    }

    private fun setServiceListeners(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        runCatching {
            manager.setDnsSdResponseListeners(
                channel,
                { instanceName, registrationType, source ->
                    if (source != null) {
                        val address = source.deviceAddress
                        advertisementNames[address] =
                            instanceName.orEmpty().removePrefix("FD-")
                        peersByAddress[address] = source
                        Diag.log("p2p", "DNS-SD служба: $instanceName ($registrationType)")
                        requestPeers(manager, channel)
                    }
                },
                { _, txt, source ->
                    if (source != null) {
                        val address = source.deviceAddress
                        val name = txt?.get("n")
                        if (!name.isNullOrBlank()) advertisementNames[address] = name
                        peersByAddress[address] = source
                    }
                },
            )
        }.onFailure { Diag.log("p2p", "setDnsSdResponseListeners failed: $it") }
    }

    private fun safeName(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '-' }.take(20).ifBlank { "android" }

    // ── обычный поиск устройств ──────────────────────────────────────────

    private fun discoverPeers(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        runCatching {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Diag.log("p2p", "discoverPeers ok")
                override fun onFailure(reason: Int) =
                    Diag.log("p2p", "discoverPeers failed: $reason")
            })
        }
    }

    private fun requestPeers(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        runCatching {
            manager.requestPeers(channel) { list ->
                list?.deviceList?.forEach { device ->
                    peersByAddress[device.deviceAddress] = device
                }
            }
        }
    }

    // ── соединение ───────────────────────────────────────────────────────

    /** Возвращает IP собеседника в P2P-сети либо null. */
    suspend fun ensureLink(peerName: String, timeoutMs: Long = 45_000L): String? =
        withTimeoutOrNull(timeoutMs) {
            when (readiness()) {
                WifiDirectReadiness.READY -> Unit
                else -> {
                    Diag.log("p2p", "нет готовности: ${readiness()}")
                    return@withTimeoutOrNull null
                }
            }
            val manager = manager ?: return@withTimeoutOrNull null
            val channel = ensureChannel(manager) ?: return@withTimeoutOrNull null

            // 1. Может, группа уже поднята.
            currentConnection(manager, channel)?.let { info ->
                val address = addressOf(info)
                if (address != null && reachable(address)) {
                    Diag.log("p2p", "уже в группе: $address")
                    return@withTimeoutOrNull address
                }
            }

            // 2. Ищем устройство.
            startDiscovery(peerName)
            val device = awaitPeer(manager, channel, peerName)
            if (device == null) {
                Diag.log("p2p", "устройство «$peerName» не найдено")
                return@withTimeoutOrNull null
            }

            // 3. Поднимаем группу.
            _state.value = WifiDirectState.Connecting(device.deviceName)
            Diag.log("p2p", "connect -> ${device.deviceName} (${device.deviceAddress})")
            runCatching { manager.cancelConnect(channel, null) }
            if (!connect(manager, channel, device)) return@withTimeoutOrNull null

            val info = awaitConnection(manager, channel)
            if (info == null) {
                _state.value = WifiDirectState.Failed("группа не сформировалась")
                Diag.log("p2p", "группа не сформировалась")
                return@withTimeoutOrNull null
            }

            val address = addressOf(info)
            _state.value = WifiDirectState.Connected(address, info.isGroupOwner)
            Diag.log(
                "p2p",
                "группа готова: owner=${info.isGroupOwner}, " +
                    "groupOwnerAddress=${info.groupOwnerAddress?.hostAddress}, peer=$address",
            )
            address?.takeIf { reachable(it) }
        }

    private suspend fun awaitPeer(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        name: String,
    ): WifiP2pDevice? {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            requestPeers(manager, channel)
            delay(PEERS_POLL_MS)

            val all = peersByAddress.values.toList()
            all.firstOrNull { it.deviceName.equals(name, ignoreCase = true) }?.let { return it }
            all.firstOrNull { device ->
                val advertised = advertisementNames[device.deviceAddress]
                advertised != null && advertised.equals(name, ignoreCase = true)
            }?.let { return it }
            all.firstOrNull { device ->
                device.deviceName.contains(name, ignoreCase = true) ||
                    name.contains(device.deviceName, ignoreCase = true)
            }?.let { return it }
            all.firstOrNull { device ->
                advertisementNames[device.deviceAddress]?.let {
                    it.contains(name, ignoreCase = true) || name.contains(it, ignoreCase = true)
                } == true
            }?.let { return it }
        }
        // Если рядом ровно одно устройство — почти наверняка это оно.
        return peersByAddress.values.singleOrNull()
    }

    private suspend fun connect(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        device: WifiP2pDevice,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // предпочитаем быть клиентом: адрес владельца известен сразу
        }
        runCatching {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Diag.log("p2p", "запрос на соединение принят")
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

    private suspend fun awaitConnection(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): WifiP2pInfo? {
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            connectionInfo?.let { if (it.groupFormed) return it }
            val info = currentConnection(manager, channel)
            if (info != null) return info
            delay(400)
        }
        return null
    }

    private suspend fun currentConnection(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): WifiP2pInfo? = suspendCancellableCoroutine { cont ->
        runCatching {
            manager.requestConnectionInfo(channel) { info ->
                if (info?.groupFormed == true) connectionInfo = info
                if (cont.isActive) cont.resume(info?.takeIf { it.groupFormed })
            }
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    /** IP владельца группы (после коннекта). */
    fun groupOwnerAddress(): String? {
        val info = connectionInfo ?: return null
        if (!info.groupFormed || info.isGroupOwner) return null
        return info.groupOwnerAddress?.hostAddress
    }

    private fun addressOf(info: WifiP2pInfo): String? {
        if (!info.groupFormed) return null
        // Если владелец группы — мы, собеседник получил .2;
        // если владелец — собеседник, берём его адрес.
        return if (info.isGroupOwner) {
            CLIENT_ADDRESS
        } else {
            info.groupOwnerAddress?.hostAddress ?: GROUP_OWNER_ADDRESS
        }
    }

    private fun reachable(ip: String): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(ip, TLS_PORT), 2_500) }
        true
    }.getOrDefault(false)

    // ── системные вызовы и приёмник ──────────────────────────────────────

    private fun ensureChannel(manager: WifiP2pManager): WifiP2pManager.Channel? {
        channel?.let { return it }
        val created = runCatching {
            manager.initialize(appContext, Looper.getMainLooper()) {
                Diag.log("p2p", "канал Wi-Fi Direct потерян")
                channel = null
            }
        }.getOrNull() ?: return null
        channel = created
        registerReceiver()
        return created
    }

    @Suppress("DEPRECATION")
    private fun registerReceiver() {
        if (receiver != null) return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val manager = manager ?: return
                val channel = channel ?: return
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        p2pEnabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Diag.log("p2p", "Wi-Fi Direct включён: $p2pEnabled")
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Обязательный шаг: событие само по себе список не отдаёт.
                        requestPeers(manager, channel)
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info: WifiP2pInfo? = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_INFO,
                        )
                        if (info != null) {
                            connectionInfo = info
                            if (info.groupFormed) {
                                val address = addressOf(info)
                                _state.value = WifiDirectState.Connected(address, info.isGroupOwner)
                                Diag.log(
                                    "p2p",
                                    "connection changed: owner=${info.isGroupOwner}, " +
                                        "адрес=$address",
                                )
                            }
                        }
                        requestPeers(manager, channel)
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device: WifiP2pDevice? = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                        )
                        device?.let { peersByAddress[it.deviceAddress] = it }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                listener,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = listener
        }.onFailure { Diag.log("p2p", "приёмник не зарегистрирован: $it") }
    }

    fun release() {
        stopDiscovery()
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            val request = serviceRequest
            if (request != null) {
                runCatching { mgr.removeServiceRequest(ch, request, null) }
            }
            runCatching { mgr.removeGroup(ch, null) }
        }
        serviceRequest = null
        runCatching { receiver?.let { appContext.unregisterReceiver(it) } }
        receiver = null
        runCatching { ch?.close() }
        channel = null
        connectionInfo = null
        peersByAddress.clear()
        advertisementNames.clear()
        localServiceAdded = false
        _state.value = WifiDirectState.Idle
    }

    /** Открыть системные настройки: геолокация или разрешения приложения. */
    fun settingsIntent(): Intent = if (readiness() == WifiDirectReadiness.LOCATION_DISABLED) {
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", appContext.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}