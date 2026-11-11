package ru.souz.mcp

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The same policy validates literal URLs and the addresses actually used by the socket connector. */
class McpNetworkPolicy(
    privateEndpoints: Set<String> = emptySet(),
    private val resolver: Dns = Dns.SYSTEM,
) {
    private val endpointsByHost = privateEndpoints.map(::parseEndpoint).groupBy { normalizeHost(it.host) }

    fun validateUrl(url: String) {
        val parsed = parseEndpoint(url)
        val host = normalizeHost(parsed.host)
        endpointsByHost[host]?.let { allowed ->
            require(parsed in allowed) { "MCP endpoint is not in the operator allowlist." }
            return
        }
        require(host != "localhost" && !host.endsWith(".localhost")) { "MCP destination is not public." }
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
            require(isPublic(InetAddress.getByName(host))) { "MCP destination is not public." }
        }
    }

    internal fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver.lookup(hostname)
        if (addresses.isEmpty() || (normalizeHost(hostname) !in endpointsByHost && addresses.any { !isPublic(it) })) {
            throw UnknownHostException("MCP destination is not public.")
        }
        return addresses
    }

    companion object {
        fun fromEnvironment(): McpNetworkPolicy = McpNetworkPolicy(
            System.getenv("MCP_PRIVATE_ENDPOINT_ALLOWLIST").orEmpty().split(',')
                .map(String::trim).filter(String::isNotEmpty).toSet()
        )

        private fun normalizeHost(host: String): String = host.lowercase().removeSuffix(".")

        private fun parseEndpoint(url: String): HttpUrl {
            require(url.length <= 2_048) { "MCP URL exceeds the length limit." }
            return url.toHttpUrl().also {
                require(it.username.isEmpty() && it.password.isEmpty() && it.fragment == null) {
                    "MCP URLs must not contain user information or fragments."
                }
            }
        }

        internal fun isPublic(address: InetAddress): Boolean {
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 16) {
                return b[0] in 0x20..0x3f &&
                    !(b[0] == 0x20 && b[1] == 0x01 && b[2] < 2) &&
                    !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) &&
                    !(b[0] == 0x20 && b[1] == 0x02) &&
                    !(b[0] == 0x3f && b[1] == 0xff && b[2] < 16)
            }
            return b[0] != 0 && b[0] != 10 && b[0] != 127 && b[0] < 224 &&
                !(b[0] == 100 && b[1] in 64..127) &&
                !(b[0] == 169 && b[1] == 254) &&
                !(b[0] == 172 && b[1] in 16..31) &&
                !(b[0] == 192 && ((b[1] == 0 && (b[2] == 0 || b[2] == 2)) ||
                    (b[1] == 88 && b[2] == 99) || b[1] == 168)) &&
                !(b[0] == 198 && (b[1] in 18..19 || (b[1] == 51 && b[2] == 100))) &&
                !(b[0] == 203 && b[1] == 0 && b[2] == 113)
        }
    }
}
