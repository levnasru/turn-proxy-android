package com.freeturn.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Поднимает/останавливает foreground-сервис туннеля. Два разных сервиса за одним
 * интерфейсом: [ProxyService] (наше TURN-ядро подпроцессом + WireGuard-библиотека
 * поверх него) для TunnelTransport.WIREGUARD/NONE, [RealityVpnService] (встроенный
 * libXray, свой VpnService) для TunnelTransport.REALITY. Выбор - по конфигу
 * активного сервера на момент нажатия, не хранится отдельно.
 */
class AndroidProxyServiceLauncher(
    private val context: Context,
    private val prefs: AppPreferences,
) : ProxyServiceLauncher {

    // runBlocking короткий: DataStore читает из уже прогретого in-memory кэша на
    // повторных чтениях (первое чтение - холодный старт процесса, тут им не является -
    // до нажатия "подключиться" экран настроек уже отрисовался с этим же значением).
    private fun targetServiceClass(): Class<*> =
        if (runBlocking { prefs.clientConfigFlow.first() }.tunnelTransport == TunnelTransport.REALITY)
            RealityVpnService::class.java
        else
            ProxyService::class.java

    override fun start() {
        val intent = Intent(context, targetServiceClass())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stop() {
        // Останавливаем оба безусловно: не знаем, какой именно был поднят в этом
        // запуске (пользователь мог сменить режим, не перезапустив сервис), а
        // stop на не запущенный сервис - no-op, не ошибка.
        //
        // ProxyService - обычный Service (WG-туннель живёт в отдельной GoBackend$
        // VpnService библиотеки com.wireguard.android) - внешний stopService() ок.
        // RealityVpnService - САМ VpnService: система держит на нём собственный
        // internal binding (см. dumpsys activity services - AppBindRecord "...:system"),
        // пока туннель активен. Внешний stopService() его не убивает - сервис и
        // туннель остаются жить, onDestroy() просто никогда не вызывается. Верный
        // путь - stopSelf() ИЗНУТРИ, через STOP-экшен (onStartCommand уже его
        // обрабатывает первым делом, до старта foreground/туннеля).
        context.stopService(Intent(context, ProxyService::class.java))
        context.startService(Intent(context, RealityVpnService::class.java).apply { action = ProxyActions.STOP })
    }

    override fun setWireGuard(enabled: Boolean) {
        // startService, а не startForegroundService: сервис уже в foreground, а из
        // фона startForegroundService на живой сервис даёт ANR-таймер без нужды.
        val intent = Intent(context, ProxyService::class.java).apply {
            action = ProxyActions.SET_WIREGUARD
            putExtra(ProxyActions.EXTRA_WG_ENABLED, enabled)
        }
        context.startService(intent)
    }
}
