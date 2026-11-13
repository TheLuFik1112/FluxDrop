# FluxDrop — архитектура

Один Android-модуль `:app`, Kotlin, Compose Material 3. Всё работает на телефоне: TLS-сервер,
обнаружение, приём и отправка. Облака и аккаунтов нет.

## 1. Структура

```
app/src/main/java/app/fluxdrop/
├─ FluxApp.kt          Application: AppGraph, каналы уведомлений, счётчик видимых Activity
├─ MainActivity.kt     Compose-хост
├─ core/
│  ├─ Models.kt        DTO протокола, порты, Payload, Offer, HistoryEntry
│  ├─ Secure.kt        IdentityStore (EC P-256), самоподписанный серт, pinning trust manager
│  ├─ Queue.kt         QueueItem / SourceFile / BuiltPayload
│  ├─ PayloadBuilder.kt сборка пакета из файлов, папок (SAF) и текста
│  └─ AppGraph.kt      ручной DI: репозитории, сервер, клиент, discovery, runtime
├─ data/               JsonFile, SettingsRepository, PeerRepository, HistoryRepository
├─ discovery/          Discovery.kt (UDP + NSD), BlePresence.kt
├─ net/
│  ├─ TransferServer.kt Ktor CIO: TLS API + HTTP веб-портал
│  ├─ TransferClient.kt отправка: offer → meta → чанки → finish → complete
│  ├─ ReceiveStore.kt   приём чанков, докачка, проверка SHA-256, экспорт в Download
│  ├─ IncomingCoordinator.kt  единственная точка правды о входящих
│  ├─ Downloads.kt      MediaStore / legacy путь
│  └─ Portal.kt         HTML/JS портала, токен, локальный IP
├─ overlay/            OverlaySheetHost, IncomingRequestActivity
├─ service/            RadarService, IncomingPresenter, IncomingNotifier, приёмники
├─ tile/               ClipboardTileService
└─ ui/                 Theme, AppRoot, AppViewModel, Home/History/Settings, IncomingSheetContent, Qr
```

Зависимости: `ui/service/overlay → net/data/discovery → core`. Обратных стрелок нет.

## 2. Стек

| Слой | Что используется |
|---|---|
| UI | Compose (BOM 2024.12.01), material3, dynamic color на Android 12+ |
| Асинхронность | coroutines 1.9, `StateFlow`, `CompletableDeferred` для решения по offer |
| Сеть | Ktor 3.0.3 CIO (TLS + HTTP), `HttpsURLConnection` на клиенте |
| Сериализация | kotlinx-serialization JSON |
| Хранение | JSON-файлы в `filesDir` + SharedPreferences |
| Крипто | JCA (`EC`, `SHA256withECDSA`), BouncyCastle для X.509, AndroidKeyStore для AES-обёртки |
| BLE | платформенные `BluetoothLeAdvertiser` / `BluetoothLeScanner` |
| QR | ZXing core (генерация) |

DI, БД и Ktor-клиент намеренно не используются: меньше зависимостей — меньше ломается при сборке.

## 3. Состояние и потоки

Сервис и UI живут в одном процессе и общаются через `AppGraph`-репозитории (`StateFlow`), а не
через bind. `IncomingCoordinator.handleOffer()` выполняется прямо внутри HTTP-запроса
отправителя: пока пользователь смотрит шторку, запрос висит; `accept()`/`decline()` завершают
`CompletableDeferred`. Таймаут — 120 секунд.

Автоприём включается, только если одновременно: устройство в доверенных, отпечаток сертификата
совпадает с сохранённым, подпись offer валидна, тип передачи разрешён, устройство не отозвано.

## 4. Протокол

Порты: TLS `53530`, веб-портал `53531`, обнаружение (UDP) `53532`. Чанк — 4 МБ.

```
GET  /v1/info                        → PeerInfo
POST /v1/offer                       → OfferResponse {accepted, token, transferId}
GET  /v1/resume/{tid}/{index}        → ResumeState {received:[...]}
POST /v1/file/{tid}/{index}/meta     → FileMeta с chunkHashes
PUT  /v1/file/{tid}/{index}?chunk=N  → тело = байты чанка
POST /v1/file/{tid}/{index}/finish   → проверка SHA-256 файла
POST /v1/transfer/{tid}/complete     → экспорт в Download и запись истории
```

Все маршруты `/v1/*`, кроме `info` и `offer`, требуют заголовок `X-Transfer-Token` — токен
выдаётся один раз при принятии offer.

