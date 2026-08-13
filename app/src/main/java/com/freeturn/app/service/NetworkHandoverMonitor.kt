package com.freeturn.app.service
import com.freeturn.app.domain.proxy.ProxyServiceState

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Следит за сменой ФИЗИЧЕСКОЙ сети (Wi-Fi <-> LTE и т.п.) и дёргает [onHandover].
 * VPN-интерфейсы отфильтрованы в физическом мониторе: иначе старт WireGuard
 * выглядел бы как смена сети и уводил прокси в бесконечный рестарт.
 *
 * Отдельно следит за default-маршрутом ([registerDefaultNetworkCallback]) - той
 * сетью, которую реально использует НЕсвязанный сокет нашего процесса (то есть
 * DTLS/UDP-сессия ядра к TURN-relay). Физический монитор ЭТУ смену не видит:
 * когда СТОРОННЕЕ VPN-приложение забирает/отдаёт системный VPN-слот, физическая
 * сеть (тот же Wi-Fi/интерфейс) не меняется вообще - меняется только маршрут по
 * умолчанию для приложений её не исключивших. Без этого второго монитора ядро
 * после переключения на чужой VPN и обратно остаётся с мёртвой UDP-сессией,
 * которую ничто не толкает на переподключение (WG-туннель к 127.0.0.1 поднимается
 * штатно - это чисто локальный хендшейк, трафика за ним просто нет).
 */
class NetworkHandoverMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onHandover: () -> Unit,
) {
    companion object {
        // Игнорируем сетевые события первые секунды после регистрации - иначе
        // initial onAvailable/onCapabilitiesChanged триггерят ложный рестарт.
        private const val WARMUP_MS = 3_000L
    }

    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var debounceJob: Job? = null
    @Volatile private var lastKey: String? = null
    @Volatile private var defaultDebounceJob: Job? = null
    @Volatile private var lastDefaultNetwork: Network? = null

    fun register() {
        val cm = cm
        val registeredAt = SystemClock.elapsedRealtime()
        lastKey = physicalNetworkKey(cm)

        fun schedule(reason: String) {
            if (SystemClock.elapsedRealtime() - registeredAt < WARMUP_MS) return
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(2_000)
                val oldKey = lastKey
                val newKey = physicalNetworkKey(cm)
                // Ключ тот же - ожидаемый no-op. onCapabilitiesChanged сыплет
                // десятки раз/мин (сигнал, link speed, валидация инета), не логаем.
                if (oldKey == newKey) return@launch
                lastKey = newKey
                if (newKey == null) {
                    ProxyServiceState.addLog("Сеть: физическая сеть недоступна ($reason)")
                    return@launch
                }
                onHandover()
            }
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    ProxyServiceState.addLog("Сеть: VPN-событие проигнорировано")
                    return
                }
                schedule("available")
            }

            override fun onLost(network: Network) {
                schedule("lost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    return
                }
                schedule("capabilities")
            }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, cb)

        lastDefaultNetwork = cm.activeNetwork
        fun scheduleDefaultCheck(network: Network?, reason: String) {
            if (SystemClock.elapsedRealtime() - registeredAt < WARMUP_MS) return
            
            // Фильтруем сам VPN из default-маршрута
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) {
                ProxyServiceState.addLog("Сеть: маршрут по умолчанию (VPN) проигнорирован")
                return
            }

            defaultDebounceJob?.cancel()
            defaultDebounceJob = scope.launch {
                delay(2_000)
                // onLost даёт null - если следом быстро придёт onAvailable с новой сетью
                // (обычный переход между VPN), он отменит эту job и запустит свою -
                // итоговый рестарт будет один на реальный переход, не два подряд.
                if (network == lastDefaultNetwork) return@launch
                lastDefaultNetwork = network
                if (network == null) {
                    ProxyServiceState.addLog("Сеть: маршрут по умолчанию недоступен ($reason)")
                    return@launch
                }
                ProxyServiceState.addLog("Сеть: маршрут по умолчанию сменился ($reason)")
                onHandover()
            }
        }
        val defaultCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleDefaultCheck(network, "default-available")
            override fun onLost(network: Network) = scheduleDefaultCheck(null, "default-lost")
        }
        defaultCallback = defaultCb
        cm.registerDefaultNetworkCallback(defaultCb)
    }

    fun unregister() {
        callback?.let { cb ->
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        callback = null
        defaultCallback?.let { cb ->
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        defaultCallback = null
    }

    /**
     * DNS активной сети (оператор/Wi-Fi) для флага `-dns-servers` ядра.
     * Пусто, если сеть недоступна или у linkProperties нет DNS (норма на эмуляторе).
     */
    fun activeDnsServers(): String = try {
        val net = cm.activeNetwork ?: return ""
        val lp = cm.getLinkProperties(net) ?: return ""
        lp.dnsServers
            .mapNotNull { it.hostAddress }
            .filter { it.isNotBlank() }
            .joinToString(",")
    } catch (_: Exception) {
        ""
    }

    /**
     * Ключ ОДНОЙ приоритетной физсети (транспорт + iface). Берём приоритетную, а не
     * весь allNetworks: при активном Wi-Fi cellular флапает в фоне, набор прыгал бы ->
     * ложная "смена сети". link-адреса не в ключе - ротация IPv6/DHCP идёт на той же
     * сети; реальный хендовер меняет транспорт/iface.
     */
    private fun physicalNetworkKey(cm: ConnectivityManager): String? {
        // allNetworks deprecated с API 31, но это единственный синхронный способ снять
        // полный снимок текущих сетей внутри колбэка. Подавляем осознанно.
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return@mapNotNull null
            }
            val (priority, transport) = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0 to "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1 to "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2 to "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3 to "bluetooth"
                else -> return@mapNotNull null
            }
            val iface = cm.getLinkProperties(network)?.interfaceName.orEmpty()
            // tie-break по iface - детерминированный выбор при равном приоритете.
            Triple(priority, iface, "$transport|$iface")
        }.minWithOrNull(compareBy({ it.first }, { it.second }))?.third
    }
}
