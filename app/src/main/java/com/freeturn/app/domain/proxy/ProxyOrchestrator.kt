package com.freeturn.app.domain.proxy

import com.freeturn.app.data.AppPreferences
import kotlinx.coroutines.flow.first

class ProxyOrchestrator(
    private val prefs: AppPreferences,
    private val proxyManager: LocalProxyManager
) {
    suspend fun restartProxyIfRunning() {
        if (!ProxyServiceState.isRunning.value) return
        proxyManager.stopProxy()
        proxyManager.startProxy(prefs.clientConfigFlow.first())
    }
}
