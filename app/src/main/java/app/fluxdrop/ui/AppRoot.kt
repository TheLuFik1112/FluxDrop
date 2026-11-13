package app.fluxdrop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.fluxdrop.discovery.WifiDirectReadiness

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
    val crash by viewModel.crash.collectAsState()
    val engineReady by viewModel.isEngineReady.collectAsState()
    val p2pReadiness by viewModel.p2pReadiness.collectAsState()

    // Движок стартует только после того, как кадр уже нарисован.
    LaunchedEffect(Unit) {
        viewModel.startEngine()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshP2pReadiness()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshP2pReadiness() }

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
            // До Android 13 поиск по Wi-Fi Direct и BLE требует геолокации.
            if (Build.VERSION.SDK_INT < 33) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
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

    // Вкладки живут в HorizontalPager: все три страницы остаются в композиции,
    // переключение — мгновенный scrollToPage, без пересоздания экранов.
    val pagerState = rememberPagerState { tabs.size }

    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) pagerState.scrollToPage(selectedTab)
    }
    LaunchedEffect(pagerState.currentPage) {
        if (selectedTab != pagerState.currentPage) selectedTab = pagerState.currentPage
    }

    val onFixP2p: () -> Unit = {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (p2pReadiness == WifiDirectReadiness.MISSING_PERMISSIONS && missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            runCatching { context.startActivity(viewModel.p2pFixIntent()) }
        }
    }

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
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = tabs.lastIndex,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> HomeScreen(
                        viewModel = viewModel,
                        queue = queue,
                        peers = peers,
                        sending = sending,
                        isEngineReady = engineReady,
                        p2pReadiness = p2pReadiness,
                        isActive = selectedTab == 0,
                        onFixP2p = onFixP2p,
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
    }

    qrPayload?.let { payload ->
        QrDialog(payload = payload, onDismiss = { qrPayload = null })
    }

    crash?.let { pending ->
        CrashDialog(
            crash = pending,
            onSend = viewModel::sendCrash,
            onDismiss = viewModel::dismissCrash,
        )
    }
}