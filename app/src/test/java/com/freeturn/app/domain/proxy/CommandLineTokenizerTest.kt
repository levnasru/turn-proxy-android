package com.freeturn.app.domain.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandLineTokenizerTest {

    @Test
    fun `empty string returns empty list`() {
        assertEquals(emptyList<String>(), CommandLineTokenizer.tokenize(""))
        assertEquals(emptyList<String>(), CommandLineTokenizer.tokenize("   "))
    }

    @Test
    fun `simple whitespace separation`() {
        val result = CommandLineTokenizer.tokenize("vkturn -peer 1.2.3.4:56000 -n 10")
        assertEquals(listOf("vkturn", "-peer", "1.2.3.4:56000", "-n", "10"), result)
    }

    @Test
    fun `double quotes preserve spaces and strip quotes`() {
        val result = CommandLineTokenizer.tokenize("vkturn -peer \"1.2.3.4:56000\" -links \"url1, url2\"")
        assertEquals(listOf("vkturn", "-peer", "1.2.3.4:56000", "-links", "url1, url2"), result)
    }

    @Test
    fun `single quotes preserve spaces and strip quotes`() {
        val result = CommandLineTokenizer.tokenize("vkturn -peer '1.2.3.4:56000' -link 'https://vk.ru/call/join/abc'")
        assertEquals(listOf("vkturn", "-peer", "1.2.3.4:56000", "-link", "https://vk.ru/call/join/abc"), result)
    }

    @Test
    fun `escaped characters and mixed quotes`() {
        val result = CommandLineTokenizer.tokenize("vkturn -flag \"quoted \\\"value\\\"\" -other\\ flag")
        assertEquals(listOf("vkturn", "-flag", "quoted \"value\"", "-other flag"), result)
    }
}
