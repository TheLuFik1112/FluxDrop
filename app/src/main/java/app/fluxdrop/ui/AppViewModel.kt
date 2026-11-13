package app.fluxdrop.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fluxdrop.FluxApp
import app.fluxdrop.core.CrashReporter
import app.fluxdrop.core.Diag
import app.fluxdrop.core.PayloadBuilder
import app.fluxdrop.core.PendingCrash
import app.fluxdrop.core.QueueItem
import app.fluxdrop.core.QueueKind
import app.fluxdrop.data.DiscoveredPeer
import app.fluxdrop.net.PortalFile
import app.fluxdrop.service.RadarService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class SendState(
    val peerName: String,
    val fraction: Float,
    val text: String,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val g = (application as FluxApp).graph
    private val context: Context get() = getApplication()

    val deviceName = g.settings.deviceName
    val serviceEnabled = g.settings.serviceEnabled
    val autoAccept = g.settings.autoAcceptTrusted
    val portalEnabled = g.settings.portalEnabled
    val developerMode = g.settings.developerMode
    val autoSendReports = g.settings.autoSendReports

    val peers = g.peers.discovered
    val trusted = g.peers.trusted
    val history = g.history.entries

    val serverState = g.serverState
    val lastSelfCheck = g.lastSelfCheck

    /** Множество id доверенных устройств: пересчитывается только при реальном изменении. */
    val trustedIds: StateFlow<Set<String>> = g.peers.trusted
        .map { list -> list.filterNot { it.revoked }.map { it.id }.toSet() }
        .distinctUntilChanged()
        .stateIn(g.scope, SharingStarted.Eagerly, emptySet())

    val queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val sending = MutableStateFlow<SendState?>(null)
    val message = MutableStateFlow<String?>(null)
    val portalUrl = MutableStateFlow<String?>(null)

    private val _crash = MutableStateFlow<PendingCrash?>(null)
    val crash: StateFlow<PendingCrash?> = _crash

    init {
        Diag.log("viewmodel", "init")
        // Ничего тяжёлого в конструкторе: чтение отчёта и портала — в фоне.
        g.scope.launch {
            loadPendingCrash()
            refreshPortal()
            Diag.log("viewmodel", "ready")
        }
    }

    private fun loadPendingCrash() {
        val pending = runCatching { CrashReporter.pending(context) }.getOrNull() ?: return
        if (g.settings.autoSendReports.value) {
            val sent = runCatching { CrashReporter.send(context, pending) }.getOrNull()
            CrashReporter.clear(context)
            message.value = if (sent != null) {
                "Отчёт о сбое отправлен разработчику"
            } else {
                "Не удалось отправить отчёт о сбое"
            }
        } else {
            _crash.value = pending
        }
    }

    fun dismissCrash() {
        _crash.value = null
        CrashReporter.clear(context)
    }

    fun sendCrash() {
        val report = _crash.value ?: return
        _crash.value = null
        g.scope.launch {
            val path = runCatching { CrashReporter.send(context, report) }.getOrNull()
            CrashReporter.clear(context)
            message.value = if (path != null) "Логи сохранены: $path" else "Не удалось отправить логи"
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val items = uris.map { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                QueueItem(
                    kind = QueueKind.FILE,
                    uri = uri,
                    name = PayloadBuilder.displayName(context, uri),
                    size = PayloadBuilder.sizeOf(context, uri),
                    mime = PayloadBuilder.mimeOf(context, uri),
                )
            }
            withContext(Dispatchers.Main) {
                queue.value = queue.value + items
                publishPortal()
            }
        }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val document = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            val item = QueueItem(
                kind = QueueKind.FOLDER,
                uri = uri,
                name = document?.name ?: "Папка",
            )
            withContext(Dispatchers.Main) {
                queue.value = queue.value + item
                publishPortal()
            }
        }
    }

    fun addText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        queue.value = queue.value + QueueItem(
            kind = QueueKind.TEXT,
            text = clean,
            name = if (clean.length > 32) clean.take(32) + "…" else clean,
        )
        publishPortal()
    }

    fun removeItem(id: String) {
        queue.value = queue.value.filterNot { it.id == id }
        publishPortal()
    }

    fun clearQueue() {
        queue.value = emptyList()
        publishPortal()
    }

    fun sendTo(peer: DiscoveredPeer) {
        if (queue.value.isEmpty()) {
            message.value = "Сначала добавьте файлы или текст"
            return
        }
        if (sending.value != null) {
            message.value = "Уже идёт передача"
            return
        }

        viewModelScope.launch {
            val built = withContext(Dispatchers.Default) {
                PayloadBuilder.build(context, queue.value)
            }
            if (built == null) {
                message.value = "Нечего отправлять"
                return@launch
            }

            val progress: (Float, String) -> Unit = { fraction, text ->
                sending.value = SendState(peer.info.name, fraction, text)
            }

            sending.value = SendState(peer.info.name, 0f, "Подключение…")
            var result = if (peer.ip.isBlank()) {
                Result.failure(java.io.IOException("нет общего Wi-Fi"))
            } else {
                g.client.send(peer.info, peer.ip, built, progress)
            }

            // Обычный путь не сработал — поднимаем прямое Wi-Fi Direct соединение (без роутера).
            if (result.isFailure && shouldTryP2p(result.exceptionOrNull())) {
                sending.value = SendState(peer.info.name, 0f, "Прямое соединение…")
                val directIp = runCatching { g.p2p.ensureLink(peer.info.name) }.getOrNull()
                if (directIp != null) {
                    Diag.log("p2p", "retry transfer over $directIp")
                    result = g.client.send(peer.info, directIp, built, progress)
                }
            }

            sending.value = null

            if (result.isSuccess) {
                queue.value = emptyList()
                publishPortal()
                message.value = "Отправлено: ${peer.info.name}"
            } else {
                message.value = result.exceptionOrNull()?.message ?: "Не удалось отправить"
            }
        }
    }

    private fun shouldTryP2p(error: Throwable?): Boolean {
        val text = error?.message.orEmpty()
        if (text.contains("отклонен", ignoreCase = true)) return false
        return true
    }

    /** Вызывается плиткой быстрых настроек, когда Activity уже на экране. */
    fun sendClipboardNow() {
        val text = readClipboard()
        if (text.isNullOrBlank()) {
            message.value = "Буфер обмена пуст"
            return
        }
        val trustedIds = g.peers.trusted.value.filterNot { it.revoked }.map { it.id }.toSet()
        val target = g.peers.discovered.value.firstOrNull {
            it.info.id in trustedIds && it.ip.isNotBlank()
        }
        if (target == null) {
            message.value = "Нет доверенного устройства рядом"
            return
        }

        viewModelScope.launch {
            val result = g.client.sendText(target.info, target.ip, text)
            message.value = if (result.isSuccess) {
                "Отправлено: ${target.info.name}"
            } else {
                result.exceptionOrNull()?.message ?: "Не удалось отправить"
            }
        }
    }

    private fun readClipboard(): String? {
        val manager = context.getSystemService(android.content.ClipboardManager::class.java)
            ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    fun setDeviceName(name: String) {
        g.settings.setDeviceName(name)
    }

    fun setServiceEnabled(enabled: Boolean) {
        g.settings.setServiceEnabled(enabled)
        if (enabled) RadarService.start(context) else RadarService.stop(context)
    }

    fun setAutoAccept(enabled: Boolean) {
        g.settings.setAutoAcceptTrusted(enabled)
    }

    fun setPortalEnabled(enabled: Boolean) {
        g.settings.setPortalEnabled(enabled)
        if (enabled) {
            g.portal.rotateToken()
            publishPortal()
            refreshPortal()
        } else {
            portalUrl.value = null
            g.portal.publish(emptyList())
            message.value = "Веб-портал выключен"
        }
    }

    fun refreshPortal() {
        portalUrl.value = if (g.settings.portalEnabled.value) g.portal.url() else null
    }

    fun revoke(id: String) {
        g.peers.revoke(id)
        message.value = "Устройство больше не доверенное"
    }

    // ── Для разработчиков ────────────────────────────────────────────────

    fun setDeveloperMode(enabled: Boolean) = g.settings.setDeveloperMode(enabled)

    fun setAutoSendReports(enabled: Boolean) = g.settings.setAutoSendReports(enabled)

    fun checkServer() {
        g.scope.launch {
            val result = runCatching { g.probeServer() }
                .getOrElse { "Ошибка: ${it.javaClass.simpleName}: ${it.message}" }
            g.lastSelfCheck.value = result
            Diag.log("probe", "manual check: $result")
            message.value = result
        }
    }

    fun saveLogs() {
        g.scope.launch {
            val path = runCatching { Diag.dump("manual") }.getOrNull()
            message.value = path?.let { "Отчёт сохранён: $it" } ?: "Не удалось сохранить отчёт"
        }
    }

    fun restartServer() {
        g.scope.launch {
            val state = g.restartServer()
            message.value = "Сервер: $state"
        }
    }

    fun serverStats(): String = buildString {
        appendLine("Сервер: ${g.serverState.value}")
        appendLine("Проверка: ${g.lastSelfCheck.value}")
        appendLine("TLS-порт: 53530, веб: 53531")
        appendLine("Устройств рядом: ${g.peers.discovered.value.size}")
        appendLine("Доверенных: ${g.peers.trusted.value.count { !it.revoked }}")
        appendLine("Передач в истории: ${g.history.entries.value.size}")
        append("P2P: ${if (g.p2p.isSupported()) "доступен" else "нет"}")
    }

    private fun publishPortal() {
        if (!g.settings.portalEnabled.value) return
        val files = queue.value
            .filter { it.kind != QueueKind.TEXT && it.uri != null }
            .map { PortalFile(id = it.id, name = it.name, size = it.size, uri = it.uri.toString()) }
        g.portal.publish(files)
    }
}