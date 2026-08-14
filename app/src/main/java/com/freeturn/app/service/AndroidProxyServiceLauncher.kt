package com.freeturn.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.service.reality.RealityStateBridge
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
    private val realityStateBridge: RealityStateBridge,
) : ProxyServiceLauncher {

    private var lastStartedClass: Class<*>? = null

    // runBlocking короткий: DataStore читает из уже прогретого in-memory кэша на
    // повторных чтениях (первое чтение - холодный старт процесса, тут им не является -
    // до нажатия "подключиться" экран настроек уже отрисовался с этим же значением).
    private fun targetServiceClass(): Class<*> =
        if (runBlocking { prefs.clientConfigFlow.first() }.tunnelTransport == TunnelTransport.REALITY)
            RealityVpnService::class.java
        else
            ProxyService::class.java

    override fun start() {
        val targetClass = targetServiceClass()
        lastStartedClass = targetClass
        val intent = Intent(context, targetClass)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        if (targetClass == RealityVpnService::class.java) {
            realityStateBridge.bind()
        }
    }

    override fun stop() {
        // Останавливаем ProxyService безусловно - stopService на не запущенный
        // сервис дешёвый no-op, ничего не поднимает.
        //
        // ProxyService - обычный Service (WG-туннель живёт в отдельной GoBackend$
        // VpnService библиотеки com.wireguard.android) - внешний stopService() ок.
        // RealityVpnService - САМ VpnService: система держит на нём собственный
        // internal binding (см. dumpsys activity services - AppBindRecord "...:system"),
        // пока туннель активен. Внешний stopService() его не убивает - сервис и
        // туннель остаются жить, onDestroy() просто никогда не вызывается. Верный
        // путь - stopSelf() ИЗНУТРИ, через STOP-экшен (onStartCommand уже его
        // обрабатывает первым делом, до старта foreground/туннеля).
        //
        // Но RealityVpnService теперь живёт в отдельном процессе (:reality,
        // android:process в манифесте) - startService() на него, если он не
        // запущен, поднимает процесс с нуля (~600мс: zygote, classloader, DI),
        // только чтобы доставить no-op teardown. Если следом почти сразу идёт
        // настоящий connect в Reality (свой startForegroundService на тот же
        // класс), оба интента попадают в один процесс и гонка между stopSelf()
        // STOP-ветки и startForeground() connect-ветки роняет сервис с
        // ForegroundServiceDidNotStartInTimeException - живой краш, воспроизведён
        // 2026-08-14. Поэтому шлём STOP только если Reality реально был тем,
        // что мы сами запускали (lastStartedClass), не спекулятивно.
        context.stopService(Intent(context, ProxyService::class.java))
        if (lastStartedClass == RealityVpnService::class.java) {
            context.startService(Intent(context, RealityVpnService::class.java).apply { action = ProxyActions.STOP })
        }
        realityStateBridge.unbind()
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
