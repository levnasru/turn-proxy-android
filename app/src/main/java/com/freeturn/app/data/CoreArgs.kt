package com.freeturn.app.data

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.DnsMode
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.config.Provider
import com.freeturn.app.data.server.ServerOpts

import com.freeturn.app.data.config.TunnelTransport

object
CoreArgs {

    fun client(
        cfg: ClientConfig,
        srv: ServerOpts,
        carrierDns: String? = null,
        ownClientId: String? = null,
        protectPath: String? = null,
    ): List<String> = buildList {
        val effectivePeer = if (cfg.tunnelTransport == TunnelTransport.VK_XRAY) {
            if (cfg.serverAddress.contains(":")) {
                cfg.serverAddress.substringBeforeLast(":") + ":56003"
            } else if (cfg.serverAddress.isNotBlank()) {
                "${cfg.serverAddress}:56003"
            } else {
                cfg.serverAddress
            }
        } else {
            cfg.serverAddress
        }
        add("-peer"); add(effectivePeer)
        add("-provider"); add(cfg.provider)
        if (cfg.provider == Provider.VK) { add("-link"); add(cfg.vkLink) }
        if (cfg.provider == Provider.HUB) {
            add("-hub-url"); add(cfg.hubUrl.trim())
            add("-hub-pin"); add(cfg.hubPin.trim())
            add("-hub-token"); add(cfg.hubToken.trim())
            // Кеш нужен всегда: за операторским белым списком хаб недоступен до
            // подъёма туннеля, стартуем со старых кредов. Относительный путь ->
            // CWD ядра (filesDir, см. CoreProcessController).
            add("-hub-cache"); add(cfg.hubCache.trim().ifBlank { DEFAULT_HUB_CACHE })
        }
        add("-listen"); add(cfg.localPort)
        val effectiveThreads = effectivePerProviderThreads(cfg)
        if (effectiveThreads > 0) { add("-n"); add(effectiveThreads.toString()) }
        // -streams-per-cred читает только vk-провайдер (config.go): под хабом креды
        // приходят готовыми, флаг никуда не доедет.
        if (cfg.provider == Provider.VK && cfg.streamsPerCred > 0 && cfg.streamsPerCred != 10) {
            add("-streams-per-cred"); add(cfg.streamsPerCred.toString())
        }
        val isTcp = cfg.tcpForward || cfg.tunnelTransport == TunnelTransport.VK_XRAY
        val isBond = (cfg.tcpForward && cfg.bond) || cfg.tunnelTransport == TunnelTransport.VK_XRAY
        if (isTcp) { add("-mode"); add("tcp") }
        if (isBond) add("-bond")
        if (cfg.useUdp && !isTcp) { add("-transport"); add("udp") }
        if (!isTcp) {
            add("-batch"); add("8")
        }
        if (srv.obfEnabled) {
            add("-obf-profile"); add(srv.obfProfile)
            if (ObfProfile.isValidKey(srv.obfKey)) {
                add("-obf-key"); add(srv.obfKey)
            }
        }
        if (cfg.manualCaptcha) add("-manual-captcha")
        if (cfg.provider == Provider.VK) { add("-platform"); add("mobile") }
        if (cfg.debugMode) add("-debug")
        val manualDns = DnsList.normalize(cfg.customDns)
        when {
            manualDns.isNotBlank() -> { add("-dns-servers"); add(manualDns) }
            cfg.useCarrierDns -> {
                carrierDns?.takeIf { it.isNotBlank() }?.let { add("-dns-servers"); add(it) }
            }
        }
        if (cfg.dnsMode == DnsMode.PLAIN || cfg.dnsMode == DnsMode.DOH) {
            add("-dns-mode"); add(cfg.dnsMode)
        }
        if (cfg.magicSwitch) {
            cfg.magicTurn.trim().takeIf { it.isNotEmpty() }?.let { add("-turn"); add(it) }
        }
        val clientId = cfg.clientId.ifBlank { ownClientId.orEmpty() }
        if (clientId.isNotBlank()) { add("-client-id"); add(clientId) }
        if (!protectPath.isNullOrBlank()) { add("-protect-path"); add(protectPath) }
    }

    /** Кеш кредов по умолчанию: относительный путь = CWD ядра (filesDir приложения). */
    const val DEFAULT_HUB_CACHE = "hubcreds-cache.json"

    /**
     * Потолок числа TURN-потоков для TCP-режима (VK-Xray): smux-сессии при большом числе потоков
     * деградируют и приводят к head-of-line blocking и сбросам TCP, в отличие от WireGuard UDP.
     */
    const val MAX_VK_XRAY_THREADS = 20

    fun numProviders(cfg: ClientConfig): Int = when (cfg.provider) {
        Provider.HUB -> cfg.hubUrl.split(",").map { it.trim() }.filter { it.isNotEmpty() }.size.coerceAtLeast(1)
        Provider.VK -> cfg.vkLink.split(",").map { it.trim() }.filter { it.isNotEmpty() }.size.coerceAtLeast(1)
        else -> 1
    }

    /**
     * Число потоков на одного провайдера (-n):
     * В ядре (free-turn-proxy) аргумент -n задаёт число потоков НА ОДИН аккаунт/ссылку
     * (totalStreams = cfg.TURN.N * max(providerCount, 1)).
     * Поэтому для достижения суммарного лимита cfg.threads мы делим его на число провайдеров.
     * Для VK_XRAY суммарный лимит дополнительно ограничен MAX_VK_XRAY_THREADS (20).
     */
    fun effectivePerProviderThreads(cfg: ClientConfig): Int {
        val providers = numProviders(cfg)
        val userThreads = if (cfg.threads > 0) cfg.threads else ClientConfig.DEFAULT_THREADS
        val targetTotal = if (cfg.tunnelTransport == TunnelTransport.VK_XRAY) {
            minOf(userThreads, MAX_VK_XRAY_THREADS)
        } else {
            userThreads
        }
        return maxOf(1, targetTotal / providers)
    }

    /**
     * Ожидаемое суммарное число потоков ядра.
     */
    fun effectiveTotalStreams(cfg: ClientConfig): Int {
        val providers = numProviders(cfg)
        return effectivePerProviderThreads(cfg) * providers
    }

    // Секреты: лог виден на экране и шарится пользователем.
    private val SENSITIVE_FLAGS = setOf("-obf-key", "-link", "-client-id", "-hub-token", "-hub-url")

    /**
     * Адаптирует сырые флаги (isRawMode) для режима VK-Xray:
     * - Переключает peer-порт на :56003
     * - Удаляет -transport udp и -batch 8 (UDP-специфика)
     * - Включает -mode tcp -bond
     * - Ограничивает суммарное число потоков потолком MAX_VK_XRAY_THREADS
     */
    fun adaptRawArgsForVkXray(args: List<String>): List<String> {
        var numProviders = 1
        for (j in 0 until args.size - 1) {
            val flag = args[j]
            val value = args[j + 1]
            if (flag == "-hub-url" || flag == "-link") {
                val count = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.size
                if (count > 0) numProviders = count
            }
        }
        val maxPerProvider = maxOf(1, MAX_VK_XRAY_THREADS / numProviders)

        val result = mutableListOf<String>()
        var i = 0
        var foundN = false

        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-peer" && i + 1 < args.size -> {
                    val peer = args[i + 1]
                    val host = if (peer.contains(":")) peer.substringBeforeLast(":") else peer
                    result.add("-peer")
                    result.add("$host:56003")
                    i += 2
                }
                arg == "-n" && i + 1 < args.size -> {
                    val nVal = args[i + 1].toIntOrNull() ?: maxPerProvider
                    result.add("-n")
                    result.add(minOf(nVal, maxPerProvider).toString())
                    foundN = true
                    i += 2
                }
                arg == "-transport" && i + 1 < args.size -> {
                    i += 2
                }
                arg == "-batch" && i + 1 < args.size -> {
                    i += 2
                }
                arg == "-mode" && i + 1 < args.size -> {
                    i += 2
                }
                arg == "-bond" -> {
                    i++
                }
                else -> {
                    result.add(arg)
                    i++
                }
            }
        }

        result.add("-mode")
        result.add("tcp")
        result.add("-bond")
        if (!foundN) {
            result.add("-n")
            result.add(maxPerProvider.toString())
        }

        return result
    }

    fun redactForLog(args: List<String>, privacy: Boolean): String = buildString {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (isNotEmpty()) append(' ')
            append(arg)
            if (privacy && arg in SENSITIVE_FLAGS && i + 1 < args.size) {
                append(' ').append("••••••")
                i += 2
            } else {
                i++
            }
        }
    }
}
