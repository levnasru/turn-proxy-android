# Изоляция RealityVpnService в отдельный процесс

## Проблема

Переключение транспорта vk-turn → reality надёжно крашит приложение
(native SIGSEGV, `Zygote: exited due to signal 11`). Подтверждено живым
тестом 2026-08-13 на устройстве R5CY93LVDGA (SM_S938B): при выключенном
WireGuard (профиль без WG, `libwg-go.so` ни разу не загружается в процесс)
переключение на Reality проходит без краша — только `libgojni.so` в
процессе, `FATAL EXCEPTION`/`signal 11` в логе отсутствуют, приложение живо
и отрисовывает UI 40+ секунд после старта Reality-туннеля.

Причина: `libwg-go.so` (WireGuard, gomobile) и `libgojni.so` (Xray/libXray,
gomobile) — два независимых Go-рантайма, резидентных в одном процессе.
Такое сочетание — известный опасный паттерн (конфликт signal handler /
runtime registration между рантаймами). Оба реальных race condition в
`RealityVpnService.kt`/`AndroidProxyServiceLauncher.kt`, найденных и
исправленных ранее, остаются нужными фиксами, но не были корневой причиной
именно этого краша.

## Решение

Перенести `RealityVpnService` в отдельный Android-процесс
(`android:process=":reality"`), чтобы `libwg-go.so` (загружается только в
основном процессе через `ProxyService`/`WireGuardTunnelManager`) и
`libgojni.so` (загружается только в `:reality`) никогда не оказывались в
одном адресном пространстве.

Масштаб — только `RealityVpnService`. `ProxyService` (ядро-подпроцесс +
WireGuard) остаётся в основном процессе как есть: краш там не
воспроизводился, закладывать более общий механизм изоляции транспортов
сейчас избыточно (YAGNI).

## Что ломается и почему

`ProxyServiceState` (`app/src/main/java/com/freeturn/app/domain/proxy/ProxyServiceState.kt`) —
Kotlin `object`-синглтон с набором `StateFlow`/`SharedFlow` полей
(`isRunning`, `connectionStats`, `startupResult`, `logs`, `tunnelActive`,
`connectedSince`, `teardownComplete`, `wireGuardUp`, `captchaSession`).
`RealityVpnService` сейчас пишет в него напрямую (`setRunning`, `addLog`,
`setStartupResult`, `setConnectionStats`, `setTunnelActive`,
`markConnectedIfAbsent`, `markTeardownStarted/Complete`,
`clearConnectedSince`), а UI/`LocalProxyManager` читают из него же — это
работает только потому, что все они сейчас в одном процессе и делят один
JVM-инстанс объекта.

После переноса в `:reality` у процесса будет своя, независимая копия
Kotlin-объекта `ProxyServiceState` — записи туда никогда не будут видны
основному процессу. Нужен явный мост.

`AppPreferences` (DataStore) не в зоне риска: `RealityVpnService` читает
`clientConfigFlow` один раз при старте (разовое чтение файла с диска,
DataStore-инстанс в каждом процессе свой, но файл на диске общий) — записи
в конфиг из `:reality`-процесса не делаются, постоянной кросс-процессной
синхронизации не требуется.

## Архитектура моста состояния

Однонаправленный канал `RealityVpnService (процесс :reality) → основной
процесс`, через `Messenger`/`Binder` (штатный межпроцессный RPC-механизм
Android — доставляет `Message` с `Bundle`-полезной нагрузкой между
процессами через `binder`-драйвер ядра).

Старт/стоп самого сервиса остаётся как есть, через `Intent`
(`startForegroundService`/`stopService` — уже кросс-процессный механизм ОС,
не требует изменений). Мост нужен только для потока статуса в обратную
сторону.

### Компоненты

**`RealityVpnService.kt`** — добавляется `onBind(intent): IBinder`,
отдающий `Messenger`, обёрнутый вокруг `Handler`, который принимает
регистрацию клиента (клиент передаёт свой `Messenger` для обратной связи).
Каждый существующий вызов `ProxyServiceState.setX(...)`/`addLog(...)`
внутри сервиса заменяется вызовом на новый приватный класс/объект внутри
файла (например, `RealityStateSink`), который:
1. Собирает `Message` с `what`-кодом события и `Bundle`-полезной нагрузкой
   из примитивов (см. "Протокол сообщений" ниже).
