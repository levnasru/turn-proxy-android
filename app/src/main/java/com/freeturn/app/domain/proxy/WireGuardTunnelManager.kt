package com.freeturn.app.domain.proxy

import android.content.Context
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.data.config.splitTunnelSelection
import com.freeturn.app.data.isPackageInstalled
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Поднимает WireGuard-туннель поверх локального прокси.
 * Заменяет Endpoint в первом [Peer] на localPort прокси.
 */
class WireGuardTunnelManager(context: Context) {

    private val appContext = context.applicationContext
    private val backend by lazy { GoBackend(appContext) }
    private val tunnelRef = AtomicReference<NamedTunnel?>(null)
    // Без сериализации stop во время старта оставлял туннель поднятым (no-op до tunnelRef.set).
    private val mutex = Mutex()

    /** Поднять туннель после того как прокси-ядро установило соединение. No-op без WG-конфига. */
    suspend fun startAfterProxyReady(cfg: ClientConfig) {
        if (!cfg.wireGuardActive) return
        val rawConfig = cfg.wireGuardConfig.trim()
        if (rawConfig.isBlank()) {
            ProxyServiceState.addLog("WireGuard: конфиг пуст, запуск пропущен")
            return
        }

        val name = cfg.wireGuardTunnelName.trim().ifBlank { TunnelTransport.DEFAULT_TUNNEL_NAME }
        val endpoint = cfg.localPort.trim()
        val preparedConfig = rawConfig
            .withLocalEndpoint(endpoint)
            .withMtu(ClientConfig.WG_MTU)
            .withLanBypass()
            .withSplitTunnel(
                appPackage = appContext.packageName,
                mode = cfg.splitTunnelMode,
                // Непоставленные пакеты отсеиваем - addDisallowedApplication валит туннель на них.
                packages = splitTunnelSelection(cfg.splitTunnelMode, cfg.splitTunnelApps)
                    .filter { appContext.isPackageInstalled(it) }
            )
        val config = Config.parse(
            ByteArrayInputStream(preparedConfig.toByteArray(StandardCharsets.UTF_8))
        )

        mutex.withLock {
            stopLocked()
            val tunnel = NamedTunnel(name)
            // Ссылка ДО setState: при провале откат/stop опустит частично поднятый туннель.
            tunnelRef.set(tunnel)
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
            } catch (e: Exception) {
                stopLocked()
                throw e
            }
            ProxyServiceState.setTunnelActive(true)
            ProxyServiceState.addLog("WireGuard: туннель $name поднят через $endpoint")
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private fun stopLocked() {
        ProxyServiceState.setTunnelActive(false)
        val tunnel = tunnelRef.getAndSet(null) ?: return
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            ProxyServiceState.addLog("WireGuard: туннель ${tunnel.name} остановлен")
        } catch (e: Exception) {
            ProxyServiceState.addLog("WireGuard: ошибка остановки ${tunnel.name}: ${e.message}")
        }
    }

    private class NamedTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName

        // Единственный источник правды об интерфейсе - GoBackend зовёт это и при
        // нашем backend.setState, и при внешнем revoke (второй VPN забрал
        // единственный VPN-слот ОС). Без этого wireGuardUp остаётся true даже
        // после того как система молча уронила туннель - UI врёт про рукопожатие.
        override fun onStateChange(newState: Tunnel.State) {
            ProxyServiceState.addLog("WireGuard: состояние $tunnelName -> $newState")
            ProxyServiceState.setWireGuardUp(newState == Tunnel.State.UP)
        }
    }
}

private fun String.withLocalEndpoint(endpoint: String): String {
    if (endpoint.isBlank()) return this
    var inPeer = false
    var replaced = false
    // Подменяем Endpoint только в первом [Peer].
    val lines = lineSequence().map { line ->
        val section = line.trim()
        if (section.startsWith("[") && section.endsWith("]")) {
            inPeer = section.equals("[Peer]", ignoreCase = true)
        }
        if (inPeer && !replaced && section.startsWith("Endpoint", ignoreCase = true) &&
            section.contains("=")) {
            replaced = true
            "Endpoint = $endpoint"
        } else {
            line
        }
    }.toMutableList()
    if (!replaced) {
        lines += ""
        lines += "Endpoint = $endpoint"
    }
    return lines.joinToString("\n")
}

private fun String.withMtu(mtu: Int): String {
    var inInterface = false
    val lines = mutableListOf<String>()
    lineSequence().forEach { line ->
        val section = line.trim()
        if (section.startsWith("[") && section.endsWith("]")) {
            inInterface = section.equals("[Interface]", ignoreCase = true)
        }
        val isMtuLine = inInterface && section.startsWith("MTU", ignoreCase = true) &&
            section.contains("=")
        if (!isMtuLine) lines += line
    }
    val interfaceIndex = lines.indexOfFirst {
        it.trim().equals("[Interface]", ignoreCase = true)
    }
    if (interfaceIndex < 0) return lines.joinToString("\n")
    lines.add(interfaceIndex + 1, "MTU = $mtu")
    return lines.joinToString("\n")
}