Веб-портал (HTTP, без TLS): `/web?t=…` — страница, `/web/list?t=…` — JSON списка,
`/web/get?t=…&id=…` — скачивание с поддержкой `Range`, `/web/up?t=…&name=…` — загрузка с
браузера. Без токена — 403.

## 5. Приём файлов и докачка

- `.part` пишется `RandomAccessFile` по смещению `chunkIndex * chunkSize`, порядок не важен.
- Принятые индексы хранятся в `<index>.idx`; после перезапуска процесса они перечитываются.
- Каждый чанк сверяется с `chunkHashes[i]`, файл целиком — с `sha256` из манифеста.
- Пустой файл (0 байт) разрешён: `chunkHashes` пуст, но `size == 0`; SHA-256 пустого файла
  всё равно сверяется.
- Готовый файл переезжает в `incoming/<tid>/out/…`, затем копируется в `Download/FluxDrop`,
  сессия удаляется.

## 6. Обнаружение

- **UDP**: мультикаст `239.255.42.99:53532` + broadcast `255.255.255.255`, beacon раз в 2 с.
  В пакете только id, имя, платформа, порт и префикс отпечатка — чтобы не фрагментировать.
- **mDNS/NSD**: `_fluxdrop._tcp.` с TXT-атрибутами `id/name/fp/platform`, резолв в IP.
- **BLE**: advertise + scan по сервис-фильтру; в manufacturer data — версия, 4 байта id, порт.
  Bluetooth помогает «увидеть» устройство, но передача требует общего Wi-Fi.

Полный `PeerInfo` (сертификат и ключ) приходит только с offer — доверие проверяется по нему, а
не по beacon.

## 7. Фон

- `RadarService` — foreground service (`connectedDevice`, при отказе — фолбэк на `dataSync`),
  держит TLS-сервер, discovery и multicast lock. `START_STICKY`, `stopWithTask=false`.
- `BootReceiver` поднимает сервис после перезагрузки, если пользователь не выключил службу.
- Doze: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` из настроек (не автоматически).
- Уведомления: `radar` (low), `incoming` (high, heads-up), `progress` (low, silent).

## 8. Входящий запрос: каскад

1. **Приложение на экране** → прозрачная `IncomingRequestActivity` со штатной
   `ModalBottomSheet`.
2. **Фон + `SYSTEM_ALERT_WINDOW` + экран разблокирован** → overlay-окно
   `TYPE_APPLICATION_OVERLAY` с собственной шторкой (scrim + slide-in).
3. **Иначе** (в том числе на заблокированном экране) → heads-up уведомление с действиями
   «Принять» / «Отклонить» (`DecisionReceiver`).

`ModalBottomSheet` нельзя использовать внутри overlay: M3 открывает её в отдельном Dialog-окне,
которому не назначить тип overlay. Поэтому в overlay своя анимация, а контент общий —
`IncomingSheetContent`.

## 9. Безопасность

- Идентичность: EC P-256. Приватный ключ хранится на диске зашифрованным AES-GCM; ключ
  шифрования — в AndroidKeyStore (alias `fluxdrop.master.v1`).
  *Почему не Keystore-ключ напрямую:* на части прошивок TLS-сервер с неэкспортируемым ключом
  падает на handshake. Компромисс осознанный: ключ не покидает песочницу приложения.
- `DeviceId = SHA-256(SPKI)`. Сертификат самоподписанный, CN содержит префикс id.
- `isSelfConsistent(peer)`: id совпадает с отпечатком сертификата и открытый ключ из offer
  совпадает с ключом сертификата.
- `TransferOffer` подписывается: `transferId|createdAt|expiresAt|senderId|kind|material`,
  `SHA256withECDSA`. Получатель проверяет подпись и срок до показа шторки.
- Клиент пинит сертификат сервера (`PinnedTrustManager`), но только если отпечаток полной
  длины (≥ 64 символов) — из BLE приходит лишь префикс. Первый контакт: шифрование без
  пиннинга, подлинность подтверждает подпись offer.
- Доверие: TOFU. Галочка «Всегда принимать» сохраняет сертификат, ключ и отпечаток.
  Отзыв — в настройках.

## 10. Ограничения

- Нет SAS-сверки при сопряжении: доверие фиксируется по первому подтверждению.
- Нет фонового `WorkManager`-докачка между запусками приложения: незавершённые сессии
  переживают перезапуск процесса, но новую попытку инициирует отправитель.
- Веб-портал без TLS (только в локальной сети, доступ по одноразовому токену).
