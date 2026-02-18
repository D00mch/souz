package server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import ru.souz.server.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalServerTest {

    @Test
    fun `test successful agent command`() = testApplication {
        application {
            configureServer(MockAgentNode())
        }

        val response = client.post("/api/agent/command") {
            contentType(ContentType.Application.Json)
            setBody("""{"text": "Hello from test"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Mock response to: Hello from test"))
    }

    @Test
    fun `test error handling when agent throws`() = testApplication {
        val failingAgent = object : AgentNode {
            override suspend fun processRequest(input: String): AgentResult {
                throw RuntimeException("Test error message")
            }
        }

        application {
            configureServer(failingAgent)
        }

        val response = client.post("/api/agent/command") {
            contentType(ContentType.Application.Json)
            setBody("""{"text": "This will fail"}""")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Test error message"))
    }

    @Test
    fun `test health check endpoint`() = testApplication {
        application {
            configureServer(MockAgentNode())
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }
}
