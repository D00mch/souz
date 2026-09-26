package ru.souz.backend.vk

import com.sun.net.httpserver.HttpServer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ru.souz.tool.web.internal.WebToolSupport

class HttpVkBotApiTest {
    @Test
    fun `HTTP adapter handles VK envelopes encodes credentials and rejects failed sends`() = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val userAgents = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { call ->
            userAgents += call.requestHeaders.getFirst("User-Agent")
            val body = if (call.requestMethod == "GET") call.requestURI.rawQuery else call.requestBody.reader().readText()
            val form = body.split('&').associate { part ->
                val (key, value) = part.split('=', limit = 2)
                URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
            requests += call.requestURI.path to form
            val response = when {
                form["access_token"] == "rejected" -> """{"error":{"error_code":5,"error_msg":"secret-token"}}"""
                call.requestURI.path == "/groups.getById" -> if (form["access_token"] == "legacy") {
                    """{"response":[{"id":123,"name":"Group"}]}"""
                } else """{"response":{"groups":[{"id":123,"name":"Group","extra":true}]}}"""
                call.requestURI.path == "/poll" -> """{"ts":"next","updates":[{"type":"message_new","object":{"message":{"id":1,"from_id":7,"peer_id":7,"text":"hello","extra":true}}}]}"""
                else -> """{"response":1}"""
            }.toByteArray()
            call.sendResponseHeaders(if (form["access_token"] == "http-error") 503 else 200, response.size.toLong())
            call.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            HttpVkBotApi(url).use { api ->
                for (token in listOf("legacy", "token+&= я")) assertEquals(VkGroup(123, "Group"), api.getGroupInfo(token))
                val batch = api.pollLongPoll("$url/poll", "key+&", "old cursor", 25)
                assertEquals("hello", batch.updates.single().obj?.message?.text)
                assertEquals("old cursor", requests.last().second["ts"])
                api.sendMessage("token+&= я", 7, "hello & 😀")
                assertEquals("token+&= я", requests.last().second["access_token"])
                assertEquals("hello & 😀", requests.last().second["message"])
                assertTrue(requests.last().second.getValue("random_id").toInt() != 0)
                assertFalse(requests.last().second.containsKey("format_data"))
                val formatted = VkMarkdown.chunks("😀 **Билет** [ссылка](https://example.com?a=1&b=2)").single()
                api.sendMessage("token", 7, formatted.text, formatted.format)
                val sent = requests.last().second
                assertEquals("😀 Билет ссылка", sent["message"])
                val format = jacksonObjectMapper().readTree(sent.getValue("format_data"))
                assertEquals(1, format["version"].asInt())
                assertEquals(2, format["items"][0]["offset"].asInt())
                assertFalse(format["items"][0].has("url"))
                assertEquals("https://example.com?a=1&b=2", format["items"][1]["url"].asText())
                api.setActivity("token", 7, 123)
                assertEquals("123", requests.last().second["group_id"])
                val failure = assertFailsWith<VkBotApiException> { api.sendMessage("rejected", 7, "hello") }
                assertEquals(5, failure.code)
                assertFalse(failure.toString().contains("secret-token"))
                assertFailsWith<IOException> { api.sendMessage("http-error", 7, "hello") }
                assertEquals(setOf(WebToolSupport().userAgent), userAgents.toSet())
            }
        } finally {
            server.stop(0)
        }
    }
}