2. Рассылает всем зарегистрированным клиентам (`Messenger.send`); при
   `RemoteException`/`DeadObjectException` — снимает клиента с регистрации,
   не падает.

На регистрацию нового клиента сервис сразу шлёт `MSG_SNAPSHOT` — полное
текущее состояние, а не только будущие дельты (закрывает случай: основной
процесс был убит системой и пересоздан, пока Reality-туннель всё ещё жив).

**`RealityStateBridge.kt`** (новый файл, основной процесс) —
`ServiceConnection`, привязывается к `RealityVpnService`
(`bindService(intent, this, 0)` — без `BIND_AUTO_CREATE`, то есть не
поднимает Reality сам, только слушает, если он уже запущен кем-то другим
через штатный `Intent`-путь). Держит `Messenger` на стороне клиента,
получает сообщения и применяет их к `ProxyServiceState` в основном
процессе — теми же вызовами (`setRunning`, `addLog` и т.д.), что раньше
делал сам сервис. `LocalProxyManager` и весь остальной UI не меняются.

**Точки подключения моста:**
- Сразу после `AndroidProxyServiceLauncher.start()`, когда выбранный
  транспорт — Reality.
- Один раз при старте приложения (DI-графа) — на случай, если Reality уже
  работал в фоне до пересоздания процесса.

**`AndroidManifest.xml`** — сервису `RealityVpnService` добавляется
`android:process=":reality"`.

### Протокол сообщений (набросок)

Плоские поля, без Parcelable-обвязки в самих доменных классах — маппинг
только на границе моста:

- `MSG_SNAPSHOT` / `MSG_STATE_UPDATE` — `Bundle`: `running: Boolean`,
  `active: Int`, `total: Int` (из `ConnectionStats`), `failedMessage:
  String?` (null = `StartupResult.Success`/не задан, non-null =
  `StartupResult.Failed`), `tunnelActive: Boolean`, `connectedSince:
  Long?`, `teardownComplete: Boolean`.
- `MSG_LOG_LINE` — `Bundle`: `text: String`, `level: String` (имя
  `LogLevel`-константы). Форвардится в общий лог-фид, видимый в UI —
  подтверждено, что это нужно (полезно именно для отладки будущих
  Reality-проблем).
- `MSG_REGISTER_CLIENT` (клиент → сервис) — `Bundle` с `replyTo:
  Messenger` для обратной связи.

## Обработка ошибок

- `onServiceDisconnected` на мосту (процесс `:reality` умер неожиданно, не
  штатный teardown) → мост выставляет `ProxyServiceState.setRunning(false)`
  локально, чтобы UI не завис на «подключено», когда процесс на деле мёртв.
- `Messenger.send()` на стороне сервиса при отправке в мёртвый UI-процесс —
  `catch`, снять клиента с регистрации, продолжить работу (не ронять
  Reality-сервис из-за того, что слушать некому — в этом весь смысл
  изоляции).

## Тестирование

- Юнит-тест на маппинг «доменный объект ↔ `Bundle`»
  (`ConnectionStats`/`StartupResult`/`LogEntry` — чистые функции, туда-
  обратно, без эмулятора).
- Существующий `./gradlew :app:testDebugUnitTest` не должен сломаться.
- **Основная приёмка — живой повтор сценария, который крашил:**
  пересобрать (`./gradlew :app:assembleDebug`), поставить (`adb install
  -r`), **включить WireGuard обратно** на профиле (исходная боевая
  конфигурация, которая крашилась), подключить vk-turn, переключиться на
  Reality. Проверка через `adb logcat -b crash -b main` непрерывным
  захватом с момента `am force-stop` (см. `verify-process-alive-not-restarted`) —
  не полагаться на «процесс жив», грепать `Zygote.*signal`/`FATAL
  EXCEPTION` явно. Дополнительно стоит проверить, что лог-строки
  `Reality: ...` по-прежнему появляются в UI-логе — подтверждает, что мост
  действительно работает, а не просто краш пропал по другой причине.

## Вне рамок (сознательно не делаем)

- Изоляцию других транспортов/сервисов — сейчас проблема только у пары
  Reality+WireGuard, закладывать общий механизм преждевременно.
- Двусторонний канал команд через тот же мост — старт/стоп остаются через
  существующий `Intent`-путь, дублировать его не нужно.
- Parcelable-обвязку доменных классов (`ConnectionStats` и т.д.) — маппинг
  в `Bundle` только на границе моста, доменные классы не меняются.
