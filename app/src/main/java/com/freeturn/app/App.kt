package com.freeturn.app

import android.app.Application
import android.os.Build
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.di.appModule
import com.freeturn.app.domain.proxy.ProxyServiceState
import com.freeturn.app.service.ProxyWidgetProvider
import com.freeturn.app.service.reality.RealityStateBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class App : Application() {

    private val appPreferences: AppPreferences by inject()
    private val realityStateBridge: RealityStateBridge by inject()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // ed25519/curve25519 работает через Bouncy Castle в classpath. jsch 2.x подхватывает его сам.
        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(appModule)
        }
        // App.onCreate() выполняется в КАЖДОМ процессе приложения, включая
        // изолированный :reality (см. RealityVpnService's android:process в
        // манифесте) - виджет и мост нужны только основному процессу.
        if (isMainProcess()) {
            observeWidgetState()
            // Если Reality-туннель уже работал в фоне (процесс :reality пережил
            // пересоздание основного процесса), подключаемся к нему сразу, а не
            // ждём следующего нажатия "подключиться".
            realityStateBridge.bind()
        }
    }

    // App.onCreate() запускается в каждом процессе приложения - привязка к
    // RealityVpnService изнутри самого :reality (до того, как сервис вообще
    // создан) гоняется с его собственным startForegroundService()-стартом и может
    // выбить FGS start-in-time бюджет. Только основной процесс должен биндиться
    // и держать наблюдателей виджета.
    private fun isMainProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName() == packageName
        }
        // ActivityManager.runningAppProcesses может вернуть null/пусто на части
        // OEM-прошивок и в ограниченных состояниях - тогда "== true"-идиома молча
        // трактует это как "не главный", и мост никогда не подключится даже в
        // реальном главном процессе (полная тихая потеря фичи на API 24-27).
        // /proc/self/cmdline читает командную строку СВОЕГО ЖЕ процесса - всегда
        // доступно ядром для самого процесса, не зависит от ActivityManager.
        return try {
            java.io.File("/proc/self/cmdline").readText().trim(' ', ' ') == packageName
        } catch (e: Exception) {
            false
        }
    }

    // Перерисовывает виджет при смене статуса прокси или активного сервера
    // (RemoteViews не реактивны - их надо толкать вручную).
    private fun observeWidgetState() {
        combine(
            ProxyServiceState.isRunning,
            ProxyServiceState.connectionStats,
            appPreferences.serversSnapshot
        ) { running, stats, snap ->
            listOf(running, stats.active, stats.total, snap.active?.name)
        }
            .distinctUntilChanged()
            .onEach { ProxyWidgetProvider.refresh(this) }
            .launchIn(scope)
    }
}
