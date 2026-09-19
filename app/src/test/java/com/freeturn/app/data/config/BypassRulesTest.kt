package com.freeturn.app.data.config

import com.freeturn.app.domain.proxy.excludeLanFromAllowedIps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassRulesTest {

    @Test
    fun `parseBypassRules parses urls, domains, ips and cidrs`() {
        val raw = """
            # This is a comment
            https://kinopoisk.ru/film/123/
            http://ozon.ru/
            *.yandex.ru
            1.2.3.4
            95.163.0.0/16
            
            # Another comment
            vk.com
            1.2.3.4
        """.trimIndent()

        val parsed = parseBypassRules(raw)
        assertEquals(listOf("kinopoisk.ru", "ozon.ru", "*.yandex.ru", "vk.com"), parsed.domains)
        assertEquals(listOf("1.2.3.4/32", "95.163.0.0/16"), parsed.cidrs)
    }

    @Test
    fun `normalizeBypassEntry formats ips and domains cleanly`() {
        assertEquals("kinopoisk.ru", normalizeBypassEntry("https://kinopoisk.ru/test"))
        assertEquals("1.2.3.4/32", normalizeBypassEntry("1.2.3.4"))
        assertEquals("192.168.0.0/16", normalizeBypassEntry("192.168.0.0/16"))
        assertEquals("*.ru", normalizeBypassEntry("*.RU"))
        assertEquals("", normalizeBypassEntry("# comment"))
        assertEquals("", normalizeBypassEntry("   "))
    }

    @Test
    fun `addBypassEntry and removeBypassEntry update rules list`() {
        var rules = ""
        rules = addBypassEntry(rules, "kinopoisk.ru")
        rules = addBypassEntry(rules, "1.2.3.4")
        // duplicate should not be added
        rules = addBypassEntry(rules, "KINOPOISK.RU")
        assertEquals("kinopoisk.ru\n1.2.3.4/32", rules)

        rules = removeBypassEntry(rules, "kinopoisk.ru")
        assertEquals("1.2.3.4/32", rules)

        rules = removeBypassEntry(rules, "1.2.3.4")
        assertEquals("", rules)
    }

    @Test
    fun `addDefaultBypassRules includes Russian service domains`() {
        val rules = addDefaultBypassRules("")
        assertTrue(rules.contains("*.ru"))
        assertTrue(rules.contains("yandex.ru"))
        assertTrue(rules.contains("gosuslugi.ru"))
        assertTrue(rules.contains("vk.com"))
    }

    @Test
    fun `custom cidr is excluded from allowed ips in WireGuard`() {
        val allowedIps = "0.0.0.0/0"
        val customCidrs = listOf("95.163.0.0/16", "1.2.3.4/32")
        val result = excludeLanFromAllowedIps(allowedIps, customExcludedCidrs = customCidrs)
            .split(",")
            .map { it.trim() }

        val ranges = result.map(::parseCidr)
        // 95.163.1.1 and 1.2.3.4 should NOT be covered
        assertFalse(ranges.any { ipToLong("95.163.1.1") in it })
        assertFalse(ranges.any { ipToLong("1.2.3.4") in it })
        // Public IP outside exclusion should be covered
        assertTrue(ranges.any { ipToLong("8.8.8.8") in it })
    }

    @Test
    fun `injectBypassRouting injects direct outbound and bypass rules into Xray JSON`() {
        val rawXray = """
            {
                "outbounds": [
                    { "protocol": "vless", "tag": "proxy" }
                ],
                "routing": {
                    "rules": [
                        { "type": "field", "outboundTag": "proxy", "network": "tcp,udp" }
                    ]
                }
            }
        """.trimIndent()

        val bypass = ParsedBypassRules(
            domains = listOf("kinopoisk.ru", "*.yandex.ru"),
            cidrs = listOf("1.2.3.4/32")
        )

        val injected = com.freeturn.app.service.injectBypassRouting(rawXray, bypass)
        val obj = org.json.JSONObject(injected)
        val outbounds = obj.getJSONArray("outbounds")
        val hasFreedom = (0 until outbounds.length()).any {
            outbounds.getJSONObject(it).optString("protocol") == "freedom" &&
                outbounds.getJSONObject(it).optString("tag") == "direct"
        }
        assertTrue("Must have freedom outbound with tag direct", hasFreedom)

        val rules = obj.getJSONObject("routing").getJSONArray("rules")
        val firstRule = rules.getJSONObject(0)
        assertEquals("direct", firstRule.getString("outboundTag"))
        val domainArr = firstRule.getJSONArray("domain")
        assertEquals(2, domainArr.length())
        assertEquals("domain:kinopoisk.ru", domainArr.getString(0))
        assertEquals("domain:yandex.ru", domainArr.getString(1))

        val ipArr = firstRule.getJSONArray("ip")
        assertEquals(1, ipArr.length())
        assertEquals("1.2.3.4/32", ipArr.getString(0))
    }

    private fun ipToLong(ip: String): Long =
        ip.split(".").map { it.toLong() }.fold(0L) { acc, o -> (acc shl 8) or o }

    private fun parseCidr(cidr: String): LongRange {
        val (ipPart, prefixPart) = cidr.split("/")
        val base = ipToLong(ipPart)
        val hostBits = 32 - prefixPart.toInt()
        val mask = if (hostBits == 0) 0L else (1L shl hostBits) - 1
        return (base and mask.inv())..(base or mask)
    }
}