// RFC1918 + link-local + loopback - локальная сеть (принтер, NAS, роутер, "умный дом")
// остаётся доступна поверх поднятого WG, вместо того чтобы тоже маршрутизироваться в туннель.
// IPv6-часть AllowedIPs не трогаем - LAN-устройства почти всегда IPv4, а вычитание диапазонов
// в 128-битном пространстве overkill для этой задачи (см. WgLanBypassTest, поведение
// зафиксировано assert-ами: приватные диапазоны исключены, IPv6-записи проходят как есть).
private val PRIVATE_IPV4_CIDRS = listOf(
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8"
)

private fun String.withLanBypass(): String {
    var inPeer = false
    val lines = lineSequence().map { line ->
        val section = line.trim()
        if (section.startsWith("[") && section.endsWith("]")) {
            inPeer = section.equals("[Peer]", ignoreCase = true)
        }
        if (inPeer && section.startsWith("AllowedIPs", ignoreCase = true) && section.contains("=")) {
            "AllowedIPs = ${excludeLanFromAllowedIps(section.substringAfter("=").trim())}"
        } else {
            line
        }
    }.toList()
    return lines.joinToString("\n")
}

internal fun excludeLanFromAllowedIps(value: String): String {
    val entries = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
    val ipv6 = entries.filter { it.contains(":") }
    val ipv4Ranges = entries.filterNot { it.contains(":") }.mapNotNull(::cidrToRange)
    if (ipv4Ranges.isEmpty()) return value

    val privateRanges = PRIVATE_IPV4_CIDRS.mapNotNull(::cidrToRange)
    val remaining = subtractRanges(mergeRanges(ipv4Ranges), mergeRanges(privateRanges))
    val ipv4Cidrs = remaining.flatMap { rangeToCidrs(it.first, it.last) }

    return (ipv4Cidrs + ipv6).joinToString(", ")
}

private fun cidrToRange(cidr: String): LongRange? {
    val parts = cidr.split("/")
    if (parts.size != 2) return null
    val octets = parts[0].split(".").map { it.toIntOrNull() ?: return null }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return null
    val prefix = parts[1].toIntOrNull() ?: return null
    if (prefix !in 0..32) return null
    val base = octets.fold(0L) { acc, o -> (acc shl 8) or o.toLong() }
    val hostBits = 32 - prefix
    val mask = if (hostBits == 0) 0L else (1L shl hostBits) - 1
    val start = base and mask.inv() and 0xFFFFFFFFL
    return start..(start or mask)
}

private fun rangeToCidrs(start: Long, end: Long): List<String> {
    val result = mutableListOf<String>()
    var s = start
    while (s <= end) {
        val alignBits = if (s == 0L) 32 else java.lang.Long.numberOfTrailingZeros(s).coerceAtMost(32)
        val remaining = end - s + 1
        val fitBits = 63 - java.lang.Long.numberOfLeadingZeros(remaining)
        val hostBits = minOf(alignBits, fitBits)
        val prefix = 32 - hostBits
        result += "${(s shr 24) and 0xFF}.${(s shr 16) and 0xFF}.${(s shr 8) and 0xFF}.${s and 0xFF}/$prefix"
        s += (1L shl hostBits)
    }
    return result
}

private fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedBy { it.first }
    val merged = mutableListOf(sorted.first())
    for (r in sorted.drop(1)) {
        val last = merged.last()
        if (r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
        else merged += r
    }
    return merged
}

// Вычитание из объединения диапазонов [allowed] объединения диапазонов [excluded] -
// стандартный interval subtraction, оба списка уже смёрджены/отсортированы.
private fun subtractRanges(allowed: List<LongRange>, excluded: List<LongRange>): List<LongRange> {
    var current = allowed
    for (ex in excluded) {
        val next = mutableListOf<LongRange>()
        for (r in current) {
            if (ex.last < r.first || ex.first > r.last) {
                next += r
                continue
            }
            if (ex.first > r.first) next += r.first..(ex.first - 1)
            if (ex.last < r.last) next += (ex.last + 1)..r.last
        }
        current = next
    }
    return current
}

private fun String.withSplitTunnel(
    appPackage: String,
    mode: String,
    packages: List<String>
): String {
    var inInterface = false
    var inserted = false
    val lines = mutableListOf<String>()
    lineSequence().forEach { line ->
        val section = line.trim()
        if (section.startsWith("[") && section.endsWith("]")) {
            inInterface = section.equals("[Interface]", ignoreCase = true)
        }
        val isSplitLine = inInterface && (
            section.startsWith("IncludedApplications", ignoreCase = true) ||
                section.startsWith("ExcludedApplications", ignoreCase = true)
            )
        if (!isSplitLine) lines += line
    }

    val interfaceIndex = lines.indexOfFirst {
        it.trim().equals("[Interface]", ignoreCase = true)
    }
    if (interfaceIndex < 0) return lines.joinToString("\n")

    val splitLines = when (mode) {
        SplitTunnelMode.INCLUDE -> {
            val included = packages.filter { it != appPackage }.distinct()
            if (included.isEmpty()) listOf("ExcludedApplications = $appPackage")
            else listOf("IncludedApplications = ${included.joinToString(",")}")
        }
        SplitTunnelMode.EXCLUDE -> {
            val excluded = (packages + appPackage).distinct()
            listOf("ExcludedApplications = ${excluded.joinToString(",")}")
        }
        else -> listOf("ExcludedApplications = $appPackage")
    }

    if (splitLines.isNotEmpty()) {
        lines.addAll(interfaceIndex + 1, splitLines)
        inserted = true
    }
    return if (inserted) lines.joinToString("\n") else this
}
