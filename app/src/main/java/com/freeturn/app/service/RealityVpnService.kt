package com.freeturn.app.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.StartupResult
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.freeturn.app.service.reality.RealityIpc
import com.freeturn.app.service.reality.RealityState
import com.freeturn.app.service.reality.realityLogBundle
import com.freeturn.app.service.reality.toBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import org.koin.android.ext.android.inject

/**
 * VpnService для TunnelTransport.REALITY - прямой VLESS+XHTTP+Reality через
 * встроенный libXray (gomobile-обёртка XTLS/libXray над xray-core), БЕЗ нашего
 * TURN-транспорта и без ядра-подпроцесса.
 *
 * Архитектурно не похож на [ProxyService]: там наш Go core живёт ОТДЕЛЬНЫМ
 * процессом (ProcessBuilder) и слушает localhost, а TUN/VpnService целиком
 * принадлежит библиотеке com.wireguard.android (GoBackend), которая туннелирует
 * В НЕГО по WireGuard-протоколу. Xray-core же встроен через JNI (gomobile bind) -
 * тот же процесс, тот же адресный space - и ждёт TUN-дескриптор НАПРЯМУЮ в своём
 * же JSON-конфиге (`env.xray.tun.fd`, см. injectTunFd) - отдельного sub-process
 * и отдельного WG-слоя тут нет и не нужно.
 */
