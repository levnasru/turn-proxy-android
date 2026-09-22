package com.freeturn.app.data

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.Provider
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.data.server.ServerOpts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreArgsTest {

    @Test
    fun vkXray_singleProvider_clampsThreadsTo20AndRewritesPort() {
        val cfg = ClientConfig(
            serverAddress = "89.124.71.77:56005",
            provider = Provider.HUB,
            hubUrl = "https://89.124.71.77:8445/turn-creds",
            threads = 50,
            useUdp = true,
            tunnelTransport = TunnelTransport.VK_XRAY
        )
        val args = CoreArgs.client(cfg, ServerOpts())

        val peerIdx = args.indexOf("-peer")
        assertTrue(peerIdx >= 0)
        assertEquals("89.124.71.77:56003", args[peerIdx + 1])

        val nIdx = args.indexOf("-n")
        assertTrue(nIdx >= 0)
        assertEquals("20", args[nIdx + 1])

        val modeIdx = args.indexOf("-mode")
        assertTrue(modeIdx >= 0)
        assertEquals("tcp", args[modeIdx + 1])

        assertTrue(args.contains("-bond"))
        assertFalse(args.contains("-transport"))
        assertFalse(args.contains("-batch"))
    }

    @Test
    fun vkXray_multiProvider_clampsThreadsPerProviderSoTotalIsAtMost20() {
        val cfg = ClientConfig(
            serverAddress = "89.124.71.77:56005",
            provider = Provider.HUB,
            hubUrl = "https://89.124.71.77:8445/turn-creds,https://89.124.71.77:8446/turn-creds,https://89.124.71.77:8448/turn-creds,https://89.124.71.77:8447/turn-creds",
            threads = 80,
            tunnelTransport = TunnelTransport.VK_XRAY
        )
        val args = CoreArgs.client(cfg, ServerOpts())

        val nIdx = args.indexOf("-n")
        assertTrue(nIdx >= 0)
        // 4 providers, MAX_VK_XRAY_THREADS = 20 -> 20 / 4 = 5 streams per provider (5 * 4 = 20 total)
        assertEquals("5", args[nIdx + 1])
    }

    @Test
    fun wireguard_multiProvider_dividesThreadsPerProviderSoTotalMatchesConfig() {
        val cfg = ClientConfig(
            serverAddress = "89.124.71.77:56005",
            provider = Provider.HUB,
            hubUrl = "https://89.124.71.77:8445/turn-creds,https://89.124.71.77:8446/turn-creds",
            threads = 80,
            useUdp = true,
            tunnelTransport = TunnelTransport.WIREGUARD
        )
        val args = CoreArgs.client(cfg, ServerOpts())

        val peerIdx = args.indexOf("-peer")
        assertTrue(peerIdx >= 0)
        assertEquals("89.124.71.77:56005", args[peerIdx + 1])

        val nIdx = args.indexOf("-n")
        assertTrue(nIdx >= 0)
        // 2 accounts, threads=80 -> -n 40 (40 * 2 = 80 total)
        assertEquals("40", args[nIdx + 1])
        assertEquals(80, CoreArgs.effectiveTotalStreams(cfg))

        val transportIdx = args.indexOf("-transport")
        assertTrue(transportIdx >= 0)
        assertEquals("udp", args[transportIdx + 1])
    }

    @Test
    fun wireguard_singleProvider_usesFullThreads() {
        val cfg = ClientConfig(
            serverAddress = "89.124.71.77:56005",
            provider = Provider.HUB,
            hubUrl = "https://89.124.71.77:8445/turn-creds",
            threads = 80,
            useUdp = true,
            tunnelTransport = TunnelTransport.WIREGUARD
        )
        val args = CoreArgs.client(cfg, ServerOpts())
        val nIdx = args.indexOf("-n")
        assertTrue(nIdx >= 0)
        assertEquals("80", args[nIdx + 1])
        assertEquals(80, CoreArgs.effectiveTotalStreams(cfg))
    }

    @Test
    fun wireguard_fourProviders_dividesTo20Each() {
        val cfg = ClientConfig(
            serverAddress = "89.124.71.77:56005",
            provider = Provider.HUB,
            hubUrl = "https://89.124.71.77:8445/turn-creds,https://89.124.71.77:8446/turn-creds,https://89.124.71.77:8447/turn-creds,https://89.124.71.77:8448/turn-creds",
            threads = 80,
            useUdp = true,
            tunnelTransport = TunnelTransport.WIREGUARD
        )
        val args = CoreArgs.client(cfg, ServerOpts())
        val nIdx = args.indexOf("-n")
        assertTrue(nIdx >= 0)
        assertEquals("20", args[nIdx + 1])
        assertEquals(80, CoreArgs.effectiveTotalStreams(cfg))
    }

    @Test
    fun adaptRawArgsForVkXray_mutatesFlagsCorrectly() {
        val raw = listOf(
            "-provider", "hub",
            "-hub-url", "https://89.124.71.77:8445/turn-creds,https://89.124.71.77:8446/turn-creds,https://89.124.71.77:8448/turn-creds,https://89.124.71.77:8447/turn-creds",
            "-peer", "89.124.71.77:56005",
            "-transport", "udp",
            "-batch", "8",
            "-n", "80",
            "-listen", "127.0.0.1:9000"
        )

        val adapted = CoreArgs.adaptRawArgsForVkXray(raw)

        val peerIdx = adapted.indexOf("-peer")
        assertTrue(peerIdx >= 0)
        assertEquals("89.124.71.77:56003", adapted[peerIdx + 1])

        val nIdx = adapted.indexOf("-n")
        assertTrue(nIdx >= 0)
        assertEquals("5", adapted[nIdx + 1])

        assertFalse(adapted.contains("-transport"))
        assertFalse(adapted.contains("-batch"))

        val modeIdx = adapted.indexOf("-mode")
        assertTrue(modeIdx >= 0)
        assertEquals("tcp", adapted[modeIdx + 1])
        assertTrue(adapted.contains("-bond"))
    }

    @Test
    fun clientId_withDeviceSuffix_appendsSuffixWhenMissing() {
        val cfg = ClientConfig(clientId = "testuser123")
        val args = CoreArgs.client(cfg, ServerOpts(), ownClientId = "abcdef12345678")
        val idx = args.indexOf("-client-id")
        assertTrue(idx >= 0)
        assertEquals("testuser123#abcdef12", args[idx + 1])
    }

    @Test
    fun clientId_alreadySuffixed_preservesExistingSuffix() {
        val cfg = ClientConfig(clientId = "testuser123#custom-device")
        val args = CoreArgs.client(cfg, ServerOpts(), ownClientId = "abcdef12345678")
        val idx = args.indexOf("-client-id")
        assertTrue(idx >= 0)
        assertEquals("testuser123#custom-device", args[idx + 1])
    }

    @Test
    fun clientId_blank_usesOwnClientId() {
        val cfg = ClientConfig(clientId = "")
        val args = CoreArgs.client(cfg, ServerOpts(), ownClientId = "abcdef12345678")
        val idx = args.indexOf("-client-id")
        assertTrue(idx >= 0)
        assertEquals("abcdef12345678", args[idx + 1])
    }
}

