package com.freeturn.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.CoreArgs
import com.freeturn.app.domain.CaptchaSession
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.proxy.CoreConnectionTracker
import com.freeturn.app.domain.proxy.CoreLogEvent
import com.freeturn.app.domain.proxy.CoreLogParser
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.proxy.MAX_PROXY_RESTARTS
import com.freeturn.app.domain.proxy.ProxyServiceState
import com.freeturn.app.domain.proxy.WireGuardTunnelManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CoreProcessController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val notifier: ProxyNotifier,
    private val carrierDns: () -> String,
    private val onStopRequested: () -> Unit,
    private val protectPath: String? = null,
) {
    companion object {
        // Даём TURN-туннелю "устаканиться" перед поднятием WireGuard поверх него.
        private const val WIREGUARD_START_DELAY_MS = 2_000L
        // Ядро на SIGTERM закрывает стримы и ждёт до 5 с (cmd/client/main.go). Даём чуть больше.
        private const val GRACEFUL_STOP_TIMEOUT_MS = 6_000L
        // NetworkHandoverMonitor шлёт ДВА onNetworkHandover() на одну реальную смену сети
        // (физический монитор + default-route монитор, каждый со своим 2с debounce,
        // независимо сработавшие) - живой замер 2026-08-16 (adb wifi toggle x3, 40-стримовый
        // профиль) показал их ~2.2с друг за другом на каждый тоггл. Без кулдауна второй
        // handover рвёт стримы, которые первый только-только начал поднимать (реконнект
        // всех N стримов идёт по ~200-300мс на стрим, т.е. секунды) - на фликающей сети это
        // не даёт SESSION вообще стабилизироваться (WG/статус показывают "подключено" сразу
        // после первого стрима, но остальные N-1 не успевают до следующего обрыва). Кулдаун
        // поглощает двойной триггер одной транзакции и не мешает разнесённым по времени
        // реальным сменам сети (секунды-минуты в норме).
        // ponytail: фиксированное значение, не пропорционально N стримов профиля - для
        // профилей сильно больше 40 стримов реконнект может не уложиться в окно; поднять
        // константу, если появятся такие профили и живой замер это подтвердит.
        private const val HANDOVER_COOLDOWN_MS = 5_000L
    }

    private val wireGuard = WireGuardTunnelManager(context)
    private val handler = Handler(Looper.getMainLooper())

    private val process = AtomicReference<Process?>(null)
    private val userStopped = AtomicBoolean(false)
    // Намерение пользователя по WG в этой сессии. WG расцеплен с ядром: его можно
    // погасить и поднять кнопкой, не пересоздавая TURN-сессию (та стоит ~10 минут
    // остывания аллокаций). По умолчанию поднимаем сразу после подключения.
    private val wgWanted = AtomicBoolean(true)
    // true между стартом graceful-kill (смена сети) и обработкой exitCode в
    // finally - ядро после SIGTERM выходит кодом 0 (штатное завершение), а finally без
    // этого флага принимает 0 за "сессия закончилась" и останавливает сервис вместо рестарта.
    private val restartInFlight = AtomicBoolean(false)
    private val restartCount = AtomicInteger(0)
    // elapsedRealtime до которого игнорируем повторные onNetworkHandover() - см.
    // HANDOVER_COOLDOWN_MS.
    private val handoverCooldownUntil = AtomicLong(0)
    // Single-flight: двойной старт (tile+UI/watchdog) затёр бы первый процесс -> зомби.
    private val startInFlight = AtomicBoolean(false)

    val isRunning: Boolean get() = process.get() != null
    val isUserStopped: Boolean get() = userStopped.get()

    fun start() {
        userStopped.set(false)
        restartCount.set(0)
        wgWanted.set(true)
        scope.launch { startBinaryProcess() }
    }

    /**
     * Кнопка WG. Гасит/поднимает туннель поверх уже работающего ядра - смена
     * внешнего VPN больше не стоит пересоздания TURN-сессии.
     */
    fun setWireGuardEnabled(enabled: Boolean) {
        wgWanted.set(enabled)
        scope.launch {
            if (enabled) {
                if (process.get() == null) {
                    ProxyServiceState.addLog("WireGuard: туннель не запущен, включать нечего")
                    return@launch
                }
                startWireGuard(prefs.clientConfigFlow.first())
            } else {
                wireGuard.stop()
                ProxyServiceState.setWireGuardUp(false)
                notifier.setStatus(context.getString(R.string.proxy_active), active = true)
            }
        }
    }

    /** Поднять WG под текущий конфиг. Ошибку логируем, ядро не роняем. */
    private suspend fun startWireGuard(cfg: com.freeturn.app.data.config.ClientConfig): Boolean {
        if (!cfg.wireGuardActive || !wgWanted.get()) return false
        return try {
            wireGuard.startAfterProxyReady(cfg)
            ProxyServiceState.setWireGuardUp(true)
            notifier.setStatus(context.getString(R.string.tunnel_active), active = true)
            true
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            ProxyServiceState.addLog("WireGuard: ошибка запуска - $message")
            ProxyServiceState.setWireGuardUp(false)
            notifier.setStatus(context.getString(R.string.notif_proxy_wireguard_error))
            false
        }
    }

    fun onNetworkHandover() {
        val proc = process.get()
        if (userStopped.get() || proc == null) return
        val now = SystemClock.elapsedRealtime()
        if (now < handoverCooldownUntil.get()) {
            ProxyServiceState.addLog("Смена сети - повтор в кулдауне, игнорирую")
            return
        }
        handoverCooldownUntil.set(now + HANDOVER_COOLDOWN_MS)
        ProxyServiceState.addLog("Смена сети - переподключение")
        notifier.setStatus(context.getString(R.string.notif_proxy_network_change))
        restartCount.set(0)
        restartInFlight.set(true)
        // stopGracefully блокирует до GRACEFUL_STOP_TIMEOUT_MS - на фоновом потоке,
        // иначе завис бы колбэк NetworkHandoverMonitor. Без этого - SIGKILL,
        // ядро не успевает отдать TURN-аллокации (Refresh lifetime=0).
        Thread { proc.stopGracefully(GRACEFUL_STOP_TIMEOUT_MS) }.start()
    }

    fun beginShutdown() {
        userStopped.set(true)
        handler.removeCallbacksAndMessages(null)
    }

    fun destroyProcessAndTunnel() {
        val proc = process.get()
        val wg = wireGuard
        Thread {
            try {
                // SIGTERM, не SIGKILL: ядро успевает отдать TURN-аллокации релею
                // (Refresh lifetime=0), иначе они висят весь TTL и следующий старт
                // упирается в квоту.
                proc?.stopGracefully(GRACEFUL_STOP_TIMEOUT_MS)
                runBlocking { wg.stop() }
            } finally {
                ProxyServiceState.markTeardownComplete()
            }
        }.start()
    }

    private suspend fun startBinaryProcess() {
        if (userStopped.get()) return
        if (!startInFlight.compareAndSet(false, true)) return
        try {
            runProcessSession()
        } finally {
            startInFlight.set(false)
        }
    }

    private suspend fun runProcessSession() {
        if (userStopped.get()) return

        val cfg = prefs.clientConfigFlow.first()
        ProxyServiceState.setLogsEnabled(cfg.logsEnabled)
        val srv = prefs.serverOptsFlow.first()
        val privacy = prefs.privacyModeFlow.first()

        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val executableFile = libDir.listFiles { f ->
            f.name.startsWith("libfreeturn") && f.name.endsWith(".so")
        }?.maxByOrNull { it.name }

        if (executableFile == null) {
            ProxyServiceState.addLog(
                "Ядро libfreeturn*.so не найдено в ${libDir.path}. " +
                "Положите бинарник в jniLibs/arm64-v8a/ (имя начинается с lib и оканчивается на .so)."
            )
            ProxyServiceState.setStartupResult(StartupResult.Failed("core binary not found"))
            ProxyServiceState.setRunning(false)
            onStopRequested()
            return
        }
        val executable = executableFile.absolutePath

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            cmdArgs.add(executable)
            cmdArgs.addAll(parts.drop(1))
        } else {
            cmdArgs.add(executable)
            val carrierDnsValue = if (cfg.useCarrierDns) carrierDns() else null
            cmdArgs.addAll(CoreArgs.client(cfg, srv, carrierDnsValue, prefs.ownClientId(), protectPath))
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        var startupEmitted = false
        var startupFailed = false
        var captchaSessionCounter = 0L

        val tracker = CoreConnectionTracker(
            udpTotal = if (cfg.isRawMode) 0 else if (cfg.threads > 0) cfg.threads else 1,
            tcpMode = cfg.tcpForward
        )

        fun publishStats() {
            ProxyServiceState.setConnectionStats(ConnectionStats(tracker.active, tracker.total))
            notifier.refreshStats()
        }
        publishStats()
        try {
            ProxyServiceState.addLog("Команда: ${CoreArgs.redactForLog(cmdArgs, privacy)}")

            val proc = withContext(Dispatchers.IO) {
                // Легаси: сносим осиротевшие профили захвата (сам механизм захвата удалён).
                context.filesDir.listFiles { f -> f.name.startsWith("vk_profile") }?.forEach { it.delete() }
                val pb = ProcessBuilder(cmdArgs).redirectErrorStream(true)
                // CWD подменяем на writeable dir для логов кэша tls-client и т.п.
                pb.directory(context.filesDir)
                pb.start()
            }
            process.set(proc)
            // Stop в окне старта: destroyProcessAndTunnel видел ещё null - убиваем сами.
            // Уже на Dispatchers.IO (см. withContext выше), блокирующий stopGracefully не страшен.
            if (userStopped.get()) {
                proc.stopGracefully(GRACEFUL_STOP_TIMEOUT_MS)
            }

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (true) {
                    line = try {
                        reader.readLine()
                    } catch (e: java.io.IOException) {
                        // При остановке процесса pipe закрывается, readLine() бросает IOException.
                        val msg = e.message.orEmpty()
                        val benign = userStopped.get() ||
                            msg.contains("interrupted by close", ignoreCase = true) ||
                            msg.contains("Stream closed", ignoreCase = true) ||
                            msg.contains("Bad file descriptor", ignoreCase = true)
                        if (!benign) {
                            ProxyServiceState.addLog("Чтение лога ядра прервано: ${e.message}")
                        }
                        null
                    }
                    if (line == null) break
                    val l = line
                    ProxyServiceState.addLog(l)

                    val events = CoreLogParser.parse(l)
                    var statsChanged = false
                    for (event in events) when (event) {
                        is CoreLogEvent.CaptchaUrl -> {
                            captchaSessionCounter += 1
                            ProxyServiceState.setCaptchaSession(
                                CaptchaSession(event.url, captchaSessionCounter)
                            )
                            notifier.showCaptcha()
                        }
                        CoreLogEvent.CaptchaResolved -> {
                            if (ProxyServiceState.captchaSession.value != null) {
                                ProxyServiceState.setCaptchaSession(null)
                                notifier.cancelCaptcha()
                            }
                        }
                        else -> if (tracker.apply(event)) statsChanged = true
                    }
                    if (statsChanged) publishStats()

                    if (!startupEmitted) {
                        val hasFatal = events.any { it is CoreLogEvent.FatalStartup }
                        val hasConnection = tracker.hasConnection
                        when {
                            hasFatal -> {
                                ProxyServiceState.setStartupResult(StartupResult.Failed(l))
                                notifier.setStatus(context.getString(R.string.notif_proxy_connect_error))
                                startupFailed = true
                                startupEmitted = true
                            }
                            hasConnection -> {
                                // Туннель поднят - это и есть успех старта. WG идёт следом
                                // и отдельно: его провал больше не роняет ядро (иначе одна
                                // кривая WG-настройка стоила бы 10 минут остывания квоты).
                                ProxyServiceState.setStartupResult(StartupResult.Success)
                                ProxyServiceState.markConnectedIfAbsent(SystemClock.elapsedRealtime())
                                notifier.setStatus(context.getString(R.string.proxy_active), active = true)
                                if (cfg.wireGuardActive && wgWanted.get()) {
                                    ProxyServiceState.addLog(
                                        "WireGuard: подъём через ${WIREGUARD_START_DELAY_MS} мс после старта TURN-туннеля"
                                    )
                                    delay(WIREGUARD_START_DELAY_MS)
                                    if (userStopped.get() || process.get() !== proc) {
                                        ProxyServiceState.addLog(
                                            "WireGuard: старт отменён, прокси останавливается"
                                        )
                                        break
                                    }
                                    startWireGuard(cfg)
                                }
                                startupEmitted = true
                            }
                        }
                    }

                    // QuotaError больше не убивает процесс: ядро (internal/proxy/udprelay)
                    // уже переживает нехватку слотов само - каждый стрим ретраит
                    // независимо с бэкоффом 2с, не трогая остальные. Полный рестарт тут
                    // раньше был нужен, чтобы обойти утечку аллокаций от SIGKILL
                    // (destroyForcibly, ~10 мин TTL) - тот баг фиксирован stopGracefully
                    // (2026-08-05), и с ним рестарт на квоту только рвёт уже рабочие
                    // стримы вместо того чтобы дать недостающим дотянуться в фоне.
                    if (events.any { it is CoreLogEvent.QuotaError }) {
                        ProxyServiceState.addLog("Квота на части стримов - ядро само доберёт недостающие")
                    }
                }
            }

            exitCode = if (withContext(Dispatchers.IO) {
                    proc.waitForCompat(5, TimeUnit.MINUTES)
                }) proc.exitValue() else -1
            ProxyServiceState.addLog("Процесс остановлен (код $exitCode)")
            if (!startupEmitted) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(
                    "Процесс завершился без вывода (код: $exitCode)"))
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Исключения при пользовательской остановке - следствие закрытия пайпов.
            if (userStopped.get()) {
                startupFailed = false
            } else {
                val msg = e.message ?: ""
                if (msg.contains("error=13") || msg.contains("Permission denied")) {
                    ProxyServiceState.addLog("Ошибка: устройство блокирует запуск файлов из внутреннего хранилища (SELinux/noexec). Используйте встроенное ядро.")
                    ProxyServiceState.setStartupResult(StartupResult.Failed(msg))
                    startupFailed = true
                } else {
                    ProxyServiceState.addLog("Ошибка: ${e.message}")
                }
            }
        } finally {
            ProxyServiceState.setCaptchaSession(null)
            notifier.cancelCaptcha()
            // Читаем состояние, а не локальный флаг: WG могли поднять кнопкой уже
            // после старта сессии.
            if (ProxyServiceState.wireGuardUp.value) {
                wireGuard.stop()
                ProxyServiceState.setWireGuardUp(false)
            }
            ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
            process.set(null)
            when {
                userStopped.get() -> {
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                restartInFlight.compareAndSet(true, false) -> {
                    ProxyServiceState.addLog("Сессия пересоздана после смены сети - переподключение")
                    scheduleWatchdogRestart()
                }
                startupFailed -> {
                    ProxyServiceState.addLog("Ошибка при запуске, watchdog не активирован")
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                exitCode == 0 -> {
                    val uptime = System.currentTimeMillis() - startedAt
                    if (uptime < 5_000L) {
                        ProxyServiceState.addLog("Быстрый выход (${uptime} мс) - проверьте ссылку и настройки")
                    } else {
                        ProxyServiceState.addLog("Сессия завершена")
                    }
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                else -> scheduleWatchdogRestart()
            }
        }
    }

    private fun scheduleWatchdogRestart() {
        val count = restartCount.incrementAndGet()
        if (count > MAX_PROXY_RESTARTS) {
            ProxyServiceState.addLog("Watchdog: превышен лимит попыток ($MAX_PROXY_RESTARTS), остановка")
            ProxyServiceState.setRunning(false)
            ProxyServiceState.emitFailed()
            onStopRequested()
            return
        }
        val baseDelay = minOf(1_000L * count, 30_000L)
        val jitter = Random.nextLong(0, 500)
        val delayMs = baseDelay + jitter
        ProxyServiceState.addLog("Watchdog: перезапуск через ${delayMs} мс (попытка $count/$MAX_PROXY_RESTARTS)")
        notifier.setStatus(context.getString(R.string.notif_proxy_reconnecting, count, MAX_PROXY_RESTARTS))
        handler.postDelayed({
            if (!userStopped.get()) scope.launch { startBinaryProcess() }
        }, delayMs)
    }

}

