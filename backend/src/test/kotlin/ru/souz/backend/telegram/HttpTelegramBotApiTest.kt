package ru.souz.backend.telegram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class HttpTelegramBotApiTest {
    @Test
    fun `rich message preserves Markdown and reports Telegram rejection`() = runBlocking {
        val requests = LinkedBlockingQueue<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.put(exchange.requestURI.path to exchange.requestBody.reader().readText())
            val reply = if (exchange.requestURI.path.contains("rejected")) {
                """{"ok":false,"error_code":400,"description":"Invalid rich message"}"""
            } else """{"ok":true,"result":{"message_id":1}}"""
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val api = HttpTelegramBotApi("http://127.0.0.1:${server.address.port}")
            val markdown = "**Сапсан** [Билеты](https://example.com?a=1&b=2)\n`<code>` 😀 \"quote\"" + "я".repeat(5_000)
            api.sendMessage("token", 42, markdown)
            val (path, body) = requests.remove()
            assertEquals("/bottoken/sendRichMessage", path)
            val form = body.split('&').associate {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
            }
            assertEquals(setOf("chat_id", "rich_message"), form.keys)
            assertEquals("42", form["chat_id"])
            val content = jacksonObjectMapper().readTree(form.getValue("rich_message"))
            assertEquals(markdown, content.path("markdown").asText())
            assertEquals(1, content.size())
            val failure = assertFailsWith<TelegramBotApiHttpException> {
                api.sendMessage("rejected", 42, "**bad")
            }
            assertEquals("sendRichMessage", failure.methodName)
            assertEquals(400, failure.telegramErrorCode)
        } finally {
            server.stop(0)
        }
    }
}
