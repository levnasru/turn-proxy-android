package com.freeturn.app.service

/** Action-строки управления [ProxyService] через [ProxyReceiver] (тайл/нотификация). */
object ProxyActions {
    const val START = "com.freeturn.app.START_PROXY"
    const val STOP = "com.freeturn.app.STOP_PROXY"

    /** Кнопка WG: поднять/погасить туннель поверх живого ядра. Флаг [EXTRA_WG_ENABLED]. */
    const val SET_WIREGUARD = "com.freeturn.app.SET_WIREGUARD"
    const val EXTRA_WG_ENABLED = "wg_enabled"

    /** Готовый Xray-конфиг для запуска RealityVpnService - передаётся интентом, а не
     * перечитывается сервисом из DataStore, т.к. :reality может закэшировать процесс
     * между сессиями (см. AndroidProxyServiceLauncher.start()). */
    const val EXTRA_XRAY_CONFIG = "xray_config"
}
