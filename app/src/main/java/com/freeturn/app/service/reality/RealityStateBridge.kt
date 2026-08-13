package com.freeturn.app.service.reality

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.proxy.ProxyServiceState
import com.freeturn.app.service.RealityVpnService

/**
 * Клиентская сторона моста к RealityVpnService (процесс :reality, см.
 * AndroidManifest.xml). Только слушает - не поднимает и не останавливает
 * сервис (старт/стоп остаются через Intent, см. AndroidProxyServiceLauncher).
 * Живёт в основном процессе, применяет полученные события к
 * ProxyServiceState - так что LocalProxyManager и весь остальной UI не
 * меняются вообще.
 */
class RealityStateBridge(private val context: Context) {

    private var bound = false

    private val clientMessenger = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                RealityIpc.MSG_STATE_UPDATE -> applyState(msg.data.toRealityState())
                RealityIpc.MSG_LOG_LINE -> ProxyServiceState.addLog(msg.data.toRealityLogText())
            }
            true
        }
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            val messenger = Messenger(binder)
            val register = Message.obtain(null, RealityIpc.MSG_REGISTER_CLIENT).apply {
                replyTo = clientMessenger
            }
            try {
                messenger.send(register)
            } catch (e: RemoteException) {
                bound = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            // Процесс :reality умер не по штатному teardown - не оставлять UI
            // подвисшим на "подключено", когда сервиса на деле уже нет.
            ProxyServiceState.setRunning(false)
        }
    }

    /** No-op, если RealityVpnService не запущен - не поднимает его сам (flags=0, без BIND_AUTO_CREATE). */
    fun bind() {
        if (bound) return
        runCatching {
            context.bindService(Intent(context, RealityVpnService::class.java), connection, 0)
        }
    }

    fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
    }

    private fun applyState(state: RealityState) {
        ProxyServiceState.setRunning(state.running)
        ProxyServiceState.setConnectionStats(ConnectionStats(state.active, state.total))
        ProxyServiceState.setStartupResult(
            state.failedMessage?.let { StartupResult.Failed(it) } ?: StartupResult.Success
        )
        ProxyServiceState.setTunnelActive(state.tunnelActive)
        if (state.connectedSince != null) {
            ProxyServiceState.markConnectedIfAbsent(state.connectedSince)
        } else {
            ProxyServiceState.clearConnectedSince()
        }
        if (state.teardownComplete) ProxyServiceState.markTeardownComplete() else ProxyServiceState.markTeardownStarted()
    }
}