class RealityVpnService : VpnService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var tunFd: ParcelFileDescriptor? = null
    private lateinit var serviceScope: CoroutineScope
    private lateinit var notifier: ProxyNotifier
    private val prefs: AppPreferences by inject()

    // Xray-core сам не под VpnService.protect() - его исходящие сокеты к настоящему
    // серверу обязаны идти МИМО туннеля, который он же создаёт, иначе петля (тот же
    // класс бага, что уже чинили для ядра-подпроцесса через UnixSocketProtector,
    // только тут API прямой - Go-библиотека сама зовёт этот колбэк на каждый сокет).
    private val dialerController = object : DialerController {
        override fun protectFd(fd: Long): Boolean {
            if (tornDown.get()) return false
            return try {
                protect(fd.toInt())
            } catch (e: Exception) {
                false
            }
        }
    }

    private val stateSink = RealityStateSink()

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == RealityIpc.MSG_REGISTER_CLIENT) {
            msg.replyTo?.let { stateSink.registerClient(it) }
        }
        true
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(incomingHandler).binder

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        notifier = ProxyNotifier(this)
        notifier.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ProxyActions.STOP) {
            // Систем-сервис (Vpn.java в system_server) держит собственный bindService()
            // к нам, пока VPN-сеть считается активной - это видно в dumpsys activity
            // services как AppBindRecord "...:system". Service уничтожается, только
            // когда нет НИ started-состояния, НИ активных bind'ов - stopSelf() снимает
            // только первое. Систему отпускает закрытие TUN pfd (сеть становится
            // недействительной), а не вызов onDestroy() как таковой. Раньше tunFd
            // закрывался ВНУТРИ onDestroy() - замкнутый круг (onDestroy ждёт unbind,
            // unbind ждёт закрытия fd, fd закрывается только в onDestroy). Тердаун
            // делаем тут, синхронно, до stopSelf() - тогда система реально отпускает
            // bind и Android потом штатно вызывает onDestroy() для остального.
            teardownTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        notifier.prepareConnecting()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, ProxyNotifier.NOTIF_ID_FG, notifier.build(), fgsType)
        } catch (e: Exception) {
            fail("не удалось запустить foreground-сервис: ${e.message}")
            return START_NOT_STICKY
        }

        stateSink.setRunning(true)
        acquireWakeLock()
        stateSink.addLog("Reality: запуск")
        serviceScope.launch { startXray() }
        return START_STICKY
    }

    private suspend fun startXray() {
        val cfg = prefs.clientConfigFlow.first()
        val rawJson = cfg.xrayConfig
        if (rawJson.isBlank()) {
            fail("Xray-конфиг не задан")
            return
        }

        val builder = Builder()
            .setSession("VK-TURN Reality")
            .setMtu(1420)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
        // Собственный пакет мимо своего же туннеля - иначе петля (тот же принцип,
        // что "Socks5Server не поднимаем" в ProxyService.onCreate).
        runCatching { builder.addDisallowedApplication(packageName) }

        val pfd = try {
            builder.establish() ?: throw IllegalStateException("establish() вернул null")
        } catch (e: Exception) {
            fail("TUN establish() упал: ${e.message}")
            return
        }
        tunFd = pfd

        val configWithTun = try {
            ensureTunInbound(stripDnsGeoDomains(stripExternalGeoRouting(rawJson)), pfd.fd)
        } catch (e: Exception) {
            fail("Xray-конфиг невалиден: ${e.message}")
            return
        }

        LibXray.registerDialerController(dialerController)
        runCatching { LibXray.setDNS(dialerController, "1.1.1.1:53") }

        // Забандленная версия libXray - официальный релиз v26.7.28 (см. память
        // android-reality-libxray-2026-08-10.md: `gh release download v26.7.28`), НЕ
        // случайно совпавший по декомпилированным полям коммит апстрима. Проверено
        // исходником ИМЕННО тега v26.7.28 (invoke_model.go): LibXrayInvokeRequest
        // вообще не имеет поля Env - верхнеуровневый "env" в invoke-конверте молча
        // отбрасывается json.Unmarshal (неизвестное поле), xray.tun.fd НИКОГДА не
        // попадал в окружение, AndroidTun.NewTun дефолтился на fd=0 (/dev/null) без
        // единой ошибки - отсюда "туннель поднят", но 0 байт трафика (живая проверка:
        // /proc/pid/fd показывал открытый и НЕ читаемый /dev/tun, tx_drop на
        // интерфейсе рос). Actual API v26.7.28: apiVersion 0/1, runXrayFromJson,
        // payload.configJSON - всё верно и раньше, кроме одного - env переехал в
        // xray-core САМ (infra/conf/xray.go Config.Build(), os.Setenv до сборки
        // объектного графа) и читается из КОРНЯ самого xray-конфига, не из invoke-
        // конверта (см. ensureTunInbound - кладёт "env" туда же, где "inbounds").
        val request = JSONObject().apply {
            put("apiVersion", 1)
            put("method", "runXrayFromJson")
            put("payload", JSONObject().put("configJSON", configWithTun))
        }
        val response = try {
            JSONObject(LibXray.invoke(request.toString()))
        } catch (e: Exception) {
            fail("libXray.invoke упал: ${e.message}")
            return
        }
        if (!response.optBoolean("success", false)) {
            fail("runXray: ${response.optString("error", "unknown")}")
            return
        }

        stateSink.addLog("Reality: туннель поднят")
        stateSink.setStartupResult(StartupResult.Success)
        // connectionStats.active - число активных TURN-стримов VK-ядра, у Reality
        // такого понятия нет, а LocalProxyManager решает Running/Connecting именно по
        // stats.active > 0 - без этого статус навсегда застревал бы в "Connecting"
        // даже с поднятым туннелем. 1 из 1 - сам туннель как единственный "стрим".
        stateSink.setConnectionStats(ConnectionStats(1, 1))
        stateSink.setTunnelActive(true)
        stateSink.markConnectedIfAbsent(SystemClock.elapsedRealtime())
        notifier.setStatus(getString(R.string.proxy_active), active = true)
    }

    // Обычный конфиг из v2ray/десктопного клиента заточен под socks/http-инбаунды
    // и не содержит inbound с protocol "tun" - без него сам xray-core некому отдать
    // fd, дескриптор просто повиснет неиспользованным (proxy/tun/README.md в
    // Xray-core: inbound обязателен). Дописываем такой inbound сами, если его нет -
    // чтобы родной конфиг с ноута можно было вставить без ручной правки JSON.
    private fun ensureTunInbound(rawJson: String, fd: Int): String {
        val root = JSONObject(rawJson)

        val inbounds = root.optJSONArray("inbounds") ?: org.json.JSONArray().also { root.put("inbounds", it) }
        val hasTunInbound = (0 until inbounds.length()).any {
            inbounds.optJSONObject(it)?.optString("protocol") == "tun"
        }
        if (!hasTunInbound) {
            inbounds.put(
                JSONObject().apply {
                    put("port", 0)
                    put("protocol", "tun")
                    put(
                        "settings",
                        JSONObject().apply {
                            put("mtu", 1420)
                            // Пустое name -> xray-core сам генерит имя через net.Interfaces()
                            // (infra/conf/tun.go: GetAvailableTunName), а это netlink-запрос
                            // системных интерфейсов - под Android-песочницей падает permission
                            // denied. На Android имя всё равно не используется (AndroidTun.Name()
                            // читает его прямо из уже открытого fd через ioctl TUNGETIFF) -
                            // достаточно любой непустой строки, чтобы обойти этот путь.
                            put("name", "tun0")
                        }
                    )
                }
            )
        }

        // Корневой "env" в САМОМ xray-конфиге (не в invoke-конверте - см. коммент в
        // startXray). infra/conf/xray.go: Config.Build() делает os.Setenv по каждой
        // паре из c.Env ДО сборки объектного графа - AndroidTun.NewTun успевает
        // прочитать xray.tun.fd до того, как до него доходит очередь.
        val env = root.optJSONObject("env") ?: JSONObject().also { root.put("env", it) }
        env.put("xray.tun.fd", fd.toString())

        return root.toString()
    }

    // Скрипты установки Reality на десктопе (X-UI, 233boy и т.п.) обычно добавляют
    // routing-правила вида "гео-категория IP/домена - напрямую" через geoip.dat/
    // geosite.dat (или кастомные ext:*.dat). Мобильное приложение НИКАКИХ geo-баз не
    // носит - xray-core падает на старте (common/geodata: failed to open *.dat) на
    // первом же таком правиле. Все три формы ссылки (ext:, geosite:, geoip:) бьют по
    // той же причине, так что вырезаем все. Правила, отбор в которых держится только
    // на geo-ссылках, вырезаем целиком (без другого критерия правило после вырезания
    // заматчило бы вообще всё - это не подмена поведения, а единственный безопасный
    // вариант); в остальных просто убираем geo-записи из ip/domain. Даунсайд: весь
    // geo-based роутинг (CN-байпас, ru-байпас и т.п.) отключается, трафик просто идёт
    // через Reality - для одиночного мобильного туннеля это ОК.
    private fun stripExternalGeoRouting(rawJson: String): String {
        val root = JSONObject(rawJson)
        val routing = root.optJSONObject("routing") ?: return root.toString()
        val rules = routing.optJSONArray("rules") ?: return root.toString()

        val kept = org.json.JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            for (key in listOf("ip", "domain")) {
                val arr = rule.optJSONArray(key) ?: continue
                val filtered = org.json.JSONArray()
                for (j in 0 until arr.length()) {
                    val entry = arr.optString(j)
                    if (GEO_PREFIXES.none { entry.startsWith(it) }) filtered.put(entry)
                }
                if (filtered.length() == 0) rule.remove(key) else rule.put(key, filtered)
            }
            val hasSelector = rule.keys().asSequence().any { it !in setOf("outboundTag", "type") }
            if (hasSelector) kept.put(rule)
        }
        routing.put("rules", kept)
        return root.toString()
    }

    // dns.servers[].domains использует тот же geosite:-синтаксис, что и routing.rules -
    // тот же geodata-файл, тот же крах при парсинге. Здесь правило целиком не роняем
    // (сервер без domains просто становится доп. дефолтным резолвером - не крашится),
    // достаточно вычистить geo-записи из массива.
    private fun stripDnsGeoDomains(rawJson: String): String {
        val root = JSONObject(rawJson)
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return root.toString()
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val domains = server.optJSONArray("domains") ?: continue
            val filtered = org.json.JSONArray()
            for (j in 0 until domains.length()) {
                val entry = domains.optString(j)
                if (GEO_PREFIXES.none { entry.startsWith(it) }) filtered.put(entry)
            }
            if (filtered.length() == 0) server.remove("domains") else server.put("domains", filtered)
        }
        return root.toString()
    }

    private companion object {
        val GEO_PREFIXES = listOf("ext:", "geosite:", "geoip:")
    }

    private fun fail(message: String) {
        stateSink.addLog("Reality: $message")
        stateSink.setStartupResult(StartupResult.Failed(message))
        stateSink.setRunning(false)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::RealityBgLock")
        wakeLock?.acquire()
    }

    // Идемпотентен - вызывается и из STOP-ветки onStartCommand (см. её комментарий),
    // и из onDestroy()/onRevoke() как страховка на случай, если сервис разбудило
    // что-то другое, а не наша кнопка (например, другое приложение перехватило
    // единственный VpnService-слот).
    private val tornDown = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun teardownTunnel() {
        if (!tornDown.compareAndSet(false, true)) return

        // AndroidProxyServiceLauncher.stop() безусловно шлёт STOP-экшен и сюда, даже
        // когда Reality за всё время жизни процесса вообще не запускалась (не знает,
        // какой из двух сервисов реально поднят - см. её комментарий). tunFd не
        // установлен => установить его и не успели => туннеля не было. isRunning/
        // teardownComplete в ProxyServiceState - ГЛОБАЛЬНОЕ состояние, общее с
        // ProxyService: если на этом холостом STOP всё равно дёрнуть
        // markTeardownComplete, "готово" от пустого Reality-инстанса может прилететь
        // раньше, чем у ProxyService реально дотушится WireGuard (у него своя, более
        // долгая асинхронная остановка) - LocalProxyManager.startProxy() ловит ложный
        // teardownComplete и пускает Reality establish() поверх ещё живого WG-туннеля;
        // системный revoke живого VPN-слота на середине инициализации Xray - живой
        // краш SIGSEGV в libgojni.so именно в этом направлении переключения (vk-turn
        // -> reality). Обратное reality -> vktun чисто: там холостой вызов -
        // stopService() на никогда не стартовавший ProxyService, а stopService (в
        // отличие от startService) не создаёт инстанс на неживом сервисе - второго
        // писателя в глобальное состояние там просто нет.
        if (tunFd == null) {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            return
        }

        stateSink.markTeardownStarted()
        stateSink.setRunning(false)
        stateSink.setConnectionStats(ConnectionStats.IDLE)
        stateSink.clearConnectedSince()
        stateSink.addLog("Reality: остановка")

        // tunFd закрываем ДО stopXray, не после: AndroidTun.Close() (libXray,
        // tun_android.go) - no-op, fd не трогает, им владеем мы. Если stopXray изнутри
        // (gVisor fdbased endpoint в proxy/tun/stack_gvisor.go) ждёт события на этом
        // fd, а он всё ещё открыт - готовый сценарий зависания на остановке. Закрытие
        // fd - ещё и то самое событие, что отпускает системный bind (см. STOP-ветку
        // onStartCommand), так что порядок здесь двойной страховки ради.
        runCatching { tunFd?.close() }
        tunFd = null

        // invoke() - блокирующий JNI-вызов в Go, а этот метод может выполняться на
        // главном потоке (вызов из onStartCommand/onDestroy). Синхронный вызов тут
        // подвесил бы процесс при любой задержке в coreServer.Close(). Состояние уже
        // помечено остановленным (fd закрыт выше) - ждать результат незачем, уводим
        // в отдельный поток. markTeardownComplete - только после того, как invoke
        // реально вернулся (тот же паттерн, что уже верно сделан в
        // CoreProcessController.destroyProcessAndTunnel()).
        Thread {
            val stopRequest = JSONObject().apply {
                put("apiVersion", 1)
                put("method", "stopXray")
                put("payload", JSONObject())
            }
            runCatching { LibXray.invoke(stopRequest.toString()) }
            runCatching { LibXray.resetDNS() }
            stateSink.markTeardownComplete()
        }.start()

        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownTunnel()
        serviceScope.cancel()
    }

    override fun onRevoke() {
        // Система/юзер отозвали VPN-разрешение (например, другое приложение
        // перехватило единственный слот VpnService).
        stopSelf()
        super.onRevoke()
    }
}

