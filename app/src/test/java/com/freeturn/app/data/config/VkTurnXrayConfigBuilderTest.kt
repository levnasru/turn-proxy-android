package com.freeturn.app.data.config

import com.freeturn.app.data.CoreArgs
import com.freeturn.app.data.server.ServerOpts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VkTurnXrayConfigBuilderTest {

    @Test
    fun `build generates valid xray configuration with tun inbound and vless outbound`() {
        val jsonStr = VkTurnXrayConfigBuilder.build(
            bypassDomains = listOf("*.ru", "yandex.ru"),
            bypassIps = listOf("192.168.0.0/16", "1.2.3.4/32"),
            localPort = 9000,
            mtu = 1050
        )

        val root = JSONObject(jsonStr)
        assertTrue(root.has("inbounds"))
        assertTrue(root.has("outbounds"))
        assertTrue(root.has("routing"))

        val inbounds = root.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        val inbound = inbounds.getJSONObject(0)
        assertEquals("tun", inbound.getString("protocol"))
        val settings = inbound.getJSONObject("settings")
        assertEquals("tun0", settings.getString("name"))
        assertEquals(1050, settings.getInt("mtu"))

        val outbounds = root.getJSONArray("outbounds")
        val proxy = outbounds.getJSONObject(0)
        assertEquals("proxy", proxy.getString("tag"))
        assertEquals("vless", proxy.getString("protocol"))
        val vnext = proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals("127.0.0.1", vnext.getString("address"))
        assertEquals(9000, vnext.getInt("port"))
        val user = vnext.getJSONArray("users").getJSONObject(0)
        assertEquals(VkTurnXrayConfigBuilder.VK_TURN_BRIDGE_UUID, user.getString("id"))

        val rules = root.getJSONObject("routing").getJSONArray("rules")
        assertEquals(3, rules.length())
        val domainRule = rules.getJSONObject(0)
        assertEquals("direct", domainRule.getString("outboundTag"))
        assertEquals(2, domainRule.getJSONArray("domain").length())

        val ipRule = rules.getJSONObject(1)
        assertEquals("direct", ipRule.getString("outboundTag"))
        assertEquals(2, ipRule.getJSONArray("ip").length())

        val defaultRule = rules.getJSONObject(2)
        assertEquals("proxy", defaultRule.getString("outboundTag"))
    }

    @Test
    fun `coreArgs client rewrites port to 56003 and enables tcp bond when VK_XRAY`() {
        val cfg = ClientConfig(
            serverAddress = "89.124.71.77:56002",
            tunnelTransport = TunnelTransport.VK_XRAY,
            localPort = "127.0.0.1:9000"
        )
        val srv = ServerOpts()
        val args = CoreArgs.client(cfg, srv)

        val peerIdx = args.indexOf("-peer")
        assertTrue(peerIdx >= 0)
        assertEquals("89.124.71.77:56003", args[peerIdx + 1])

        val modeIdx = args.indexOf("-mode")
        assertTrue(modeIdx >= 0)
        assertEquals("tcp", args[modeIdx + 1])
        assertTrue(args.contains("-bond"))
    }

    @Test
    fun `coreArgs client caps threads to 20 when VK_XRAY but preserves high threads for WireGuard`() {
        val cfgXray = ClientConfig(
            threads = 39,
            tunnelTransport = TunnelTransport.VK_XRAY
        )
        val argsXray = CoreArgs.client(cfgXray, ServerOpts())
        val nIdxXray = argsXray.indexOf("-n")
        assertTrue(nIdxXray >= 0)
        assertEquals("20", argsXray[nIdxXray + 1])

        val cfgWg = ClientConfig(
            threads = 39,
            tunnelTransport = TunnelTransport.WIREGUARD
        )
        val argsWg = CoreArgs.client(cfgWg, ServerOpts())
        val nIdxWg = argsWg.indexOf("-n")
        assertTrue(nIdxWg >= 0)
        assertEquals("39", argsWg[nIdxWg + 1])
    }
}
