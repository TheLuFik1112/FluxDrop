package app.fluxdrop.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluxdrop.core.APP_VERSION
import app.fluxdrop.core.Diag
import app.fluxdrop.core.TrustedPeer
import app.fluxdrop.util.hasOverlayPermission
import app.fluxdrop.util.isIgnoringBatteryOptimizations
import app.fluxdrop.util.openAppNotificationSettings
import app.fluxdrop.util.requestIgnoreBatteryOptimizations
import app.fluxdrop.util.requestOverlayPermission

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    trusted: List<TrustedPeer>,
    portalUrl: String?,
    onShowQr: (String) -> Unit,
) {
    val context = LocalContext.current
    val deviceName by viewModel.deviceName.collectAsState()
    val serviceEnabled by viewModel.serviceEnabled.collectAsState()
    val autoAccept by viewModel.autoAccept.collectAsState()
    val portalEnabled by viewModel.portalEnabled.collectAsState()

    var renameOpen by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(hasOverlayPermission(context)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard("Устройство") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Имя в сети", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { renameOpen = true }) { Text("Изменить") }
            }
        }

        SettingsCard("Фоновая работа") {
            SwitchRow(
                title = "Служба обнаружения",
                subtitle = "Приём передач, даже когда приложение свёрнуто",
                checked = serviceEnabled,
                onCheckedChange = viewModel::setServiceEnabled,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                title = "Шторка поверх других приложений",
                subtitle = if (overlayGranted) "Разрешено" else "Нужно для показа запроса поверх чужих приложений",
                action = if (overlayGranted) "Настроить" else "Разрешить",
            ) {
                requestOverlayPermission(context)
                overlayGranted = hasOverlayPermission(context)
            }
            ActionRow(
                title = "Не оптимизировать батарею",
                subtitle = if (batteryOk) "Разрешено" else "Иначе Android может убивать приём в фоне",
                action = "Открыть",
            ) {
                requestIgnoreBatteryOptimizations(context)
                batteryOk = isIgnoringBatteryOptimizations(context)
            }
            ActionRow(
                title = "Уведомления",
                subtitle = "Запросы на приём и прогресс",
                action = "Открыть",
            ) {
                openAppNotificationSettings(context)
            }
        }

        SettingsCard("Приём") {
            SwitchRow(
                title = "Автоприём от доверенных",
                subtitle = "Без шторки, только тихое уведомление о прогрессе",
                checked = autoAccept,
                onCheckedChange = viewModel::setAutoAccept,
            )
        }

        SettingsCard("Доверенные устройства") {
            if (trusted.none { !it.revoked }) {
                Text(
                    "Пока пусто. Поставьте галочку «Всегда принимать» во входящей шторке.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                trusted.filterNot { it.revoked }.forEach { peer ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                peer.fingerprint.take(16),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.revoke(peer.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Отозвать")
                        }
                    }
                }
            }
        }

        SettingsCard("Веб-портал") {
            SwitchRow(
                title = "Браузер без приложения",
                subtitle = "Страница по QR-коду для скачивания и загрузки файлов",
                checked = portalEnabled,
                onCheckedChange = viewModel::setPortalEnabled,
            )
            if (portalEnabled && portalUrl != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    portalUrl,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onShowQr(portalUrl) }, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Показать QR")
                }
            }
        }

        SettingsCard("О приложении") {
            Text("FluxDrop $APP_VERSION", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Локальная передача: TLS + подпись запросов, файлы чанками 4 МБ с SHA-256, " +
                    "доверенные устройства — по отпечатку ключа.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Диагностика") {
            Text(
                "Если приложение вылетает — сохраните отчёт и пришлите файл из папки Download/FluxDrop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    val path = Diag.dump("manual")
                    Toast.makeText(
                        context,
                        path?.let { "Сохранено: $it" } ?: "Не удалось сохранить отчёт",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                shape = RoundedCornerShape(12.dp),
            ) { Text("Сохранить отчёт") }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (renameOpen) {
        var value by remember(deviceName) { mutableStateOf(deviceName) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Имя устройства") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(40) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setDeviceName(value)
                        renameOpen = false
                    },
                    enabled = value.isNotBlank(),
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) {
            Text(action)
        }
    }
}