/**
 * Заменяет прямые вызовы ProxyServiceState внутри RealityVpnService: этот сервис
 * с этого момента живёт в отдельном процессе (:reality, см. AndroidManifest.xml),
 * у него своя JVM-копия синглтона ProxyServiceState, недоступная основному
 * процессу. Рассылает состояние подключённым клиентам (RealityStateBridge) через
 * Messenger вместо прямой записи в общий объект.
 */
private class RealityStateSink {
    private val clients = java.util.concurrent.CopyOnWriteArrayList<Messenger>()

    private var running = false
    private var active = 0
    private var total = 0
    private var failedMessage: String? = null
    private var tunnelActive = false
    private var connectedSince: Long? = null
    private var teardownComplete = true

    private fun currentState() = RealityState(
        running = running,
        active = active,
        total = total,
        failedMessage = failedMessage,
        tunnelActive = tunnelActive,
        connectedSince = connectedSince,
        teardownComplete = teardownComplete
    )

    /** Новый клиент подключился - сразу шлём полный снепшот, не только будущие изменения. */
    fun registerClient(client: Messenger) {
        clients += client
        sendTo(client, RealityIpc.MSG_STATE_UPDATE, currentState().toBundle())
    }

    fun setRunning(value: Boolean) {
        running = value
        broadcastState()
    }

