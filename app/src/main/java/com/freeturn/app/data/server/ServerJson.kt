package com.freeturn.app.data.server

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.DnsMode
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.config.Provider
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.data.config.TunnelTransport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Имена JSON-ключей - контракт с сохранёнными данными: менять только с миграцией.
internal object ServerJson {
    fun encodeList(list: List<Server>): String {
        val arr = JSONArray()
        list.forEach { arr.put(encode(it)) }
        return arr.toString()
    }

    fun decodeList(raw: String?): List<Server> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { decode(arr.getJSONObject(it)) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun encode(p: Server): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("client", JSONObject().apply {
            put("serverAddress", p.client.serverAddress)
            put("vkLink", p.client.vkLink)
            put("provider", p.client.provider)
            put("threads", p.client.threads)
            put("streamsPerCred", p.client.streamsPerCred)
            put("useUdp", p.client.useUdp)
            put("manualCaptcha", p.client.manualCaptcha)
            put("localPort", p.client.localPort)
            put("isRawMode", p.client.isRawMode)
            put("rawCommand", p.client.rawCommand)
            put("tcpForward", p.client.tcpForward)
            put("bond", p.client.bond)

            put("debugMode", p.client.debugMode)
            put("useCarrierDns", p.client.useCarrierDns)
            put("dnsMode", p.client.dnsMode)
            put("customDns", p.client.customDns)
            put("syncServerSwitches", p.client.syncServerSwitches)
            put("magicSwitch", p.client.magicSwitch)
            put("magicTurn", p.client.magicTurn)
            put("tunnelTransport", p.client.tunnelTransport)
            put("wireGuardConfig", p.client.wireGuardConfig)
            put("wireGuardTunnelName", p.client.wireGuardTunnelName)
            put("xrayConfig", p.client.xrayConfig)
            put("splitTunnelMode", p.client.splitTunnelMode)
            put("splitTunnelApps", p.client.splitTunnelApps)
            put("logsEnabled", p.client.logsEnabled)
            put("clientId", p.client.clientId)
            put("hubUrl", p.client.hubUrl)
            put("hubPin", p.client.hubPin)
            put("hubToken", p.client.hubToken)
            put("hubCache", p.client.hubCache)
        })
        put("proxyListen", p.proxyListen)
        put("proxyConnect", p.proxyConnect)
        put("opts", JSONObject().apply {
            put("obfProfile", p.opts.obfProfile)
            put("obfKey", p.opts.obfKey)
        })
        put("subscriptionId", p.subscriptionId)
        put("subscriptionNodeKey", p.subscriptionNodeKey)
    }

    private fun decode(o: JSONObject): Server {
        val cliO = o.optJSONObject("client") ?: JSONObject()
        val optsO = o.optJSONObject("opts") ?: JSONObject()
        return Server(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name").ifBlank { Server.FALLBACK_NAME },
            client = ClientConfig(
                serverAddress = cliO.optString("serverAddress"),
                vkLink = cliO.optString("vkLink"),
                provider = cliO.optString("provider", Provider.VK).let {
                    if (it in Provider.VALUES) it else Provider.VK
                },
                // Фоллбэки = дефолты ClientConfig (для новых полей).
                threads = cliO.optInt("threads", 12),
                streamsPerCred = cliO.optInt("streamsPerCred", 6),
                useUdp = cliO.optBoolean("useUdp", false),
                manualCaptcha = cliO.optBoolean("manualCaptcha", false),
                localPort = cliO.optString("localPort", ClientConfig.DEFAULT_LOCAL_PORT),
                isRawMode = cliO.optBoolean("isRawMode", false),
                rawCommand = cliO.optString("rawCommand"),
                tcpForward = cliO.optBoolean("tcpForward", false),
                bond = cliO.optBoolean("bond", false),

                debugMode = cliO.optBoolean("debugMode", false),
                useCarrierDns = cliO.optBoolean("useCarrierDns", true),
                dnsMode = cliO.optString("dnsMode", DnsMode.AUTO).let {
                    if (it in DnsMode.VALUES) it else DnsMode.AUTO
                },
                customDns = cliO.optString("customDns"),
                syncServerSwitches = cliO.optBoolean("syncServerSwitches", true),
                magicSwitch = cliO.optBoolean("magicSwitch", false),
                magicTurn = cliO.optString("magicTurn"),
                tunnelTransport = cliO.optString("tunnelTransport", TunnelTransport.NONE).let {
                    if (it in TunnelTransport.VALUES) it else TunnelTransport.NONE
                },
                wireGuardConfig = cliO.optString("wireGuardConfig"),
                wireGuardTunnelName = cliO.optString("wireGuardTunnelName").ifBlank { TunnelTransport.DEFAULT_TUNNEL_NAME },
                xrayConfig = cliO.optString("xrayConfig"),
                splitTunnelMode = cliO.optString("splitTunnelMode", SplitTunnelMode.EXCLUDE).let {
                    if (it in SplitTunnelMode.VALUES) it else SplitTunnelMode.EXCLUDE
                },
                splitTunnelApps = cliO.optString("splitTunnelApps"),
                logsEnabled = cliO.optBoolean("logsEnabled", true),
                clientId = cliO.optString("clientId"),
                hubUrl = cliO.optString("hubUrl"),
                hubPin = cliO.optString("hubPin"),
                hubToken = cliO.optString("hubToken"),
                hubCache = cliO.optString("hubCache")
            ),
            proxyListen = o.optString("proxyListen").ifBlank { "0.0.0.0:56000" },
            proxyConnect = o.optString("proxyConnect").ifBlank { "127.0.0.1:40537" },
            opts = ServerOpts(
                obfProfile = optsO.optString("obfProfile", ObfProfile.NONE).let {
                    if (it in ObfProfile.VALUES) it else ObfProfile.NONE
                },
                obfKey = optsO.optString("obfKey", "")
            ),
            subscriptionId = o.optString("subscriptionId"),
            subscriptionNodeKey = o.optString("subscriptionNodeKey")
        )
    }
}
