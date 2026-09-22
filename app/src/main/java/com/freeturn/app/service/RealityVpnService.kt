package com.freeturn.app.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.proxy.PRIVATE_IPV4_CIDRS
import com.freeturn.app.domain.proxy.excludeLanFromAllowedIps
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
import java.net.InetAddress
import com.freeturn.app.data.config.parseBypassRules
import com.freeturn.app.data.config.ParsedBypassRules
import libXray.DialerController
import libXray.LibXray
import org.json.JSONArray
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
    @Volatile
    private var isVkXray = false

    // Xray-core сам не под VpnService.protect() - его исходящие сокеты к настоящему
    // серверу обязаны идти МИМО туннеля, который он же создаёт, иначе петля (тот же
    // класс бага, что уже чинили для ядра-подпроцесса через UnixSocketProtector,
    // только тут API прямой - Go-библиотека сама зовёт этот колбэк на каждый сокет).
    private val dialerController = object : DialerController {
        override fun protectFd(fd: Long): Boolean {
            if (tornDown.get()) return false
            if (isVkXray) {
                // В режиме VK-Xray единственный исходящий адрес - локальный 127.0.0.1:9000
                // ядра libfreeturn. VpnService.protect() привязывает сокет через SO_BINDTODEVICE /
                // fwmark к физическому сетевому интерфейсу (wlan0/rmnet), из-за чего попытка
                // соединиться с 127.0.0.1 намертво отбрасывается ядром Linux как martian packet.
                // При этом защита от петли туннеля уже обеспечена через addDisallowedApplication(packageName).
                return true
            }
            return try {
                protect(fd.toInt())
            } catch (e: Exception) {
                false
            }
        }
    }

    private val stateSink = RealityStateSink()

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            RealityIpc.MSG_REGISTER_CLIENT -> {
                msg.replyTo?.let { stateSink.registerClient(it) }
            }
            RealityIpc.MSG_UNREGISTER_CLIENT -> {
                msg.replyTo?.let { stateSink.unregisterClient(it) }
            }
        }
        true
    }

    // SERVICE_INTERFACE-бинд принадлежит системе: Vpn.java (system_server) биндится
    // именно этим интентом и держит возвращённый отсюда Callback-binder, чтобы слать
    // на него LAST_CALL_TRANSACTION -> onRevoke() при отзыве VPN-разрешения. Если
    // подменить его своим Messenger, onRevoke() перестаёт вызываться вообще -
    // "призрачное" подключение с висящим wakelock и уведомлением. Свой Messenger
    // отдаём только собственному мосту (RealityStateBridge биндится интентом без action).
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent)
        else Messenger(incomingHandler).binder

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        notifier = ProxyNotifier(this)
        notifier.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

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
            //
            // Белт-энд-брейсес против ForegroundServiceDidNotStartInTimeException (см.
            // комментарий в AndroidProxyServiceLauncher.stop()): если этот STOP всё же
            // достался процессу параллельно со "свежим" connect (тот же ServiceRecord),
            // startForegroundService()-промис должен быть закрыт ПЕРЕД stopSelf() -
            // иначе ОС считает его невыполненным, даже когда сервис и так тушится сам.
            try {
                ServiceCompat.startForeground(this, ProxyNotifier.NOTIF_ID_FG, notifier.build(), fgsType)
            } catch (e: Exception) {
                // Не критично - teardown/stopSelf ниже всё равно снимут сервис.
            }
            teardownTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        notifier.prepareConnecting()
        try {
            ServiceCompat.startForeground(this, ProxyNotifier.NOTIF_ID_FG, notifier.build(), fgsType)
        } catch (e: Exception) {
            fail("не удалось запустить foreground-сервис: ${e.message}")
            return START_NOT_STICKY
        }

        // Конфиг приходит интентом от AndroidProxyServiceLauncher, не перечитывается
        // из DataStore здесь: :reality - изолированный процесс, который Android может
        // держать живым между сессиями (см. EXTRA_XRAY_CONFIG) - перечитывание из
        // AppPreferences в этом процессе видело бы конфиг только на момент ПЕРВОГО
        // старта процесса, не текущий.
        val xrayConfigOverride = intent?.getStringExtra(ProxyActions.EXTRA_XRAY_CONFIG)
        val bypassRulesOverride = intent?.getStringExtra(ProxyActions.EXTRA_BYPASS_RULES)
        isVkXray = intent?.getBooleanExtra(ProxyActions.EXTRA_IS_VK_XRAY, false) ?: false
        stateSink.setRunning(true)
        acquireWakeLock()
        stateSink.addLog(if (isVkXray) "VK-Xray: запуск туннеля" else "Reality: запуск")
        serviceScope.launch { startXray(xrayConfigOverride, bypassRulesOverride) }
        return START_STICKY
    }

    private suspend fun startXray(xrayConfigOverride: String?, bypassRulesOverride: String? = null) {
        val rawJson = xrayConfigOverride ?: prefs.clientConfigFlow.first().xrayConfig
        if (rawJson.isBlank()) {
            fail("Xray-конфиг не задан")
            return
        }
        val rawBypass = bypassRulesOverride ?: prefs.clientConfigFlow.first().bypassRules
        val parsedBypass = parseBypassRules(rawBypass)

        val builder = Builder()
            .setSession(if (isVkXray) "VK-TURN Xray" else "VK-TURN Reality")
            .setMtu(ClientConfig.WG_MTU)
            .addAddress("172.19.0.1", 30)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
        // RFC1918/link-local/loopback (принтер/NAS/роутер/KDE Connect/Immich по
        // локальному IP) должны остаться доступны поверх поднятого туннеля.
        // Раньше был голый addRoute(0.0.0.0, 0) без единого исключения. Комплемент
        // из CIDR (addRoute на всё, КРОМЕ приватных диапазонов) недостаточен сам по
        // себе - живой разбор `ip rule`/`ip route` на SM_S938B (Android 16)
        // показал, что промах в таблице маршрутов VPN проваливается в безусловное
        // "unreachable", а не откатывается на Wi-Fi. Builder.excludeRoute() (API 33+)
        // - единственный API, который явно помечает диапазон как невладеемый VPN и
        // получает откат на другую сеть; на нём и держим полный 0.0.0.0/0.
        val allExcludedCidrs = (PRIVATE_IPV4_CIDRS + parsedBypass.cidrs).distinct()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            allExcludedCidrs.forEach { cidr ->
                val (addr, prefix) = cidr.split("/")
                runCatching { builder.excludeRoute(IpPrefix(InetAddress.getByName(addr), prefix.toInt())) }
            }
        } else {
            // До API 33 excludeRoute() нет - комплемент-список не даёт гарантии
            // отвала на Wi-Fi при промахе, но не хуже прежнего голого 0.0.0.0/0.
            excludeLanFromAllowedIps("0.0.0.0/0", customExcludedCidrs = parsedBypass.cidrs).split(",").forEach { cidr ->
                val (addr, prefix) = cidr.trim().split("/")
                builder.addRoute(addr, prefix.toInt())
            }
        }
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
            val stripped = stripDnsGeoDomains(stripExternalGeoRouting(rawJson))
            val withBypass = injectBypassRouting(stripped, parsedBypass)
            ensureTunInbound(withBypass, pfd.fd)
        } catch (e: Exception) {
            fail("Xray-конфиг невалиден: ${e.message}")
            return
        }

        LibXray.registerDialerController(dialerController)
        if (!isVkXray) {
            runCatching { LibXray.setDNS(dialerController, "1.1.1.1:53") }
        }

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
            put("apiVersion", LibXray.LibXrayAPIVersion)
            put("method", "runXray")
            put("payload", JSONObject().put("xrayJson", configWithTun))
        }
        val response = try {
            JSONObject(LibXray.invoke(request.toString()))
        } catch (e: Exception) {
            android.util.Log.e("RealityVpnService", "libXray.invoke упал", e)
            fail("libXray.invoke упал: ${e.message}")
            return
        }
        if (!response.optBoolean("success", false)) {
            val err = response.optString("error", "unknown")
            android.util.Log.e("RealityVpnService", "runXray failed: $err")
            fail("runXray: $err")
            return
        }

        android.util.Log.i("RealityVpnService", "runXray succeeded, isVkXray=$isVkXray")
        stateSink.addLog(if (isVkXray) "VK-Xray: туннель поднят" else "Reality: туннель поднят")
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
                            put("mtu", ClientConfig.WG_MTU)
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
        teardownTunnel()
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

        // AndroidProxyServiceLauncher.stop() шлёт STOP-экшен сюда только когда живьём
        // подтвердил через ActivityManager, что Reality реально запущен (см. её
        // isRealityServiceRunning()) - но это не отменяет старую защиту ниже: tunFd не
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
        //
        // Мост (RealityStateBridge) может к этому моменту быть уже отвязан от
        // основного процесса (AndroidProxyServiceLauncher.stop() зовёт unbind() сразу
        // после отправки STOP-интента, не дожидаясь реального teardown) - это ок:
        // stateSink не чистит clients по локальному состоянию бинда, только когда
        // send() реально падает, а Messenger - независимый канал в тот же живой
        // процесс, unbind() на него не влияет.
        Thread {
            val stopRequest = JSONObject().apply {
                put("apiVersion", LibXray.LibXrayAPIVersion)
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
        teardownTunnel()
        stopSelf()
        super.onRevoke()
    }
}

