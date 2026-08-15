package com.freeturn.app.domain.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WgLanBypassTest {

    /** Каждая нота исходного диапазона в разрешённом (не покрыта приватными вырезами). */
    private fun assertCovered(result: List<String>, vararg ips: String) {
        val ranges = result.map { cidr -> cidr.let(::parseCidr) }
        for (ip in ips) {
            val v = ipToLong(ip)
            assertTrue("$ip should be covered by $result", ranges.any { v in it })
        }
    }

    private fun assertNotCovered(result: List<String>, vararg ips: String) {
        val ranges = result.map(::parseCidr)
        for (ip in ips) {
            val v = ipToLong(ip)
            assertFalse("$ip should NOT be covered by $result", ranges.any { v in it })
        }
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

    @Test
    fun `full route excludes RFC1918 and link-local but keeps public internet`() {
        val result = excludeLanFromAllowedIps("0.0.0.0/0").split(",").map { it.trim() }
        assertNotCovered(result, "192.168.1.1", "10.0.0.1", "172.16.0.1", "169.254.1.1", "127.0.0.1")
        assertCovered(result, "8.8.8.8", "1.1.1.1", "194.169.163.64")
    }

    @Test
    fun `family split-CIDR list still excludes private ranges`() {
        // Реальный AllowedIPs из прод-конфига (см. переписку) - объединение диапазонов,
        // покрывающее почти всё IPv4-пространство без явного "0.0.0.0/0".
        val allowedIps = "0.0.0.0/2, 64.0.0.0/4, 80.0.0.0/5, 88.0.0.0/7, 90.0.0.0/9, " +
            "90.128.0.0/12, 90.144.0.0/13, 90.152.0.0/14, 90.157.0.0/16, 90.158.0.0/15, " +
            "90.160.0.0/11, 90.192.0.0/10, 91.0.0.0/8, 92.0.0.0/7, 94.0.0.0/8, 95.0.0.0/9, " +
            "95.128.0.0/11, 95.160.0.0/15, 95.162.0.0/16, 95.164.0.0/14, 95.168.0.0/13, " +
            "95.176.0.0/12, 95.192.0.0/10, 96.0.0.0/3, 128.0.0.0/1"
        val result = excludeLanFromAllowedIps(allowedIps).split(",").map { it.trim() }
        assertNotCovered(result, "192.168.1.1", "10.0.0.1", "172.16.5.5")
        // 194.169.163.64 - реальный Reality-relay из живого конфига (128.0.0.0/1).
        // 95.100.1.1 - внутри 95.0.0.0/9, однозначно покрыт (у списка есть дыра на
        // 95.163.0.0/16 - похоже, намеренно вырезанный диапазон собственных TURN-relay
        // IP семьи, чтобы не заворачивать их трафик обратно в туннель; не трогаем).
        assertCovered(result, "194.169.163.64", "95.100.1.1")
    }

    @Test
    fun `ipv6 entries pass through untouched`() {
        val result = excludeLanFromAllowedIps("0.0.0.0/0, ::/0").split(",").map { it.trim() }
        assertTrue("::/0" in result)
    }

    @Test
    fun `already-narrow allowed range still yields something for non-private ip`() {
        val result = excludeLanFromAllowedIps("194.169.163.64/32")
        assertEquals("194.169.163.64/32", result)
    }

    @Test
    fun `pure private range collapses to empty`() {
        assertEquals("", excludeLanFromAllowedIps("192.168.0.0/16"))
    }
}
