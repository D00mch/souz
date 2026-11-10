package ru.souz.mcp

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpNetworkPolicyTest {
    @Test
    fun `literal destinations reject reserved IPv4 IPv6 and alternative numeric forms`() {
        val policy = McpNetworkPolicy()
        val blocked = listOf("0.0.0.0", "10.0.0.1", "100.64.1.1", "127.0.0.1", "127.1", "2130706433",
            "169.254.169.254", "172.16.0.1", "192.168.0.1", "198.18.0.1", "224.0.0.1",
            "[::]", "[::1]", "[::ffff:127.0.0.1]", "[fc00::1]", "[fe80::1]", "[2002:7f00:1::]",
            "[64:ff9b::7f00:1]", "[2001:db8::1]", "localhost", "x.localhost")
        blocked.forEach { host -> assertFailsWith<IllegalArgumentException>(host) { policy.validateUrl("http://$host/mcp") } }
        listOf("1.1.1.1", "[2606:4700:4700::1111]", "mcp.example.test").forEach {
            policy.validateUrl("https://$it/mcp")
        }
    }

    @Test
    fun `DNS checks every answer on each lookup and private exceptions bind exact endpoints`() {
        var answers = listOf(InetAddress.getByName("1.1.1.1"))
        val dns = Dns { answers }
        val policy = McpNetworkPolicy(resolver = dns)
        assertEquals(answers, policy.lookup("server.example"))
        answers = answers + InetAddress.getByName("127.0.0.1")
        assertFailsWith<UnknownHostException> { policy.lookup("server.example") }
        val allowed = McpNetworkPolicy(setOf("https://server.example/mcp"), dns)
        assertEquals(answers, allowed.lookup("server.example"))
        assertFailsWith<UnknownHostException> { allowed.lookup("other.server.example") }
        allowed.validateUrl("https://server.example/mcp")
        listOf("https://server.example/admin", "https://server.example:8443/mcp",
            "http://server.example/mcp", "https://server.example/mcp?other=1").forEach {
            assertFailsWith<IllegalArgumentException> { allowed.validateUrl(it) }
        }
        assertTrue(McpNetworkPolicy(setOf("http://127.0.0.1/mcp")).let {
            it.validateUrl("http://127.0.0.1/mcp")
            true
        })
    }
}
