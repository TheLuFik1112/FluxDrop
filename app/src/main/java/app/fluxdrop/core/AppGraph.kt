package app.fluxdrop.core

import android.app.Application
import app.fluxdrop.data.HistoryRepository
import app.fluxdrop.data.PeerRepository
import app.fluxdrop.data.SettingsRepository
import app.fluxdrop.discovery.PeerDiscovery
import app.fluxdrop.net.IncomingCoordinator
import app.fluxdrop.net.Portal
import app.fluxdrop.net.ReceiveStore
import app.fluxdrop.net.TransferClient
import app.fluxdrop.net.TransferServer
import app.fluxdrop.service.IncomingNotifier
import app.fluxdrop.service.IncomingPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AppGraph(val app: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val identity by lazy {
        Diag.log("identity", "creating")
        runCatching { IdentityStore(app) }.getOrElse { error ->
            Diag.log("identity", "failed: $error")
            Diag.dump("identity", error)
            throw error
        }.also { Diag.log("identity", "ready") }
    }
    val settings by lazy { SettingsRepository(app) }
    val peers by lazy { PeerRepository(app, json) }
    val history by lazy { HistoryRepository(app, json) }
    val portal by lazy { Portal(app, settings, json) }
    val receiver by lazy { ReceiveStore(app, identity) }
    val notifier by lazy { IncomingNotifier(app) }

    val incoming by lazy { IncomingCoordinator(this) }
    val presenter by lazy { IncomingPresenter(app, this) }
    val client by lazy { TransferClient(this) }
    val server by lazy { TransferServer(this) }

    val discovery by lazy {
        PeerDiscovery(
            context = app,
            scope = scope,
            self = { myPeerInfo() },
            onPeer = { peer, ip, source -> peers.onDiscovered(peer, ip, source) },
        )
    }

    val runtimeActive = MutableStateFlow(false)

    private var pruneJob: Job? = null

    fun myPeerInfo(): PeerInfo {
        val id = identity.identity
        return PeerInfo(
            id = id.id,
            name = settings.deviceName.value,
            platform = "android",
            port = TLS_PORT,
            certFingerprint = id.fingerprint,
            certB64 = id.certB64,
            publicKeyB64 = id.publicKeyB64,
        )
    }

    /** Проверяет свой же сервер по TLS: relay → Ktor → маршрут → JSON. */
    fun probeServer(): String {
        var last: Throwable? = null
        for (attempt in 1..3) {
            val result = runCatching { probeOnce() }
            result.getOrNull()?.let { return it }
            last = result.exceptionOrNull()
            if (attempt < 3) Thread.sleep(400L * attempt)
        }
        throw last ?: IllegalStateException("нет соединения")
    }

    private fun probeOnce(): String {
        val url = java.net.URL("https://127.0.0.1:$TLS_PORT/v1/info")
        val (factory, _) = identity.clientSsl(identity.identity.fingerprint)
        val connection = (url.openConnection() as javax.net.ssl.HttpsURLConnection).apply {
            sslSocketFactory = factory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connectTimeout = 5_000
            readTimeout = 8_000
        }
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            "HTTP $code ${text.take(90)}"
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    fun startRuntime() {
        if (runtimeActive.value) return
        runtimeActive.value = true
        Diag.log("runtime", "start")

        runCatching { server.start() }
            .onFailure { Diag.log("runtime", "server failed: $it") }
        runCatching { discovery.start() }
            .onFailure { Diag.log("runtime", "discovery failed: $it") }

        scope.launch {
            delay(700)
            runCatching { probeServer() }
                .onSuccess { Diag.log("probe", "self-check ok: $it") }
                .onFailure { Diag.log("probe", "self-check failed: $it") }
        }

        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                runCatching { peers.prune() }
                delay(5_000)
            }
        }
    }

    fun stopRuntime() {
        runtimeActive.value = false
        Diag.log("runtime", "stop")
        pruneJob?.cancel()
        pruneJob = null
        discovery.stop()
        runCatching { server.stop() }
    }
}
