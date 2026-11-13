package app.fluxdrop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

private data class Tab(
    val title: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val tabs = listOf(
    Tab("Отправить", "Отправить", Icons.Rounded.Send),
    Tab("История", "История", Icons.Rounded.History),
    Tab("Настройки", "Настройки", Icons.Rounded.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var qrPayload by remember { mutableStateOf<String?>(null) }

    val peers by viewModel.peers.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val message by viewModel.message.collectAsState()
    val trusted by viewModel.trusted.collectAsState()
    val history by viewModel.history.collectAsState()
    val portalUrl by viewModel.portalUrl.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT < 29) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addUris(uris) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.addFolder(it) } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(tabs[selectedTab].title) })
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen(
                    viewModel = viewModel,
                    queue = queue,
                    peers = peers,
                    sending = sending,
                    onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                    onPickFolder = { folderPicker.launch(null) },
                    onShowQr = { url -> qrPayload = url },
                )
                1 -> HistoryScreen(history)
                else -> SettingsScreen(
                    viewModel = viewModel,
                    trusted = trusted,
                    portalUrl = portalUrl,
                    onShowQr = { url -> qrPayload = url },
                )
            }
        }
    }

    qrPayload?.let { payload ->
        QrDialog(payload = payload, onDismiss = { qrPayload = null })
    }
}
