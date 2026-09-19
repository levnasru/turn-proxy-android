package com.freeturn.app.data.config

data class ClientConfig(
    val serverAddress: String = "",
    val vkLink: String = "",
    val provider: String = Provider.VK,
    val threads: Int = DEFAULT_THREADS,
    val streamsPerCred: Int = DEFAULT_STREAMS_PER_CRED,
    val useUdp: Boolean = false,
    val manualCaptcha: Boolean = false,
    val localPort: String = DEFAULT_LOCAL_PORT,
    val isRawMode: Boolean = false,
    val rawCommand: String = "",
    val tcpForward: Boolean = false,
    val bond: Boolean = false,
    val debugMode: Boolean = false,
    val useCarrierDns: Boolean = true,
    val dnsMode: String = DnsMode.AUTO,
    val customDns: String = "",
    val syncServerSwitches: Boolean = true,
    val magicSwitch: Boolean = false,
    val magicTurn: String = "",
    val tunnelTransport: String = TunnelTransport.NONE,
    val wireGuardConfig: String = "",
    val wireGuardTunnelName: String = TunnelTransport.DEFAULT_TUNNEL_NAME,
    /**
     * Сырой Xray-core JSON (vless+xhttp+reality и т.п.) для TunnelTransport.REALITY -
     * тот же формат, что и десктопный v2ray/Xray конфиг, копируется как есть, меняется
     * только users[].id на человека. tun-инбаунд обязателен (см. RealityVpnService,
     * который дописывает env.xray.tun.fd перед запуском).
     */
    val xrayConfig: String = "",
    val splitTunnelMode: String = SplitTunnelMode.EXCLUDE,
    /**
     * Package-имена для include/exclude (разделители: запятая/пробел/перенос строки).
     * Пустой в exclude-режиме = дефолтный список рос-сервисов (см. [splitTunnelSelection]).
     */
    val splitTunnelApps: String = "",
    /**
     * Пользовательские правила обхода (домены и IP/подсети, разделенные переносом строки или запятой).
     * Трафик к ним идет напрямую мимо туннеля (WireGuard AllowedIPs exclusion / Xray direct routing).
     */
    val bypassRules: String = "",
    val logsEnabled: Boolean = true,
    val clientId: String = "",
    /** `-hub-url`: один или несколько эндпоинтов через запятую (аккаунт на эндпоинт). */
    val hubUrl: String = "",
    /** `-hub-pin`: base64 SHA-256 SPKI сертификата хаба. */
    val hubPin: String = "",
    /** `-hub-token`: Bearer. Секрет - в логах маскируется, см. CoreArgs.SENSITIVE_FLAGS. */
    val hubToken: String = "",
    /** `-hub-cache`: дисковый кеш кредов. Пустой = путь по умолчанию в filesDir. */
    val hubCache: String = ""
) {
    val wireGuardActive: Boolean
        get() = tunnelTransport == TunnelTransport.WIREGUARD && wireGuardConfig.isNotBlank()

    val vkXrayActive: Boolean
        get() = tunnelTransport == TunnelTransport.VK_XRAY

    val amneziaActive: Boolean
        get() = tunnelTransport == TunnelTransport.AMNEZIA && wireGuardConfig.isNotBlank()

    val realityActive: Boolean
        get() = tunnelTransport == TunnelTransport.REALITY && xrayConfig.isNotBlank()

    val anyTunnelActive: Boolean
        get() = wireGuardActive || vkXrayActive || amneziaActive || realityActive

    val hubMode: Boolean get() = provider == Provider.HUB

    companion object {
        const val DEFAULT_LOCAL_PORT = "127.0.0.1:9000"
        const val DEFAULT_THREADS = 12
        const val DEFAULT_STREAMS_PER_CRED = 12
        // Не настройка, а константа транспорта: WG идёт поверх TURN+DTLS+обфускации+reseq.
        // Оверхед включает 38-байт DTLS (13B заголовок + 9B CID + 16B AEAD tag) + RTP + WireGuard + IPv6.
        // Внутренний MTU 1050 оставляет достаточный запас, чтобы внешний пакет строго укладывался в IPv6 1280 wire MTU.
        const val WG_MTU = 1050
    }
}