// Внедряет правила обхода (bypass/whitelist): трафик к указанным доменам и IP
// направляется в direct-аутбаунд (freedom) в обход Reality-прокси.
internal fun injectBypassRouting(rawJson: String, bypass: ParsedBypassRules): String {
    if (bypass.domains.isEmpty() && bypass.cidrs.isEmpty()) {
        return rawJson
    }
    val root = JSONObject(rawJson)

    val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
    var directTag = "direct"
    var foundFreedom = false
    for (i in 0 until outbounds.length()) {
        val o = outbounds.optJSONObject(i) ?: continue
        val protocol = o.optString("protocol")
        val tag = o.optString("tag")
        if (protocol == "freedom") {
            foundFreedom = true
            directTag = if (tag.isNotBlank()) tag else "direct"
            if (tag.isBlank()) o.put("tag", "direct")
            break
        }
    }
    if (!foundFreedom) {
        outbounds.put(JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        })
        directTag = "direct"
    }

    val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
    val rules = routing.optJSONArray("rules") ?: JSONArray().also { routing.put("rules", it) }

    val bypassRule = JSONObject().apply {
        put("type", "field")
        put("outboundTag", directTag)
    }

    if (bypass.domains.isNotEmpty()) {
        val domainArr = JSONArray()
        for (d in bypass.domains) {
            val clean = d.removePrefix("*.").trim()
            if (clean.isNotBlank()) {
                domainArr.put("domain:$clean")
            }
        }
        if (domainArr.length() > 0) {
            bypassRule.put("domain", domainArr)
        }
    }

    if (bypass.cidrs.isNotEmpty()) {
        val ipArr = JSONArray()
        for (cidr in bypass.cidrs) {
            ipArr.put(cidr)
        }
        bypassRule.put("ip", ipArr)
    }

    val newRules = JSONArray()
    newRules.put(bypassRule)
    for (i in 0 until rules.length()) {
        newRules.put(rules.get(i))
    }
    routing.put("rules", newRules)

    return root.toString()
}

