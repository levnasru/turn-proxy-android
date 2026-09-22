package com.freeturn.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.domain.proxy.ProxyServiceState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ProxyReceiver : BroadcastReceiver(), KoinComponent {

    private val launcher: ProxyServiceLauncher by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ProxyActions.START -> {
                ProxyServiceState.clearLogs()
                ProxyServiceState.setStartupResult(null)
                launcher.start()
            }
            ProxyActions.STOP -> {
                launcher.stop()
            }
        }
    }
}
