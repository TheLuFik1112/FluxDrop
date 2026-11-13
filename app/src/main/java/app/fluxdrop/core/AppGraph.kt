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

    val identity by lazy { IdentityStore(app) }
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

    fun startRuntime() {
        if (runtimeActive.value) return
        runtimeActive.value = true

        runCatching { server.start() }
        discovery.start()

        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                peers.prune()
                delay(5_000)
            }
        }
    }

    fun stopRuntime() {
        runtimeActive.value = false
        pruneJob?.cancel()
        pruneJob = null
        discovery.stop()
        runCatching { server.stop() }
    }
}
