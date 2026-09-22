package com.freeturn.app.domain.proxy

import android.net.VpnService
import android.util.Log
import java.lang.ref.WeakReference

object VpnServiceHolder {
    @Volatile
    private var activeVpnRef: WeakReference<VpnService>? = null

    fun register(service: VpnService) {
        activeVpnRef = WeakReference(service)
        Log.i("VpnServiceHolder", "Registered active VpnService: ${service.javaClass.simpleName}")
    }

    fun unregister(service: VpnService) {
        val current = activeVpnRef?.get()
        if (current == null || current === service) {
            activeVpnRef = null
            Log.i("VpnServiceHolder", "Unregistered VpnService: ${service.javaClass.simpleName}")
        }
    }

    fun get(): VpnService? = activeVpnRef?.get()
}
