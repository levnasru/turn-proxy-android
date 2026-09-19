package com.freeturn.app.data.config

import java.security.SecureRandom

object DnsMode {
    const val AUTO = "auto"
    const val PLAIN = "plain"
    const val DOH = "doh"
    val VALUES = listOf(AUTO, PLAIN, DOH)
}

object Provider {
    const val VK = "vk"

    /** Готовые TURN-креды с доверенного хаба: VK API клиент не трогает, капчи нет. */
    const val HUB = "hub"
    val VALUES = listOf(VK, HUB)
}

object ObfProfile {
    const val NONE = "none"
    const val RTPOPUS = "rtpopus"
    const val RTPOPUS2 = "rtpopus2"
    const val RTPOPUS3 = "rtpopus3"
    const val RTPVIDEO = "rtpvideo"
    val VALUES = listOf(NONE, RTPOPUS, RTPOPUS2, RTPOPUS3, RTPVIDEO)

    private val KEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun isValidKey(key: String): Boolean = key.matches(KEY_REGEX)

    fun generateKey(): String =
        ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

// Ядро не принимает IPv6-адреса в скобках.
object HostPort {
    private val REGEX = Regex("""^[\w.\-]+:\d{1,5}$""")

    fun isValid(value: String): Boolean =
        value.matches(REGEX) && value.substringAfterLast(":").toInt() in 1..65535
}

object ClientId {
    private val ID_REGEX = Regex("^[0-9a-f]{32}$")

    fun isValid(id: String): Boolean = id.matches(ID_REGEX)

    fun generate(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

object TunnelTransport {
    const val NONE = "none"
    const val WIREGUARD = "wireguard"
    const val VK_XRAY = "vk_xray"
    const val AMNEZIA = "amnezia_wg"
    // REALITY - прямой VLESS+XHTTP+Reality через встроенный libXray, БЕЗ нашего
    // TURN-транспорта (не подпроцесс - Go-библиотека в том же процессе, см.
    // RealityVpnService). Для родни без строгого вайтлиста - тот же профиль, что
    // они раньше гоняли в отдельном v2rayNG, теперь в одном приложении.
    const val REALITY = "reality"
    const val DEFAULT_TUNNEL_NAME = "freeturn-wg"
    val VALUES = listOf(NONE, WIREGUARD, VK_XRAY, AMNEZIA, REALITY)
}

object SplitTunnelMode {
    const val ALL = "all"
    const val INCLUDE = "include"
    const val EXCLUDE = "exclude"
    val VALUES = listOf(ALL, INCLUDE, EXCLUDE)
}
