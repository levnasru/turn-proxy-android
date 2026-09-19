package com.freeturn.app.domain.proxy

sealed interface CoreLogEvent {
    data class CaptchaUrl(val url: String) : CoreLogEvent
    data object CaptchaResolved : CoreLogEvent
    data class StreamEstablished(val streamId: Int = 1) : CoreLogEvent
    data class StreamClosed(val streamId: Int = 1) : CoreLogEvent
    data class TotalStreams(val total: Int) : CoreLogEvent
    data class TcpTotal(val total: Int) : CoreLogEvent
    data class TcpActive(val active: Int) : CoreLogEvent
    data class FatalStartup(val line: String) : CoreLogEvent
    data object QuotaError : CoreLogEvent
}

object CoreLogParser {

    // Жесткая привязка к формату капчи (старое и новое ядро).
    private val CAPTCHA_URL_REGEX =
        Regex("""(?:manually open this URL|Open this URL in your browser):\s*(https?://\S+)""")

    private val STREAM_ESTABLISHED_REGEX =
        Regex("""\[STREAM (\d+)\] Established DTLS connection""")
    private val STREAM_CLOSED_REGEX =
        Regex("""\[STREAM (\d+)\] Closed DTLS connection""")
    private val MULTI_PROVIDER_TOTAL_REGEX =
        Regex("""multi-provider:\s*\d+\s*(?:hub accounts|VK links),\s*(\d+)\s*total streams""")
    private val TCP_ACTIVE_REGEX =
        Regex("""\[session \d+\] (?:connected|disconnected) \(active: (\d+)\)""")
    private val TCP_TOTAL_REGEX =
        Regex("""TCP mode: waiting for sessions to connect \(total: (\d+)\)""")

    fun parse(line: String): List<CoreLogEvent> {
        val events = mutableListOf<CoreLogEvent>()

        CAPTCHA_URL_REGEX.find(line)?.let {
            events += CoreLogEvent.CaptchaUrl(it.groupValues[1])
        }

        if (line.contains("[VK Auth] Failed") ||
            line.contains("[VK Auth] Success") ||
            (line.contains("[Captcha]") && line.contains("failed"))
        ) {
            events += CoreLogEvent.CaptchaResolved
        }

        STREAM_ESTABLISHED_REGEX.find(line)?.let {
            val id = it.groupValues[1].toIntOrNull() ?: 1
            events += CoreLogEvent.StreamEstablished(id)
        }
        STREAM_CLOSED_REGEX.find(line)?.let {
            val id = it.groupValues[1].toIntOrNull() ?: 1
            events += CoreLogEvent.StreamClosed(id)
        }
        MULTI_PROVIDER_TOTAL_REGEX.find(line)?.let {
            it.groupValues[1].toIntOrNull()?.let { total ->
                events += CoreLogEvent.TotalStreams(total)
            }
        }
        TCP_TOTAL_REGEX.find(line)?.let {
            events += CoreLogEvent.TcpTotal(it.groupValues[1].toInt())
        }
        TCP_ACTIVE_REGEX.find(line)?.let {
            events += CoreLogEvent.TcpActive(it.groupValues[1].toInt())
        }

        // Фатальный старт: не матчим "rate limit" (часть retry-цикла), ищем финальный отказ.
        // panic:/fatal error: - голый вывод рантайма Go, без префикса. Остальное идёт через
        // logx (log.Printf добавляет таймстемп + "[ERROR] "), поэтому startsWith не сработает -
        // матчим по уникальному токену внутри строки (hub.go:371, token_call.go:121/204).
        val lower = line.lowercase()
        if (lower.startsWith("panic:") ||
            lower.startsWith("fatal error:") ||
            lower.contains("hub_fetch_failed") ||
            lower.contains("[captcha] fatal:")
        ) {
            events += CoreLogEvent.FatalStartup(line)
        }

        if (lower.contains("quota")) events += CoreLogEvent.QuotaError

        return events
    }
}

class CoreConnectionTracker(
    private var udpTotal: Int,
    tcpMode: Boolean
) {
    private var isTcp = tcpMode
    private val udpActiveStreams = mutableSetOf<Int>()
    private var anonymousUdpActive = 0
    private var tcpActive = 0
    private var tcpTotal = 0

    val active: Int
        get() = if (isTcp) {
            tcpActive
        } else {
            val count = udpActiveStreams.size + anonymousUdpActive
            if (udpTotal > 0) minOf(count, udpTotal) else count
        }

    val total: Int
        get() = if (isTcp) tcpTotal else udpTotal

    val hasConnection: Boolean get() = active > 0

    fun apply(event: CoreLogEvent): Boolean = when (event) {
        is CoreLogEvent.StreamEstablished -> {
            isTcp = false
            if (event.streamId > 0) {
                udpActiveStreams.add(event.streamId)
            } else {
                anonymousUdpActive += 1
            }
            true
        }
        is CoreLogEvent.StreamClosed -> {
            if (event.streamId > 0) {
                udpActiveStreams.remove(event.streamId)
            } else if (anonymousUdpActive > 0) {
                anonymousUdpActive -= 1
            }
            true
        }
        is CoreLogEvent.TotalStreams -> {
            udpTotal = event.total
            isTcp = false
            true
        }
        is CoreLogEvent.TcpTotal -> {
            tcpTotal = event.total
            isTcp = true
            true
        }
        is CoreLogEvent.TcpActive -> {
            tcpActive = event.active
            isTcp = true
            true
        }
        else -> false
    }
}