    fun setStartupResult(result: StartupResult) {
        failedMessage = (result as? StartupResult.Failed)?.message
        broadcastState()
    }

    fun setConnectionStats(stats: ConnectionStats) {
        active = stats.active
        total = stats.total
        broadcastState()
    }

    fun setTunnelActive(value: Boolean) {
        tunnelActive = value
        broadcastState()
    }

    fun markConnectedIfAbsent(nowElapsed: Long) {
        if (connectedSince == null) connectedSince = nowElapsed
        broadcastState()
    }

    fun clearConnectedSince() {
        connectedSince = null
        broadcastState()
    }

    fun markTeardownStarted() {
        teardownComplete = false
        broadcastState()
    }

    fun markTeardownComplete() {
        teardownComplete = true
        broadcastState()
    }

    fun addLog(text: String) = broadcastAll(RealityIpc.MSG_LOG_LINE, realityLogBundle(text))

    private fun broadcastState() = broadcastAll(RealityIpc.MSG_STATE_UPDATE, currentState().toBundle())

    private fun broadcastAll(what: Int, bundle: android.os.Bundle) {
        val dead = mutableListOf<Messenger>()
        for (client in clients) {
            if (!sendTo(client, what, bundle)) dead += client
        }
        clients.removeAll(dead)
    }

    private fun sendTo(client: Messenger, what: Int, bundle: android.os.Bundle): Boolean = try {
        client.send(Message.obtain(null, what).apply { data = bundle })
        true
    } catch (e: RemoteException) {
        false
    }
}
