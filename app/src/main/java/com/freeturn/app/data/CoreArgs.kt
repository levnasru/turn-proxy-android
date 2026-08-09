package com.freeturn.app.data

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.DnsMode
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.config.Provider
import com.freeturn.app.data.server.ServerOpts

object
CoreArgs {

    fun client(
        cfg: ClientConfig,
        srv: ServerOpts,
        carrierDns: String? = null,
        ownClientId: String? = null,
        protectPath: String? = null,
    ): List<String> = buildList {
        add("-peer"); add(cfg.serverAddress)
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
        if (cfg.threads > 0) { add("-n"); add(cfg.threads.toString()) }
        // -streams-per-cred читает только vk-провайдер (config.go): под хабом креды
        // приходят готовыми, флаг никуда не доедет.
        if (cfg.provider == Provider.VK && cfg.streamsPerCred > 0 && cfg.streamsPerCred != 10) {
            add("-streams-per-cred"); add(cfg.streamsPerCred.toString())
        }
        if (cfg.tcpForward) { add("-mode"); add("tcp") }
        if (cfg.tcpForward && cfg.bond) add("-bond")
        if (cfg.useUdp) { add("-transport"); add("udp") }
        if (srv.obfEnabled && ObfProfile.isValidKey(srv.obfKey)) {
            add("-obf-profile"); add(srv.obfProfile)
            add("-obf-key"); add(srv.obfKey)
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

    // Секреты: лог виден на экране и шарится пользователем.
    private val SENSITIVE_FLAGS = setOf("-obf-key", "-link", "-client-id", "-hub-token", "-hub-url")

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