/**
 * Заменяет прямые вызовы ProxyServiceState внутри RealityVpnService: этот сервис
 * с этого момента живёт в отдельном процессе (:reality, см. AndroidManifest.xml),
 * у него своя JVM-копия синглтона ProxyServiceState, недоступная основному
 * процессу. Рассылает состояние подключённым клиентам (RealityStateBridge) через
 * Messenger вместо прямой записи в общий объект.
 *
 * Публичные методы синхронизированы (@Synchronized = synchronized(this)) - поля
 * состояния пишутся с трёх разных потоков (главный: onStartCommand/STOP-ветка;
 * Dispatchers.IO: startXray()/fail(); отдельный Thread: async-хвост
 * teardownTunnel()), а currentState()/registerClient() читают их же для снепшота
 * новому клиенту - без синхронизации это гонка данных без happens-before.
 */
private class RealityStateSink {
    private val clients = java.util.concurrent.ConcurrentHashMap<IBinder, Messenger>()
    private val deathRecipients = java.util.concurrent.ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()
    private val pendingLogs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private var running = false
    private var active = 0
    private var total = 0
    private var failedMessage: String? = null
    private var hasStartupResult = false
    private var tunnelActive = false
    private var connectedSince: Long? = null
    private var teardownComplete = true

    @Synchronized
    private fun currentState() = RealityState(
        running = running,
        active = active,
        total = total,
        failedMessage = failedMessage,
        hasStartupResult = hasStartupResult,
        tunnelActive = tunnelActive,
        connectedSince = connectedSince,
        teardownComplete = teardownComplete
    )

