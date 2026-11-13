# Android-манифест и тема шторки

Файл: `app/src/main/AndroidManifest.xml`. Ниже — то, что реально объявлено, и зачем.

## Разрешения

| Разрешение | Зачем |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | TLS-сервер, клиент, веб-портал |
| `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | UDP-мультикаст и multicast lock |
| `WAKE_LOCK` | не давать связи заснуть во время передачи |
| `FOREGROUND_SERVICE` + `..._CONNECTED_DEVICE` + `..._DATA_SYNC` | фоновая служба обнаружения и приёма |
| `POST_NOTIFICATIONS` | запросы на приём и прогресс |
| `SYSTEM_ALERT_WINDOW` | шторка поверх чужих приложений (опционально) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | кнопка «Не оптимизировать батарею» |
| `RECEIVE_BOOT_COMPLETED` | автозапуск службы после перезагрузки |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`) | Android 13+, mDNS/UDP без доступа к геолокации |
| `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | BLE-присутствие, Android 12+ |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (`maxSdkVersion=30`) | BLE на Android 8–11 |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | сохранение в Download на Android 8–9 |

`POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES` и Bluetooth-разрешения запрашиваются во время
работы из `AppRoot` — при первом запуске, а не при установке.

## Компоненты

```xml
<service
    android:name=".service.RadarService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice|dataSync"
    android:stopWithTask="false" />

<service
    android:name=".tile.ClipboardTileService"
    android:exported="true"
    android:icon="@drawable/ic_tile_clipboard"
    android:label="@string/tile_clipboard"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
    <meta-data android:name="android.service.quicksettings.ACTIVE_TILE" android:value="false" />
</service>

<activity
    android:name=".overlay.IncomingRequestActivity"
    android:exported="false"
    android:excludeFromRecents="true"
    android:launchMode="singleTop"
    android:taskAffinity=""
    android:theme="@style/Theme.FluxDrop.Transparent" />

<receiver android:name=".service.DecisionReceiver" android:exported="false" />

<receiver android:name=".service.BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

`<application>` использует `android:name=".FluxApp"`, `allowBackup="false"`,
`usesCleartextTraffic="true"` (нужно для веб-портала по HTTP в локальной сети).

## Тема прозрачной Activity

```xml
<style name="Theme.FluxDrop.Transparent" parent="android:Theme.Material.Light.NoActionBar">
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:windowNoTitle">true</item>
    <item name="android:backgroundDimEnabled">false</item>
    <item name="android:windowAnimationStyle">@null</item>
    <item name="android:windowIsFloating">false</item>
    <item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>
</style>
```

`backgroundDimEnabled=false` обязателен: затемнение рисует сама Compose-шторка, иначе
получится двойной scrim и «прыжок» при закрытии. Базовая тема — платформенная, потому что
Material 3-тема приходит из Compose; Activity нужна лишь как прозрачный контейнер.

## Разрешение overlay

Запрашивается только из UI (из фонового сервиса открывать настройки нельзя):

```kotlin
if (!Settings.canDrawOverlays(context)) {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
```

Кнопка живёт в настройках, в блоке «Фоновая работа». Пока разрешения нет, запрос всё равно
приходит: прозрачной Activity (если приложение на экране) или heads-up уведомлением.

## Doze / батарея

```kotlin
val pm = context.getSystemService(PowerManager::class.java)
if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
```

Плюс `startForeground` с `FOREGROUND_SERVICE_IMMEDIATE`, чтобы уведомление о работе службы
появлялось сразу. Долгие передачи не ограничены таймером `dataSync`, потому что приём идёт
внутри уже поднятого `RadarService` и не запускает отдельную `dataSync`-службу.

## Full-screen intent

На Android 14+ `setFullScreenIntent` работает только с разрешением
`USE_FULL_SCREEN_INTENT` (выдаётся пользователем). Код проверяет
`NotificationManager.canUseFullScreenIntent()` и при отказе просто показывает heads-up с
кнопками — приём от этого не страдает.
