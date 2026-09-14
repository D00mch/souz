package ru.souz.backend.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.spi.LoggingEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class BackendLoggingTest {
    @Test
    fun `JSON logs contain bounded MDC fields and nested scopes restore IDs after failure`() = runBlocking {
        val original = MDC.getCopyOfContextMap()
        withContext(backendLogContext("chatId" to "chat", "clientRequestId" to "client\n\u202erequest")) {
            try {
                withBackendLogContext("threadId" to "thread", "toolCallId" to "x".repeat(200), "clientRequestId" to null) {
                    withContext(Dispatchers.IO) {
                        yield()
                        val logger = LoggerFactory.getLogger("BackendLoggingTest") as Logger
                        val event = LoggingEvent(javaClass.name, logger, Level.INFO, "Processing", null, null)
                        val encoded = JsonEncoder().encode(event)
                        val fields = jacksonObjectMapper().readTree(encoded)["mdc"]
                        assertEquals("chat", fields["chatId"].asText())
                        assertEquals("thread", fields["threadId"].asText())
                        assertEquals("x".repeat(128), fields["toolCallId"].asText())
                        assertFalse(fields.has("clientRequestId"))
                    }
                    error("test failure")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: IllegalStateException) {
                assertEquals("test failure", expected.message)
            }
            assertEquals(mapOf("chatId" to "chat", "clientRequestId" to "client__request"), MDC.getCopyOfContextMap())
        }
        assertEquals(original, MDC.getCopyOfContextMap())
    }
}