    /** Новый клиент подключился - сразу шлём полный снепшот, не только будущие изменения. */
    @Synchronized
    fun registerClient(client: Messenger) {
        val binder = client.binder ?: return
        unregister(binder)

        val recipient = IBinder.DeathRecipient {
            unregister(binder)
        }
        try {
            binder.linkToDeath(recipient, 0)
            deathRecipients[binder] = recipient
        } catch (e: RemoteException) {
            return
        }

        clients[binder] = client
        sendTo(client, RealityIpc.MSG_STATE_UPDATE, currentState().toBundle())
        for (text in pendingLogs) sendTo(client, RealityIpc.MSG_LOG_LINE, realityLogBundle(text))
    }

    @Synchronized
    fun unregisterClient(client: Messenger) {
        val binder = client.binder ?: return
        unregister(binder)
    }

    @Synchronized
    fun unregister(binder: IBinder) {
        clients.remove(binder)
        deathRecipients.remove(binder)?.let { recipient ->
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
    }

    @Synchronized
    fun setRunning(value: Boolean) {
        running = value
        broadcastState()
    }

    @Synchronized
    fun setStartupResult(result: StartupResult) {
        failedMessage = (result as? StartupResult.Failed)?.message
        hasStartupResult = true
        broadcastState()
    }

    @Synchronized
    fun setConnectionStats(stats: ConnectionStats) {
        active = stats.active
        total = stats.total
        broadcastState()
    }

    @Synchronized
    fun setTunnelActive(value: Boolean) {
        tunnelActive = value
        broadcastState()
    }

    @Synchronized
    fun markConnectedIfAbsent(nowElapsed: Long) {
        if (connectedSince == null) connectedSince = nowElapsed
        broadcastState()
    }

    @Synchronized
    fun clearConnectedSince() {
        connectedSince = null
        broadcastState()
    }

    @Synchronized
    fun markTeardownStarted() {
        teardownComplete = false
        broadcastState()
    }

    @Synchronized
    fun markTeardownComplete() {
        teardownComplete = true
        broadcastState()
    }

    @Synchronized
    fun addLog(text: String) {
        pendingLogs += text
        while (pendingLogs.size > 50) pendingLogs.removeAt(0)
        broadcastAll(RealityIpc.MSG_LOG_LINE, realityLogBundle(text))
    }

    private fun broadcastState() = broadcastAll(RealityIpc.MSG_STATE_UPDATE, currentState().toBundle())

    private fun broadcastAll(what: Int, bundle: android.os.Bundle) {
        val dead = mutableListOf<IBinder>()
        for ((binder, client) in clients) {
            if (!sendTo(client, what, bundle)) dead += binder
        }
        for (binder in dead) {
            unregister(binder)
        }
    }

    private fun sendTo(client: Messenger, what: Int, bundle: android.os.Bundle): Boolean = try {
        client.send(Message.obtain(null, what).apply { data = bundle })
        true
    } catch (e: RemoteException) {
        false
    }
}
