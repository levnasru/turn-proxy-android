package com.freeturn.app.data.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Генерирует локальный Xray JSON-конфиг для режима TunnelTransport.VK_XRAY:
 * входящий tun-интерфейс перенаправляет весь трафик устройства в протокол VLESS
 * на локальный порт 127.0.0.1:9000 ядра libfreeturn (которое отправляет его
 * через VK WebRTC TURN в порт :56003 VPS).
 *
 * Точный аналог десктопного buildVKTurnTunConfig (cmd/desktop/launcher.go).
 */
object VkTurnXrayConfigBuilder {
    const val VK_TURN_BRIDGE_UUID = "5897fcf0-6a80-4761-9917-7091e3618981"
    const val DEFAULT_LOCAL_PORT = 9000

    fun build(
        bypassDomains: List<String> = emptyList(),
        bypassIps: List<String> = emptyList(),
        localPort: Int = DEFAULT_LOCAL_PORT,
        mtu: Int = ClientConfig.WG_MTU
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("port", 0)
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "tun0")
                put("mtu", mtu)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                put("routeOnly", true)
            })
        })
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        outbounds.put(JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", "127.0.0.1")
                    put("port", localPort)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", VK_TURN_BRIDGE_UUID)
                        put("encryption", "none")
                    }))
                }))
            })
            put("streamSettings", JSONObject().apply {
                put("network", "tcp")
                put("security", "none")
            })
        })
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        })
        root.put("outbounds", outbounds)

        val rules = JSONArray()
        if (bypassDomains.isNotEmpty()) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("domain", JSONArray(bypassDomains))
            })
        }
        if (bypassIps.isNotEmpty()) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray(bypassIps))
            })
        }
        rules.put(JSONObject().apply {
            put("type", "field")
            put("outboundTag", "proxy")
            put("network", "tcp,udp")
        })

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", rules)
        })

        return root.toString()
    }
}
