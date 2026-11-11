package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ru.souz.backend.http.BackendHttpRoutes

class BackendSettingsLimitsE2eTest {
    @Test
    fun `explicit tool selections bound count and bytes without requiring a plugin runtime`() =
        backendE2eTest("e2e_settings_tool_limits") {
            val user = UUID.randomUUID().toString()
            val shortNames = (0 until 512).map { "plugin.future.t$it" }
            val longNames = (0 until 256).map { longToolName(it) }
            for ((names, extra) in listOf(shortNames to "plugin.future.extra", longNames to longToolName(256))) {
                val accepted = client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user)
                    jsonBody(json.writeValueAsString(mapOf("enabledTools" to names, "narrateSteps" to false)))
                }
                assertEquals(HttpStatusCode.OK, accepted.status)
                assertEquals(names.toSet(), accepted.jsonBody()["settings"]["enabledTools"].map { it.asText() }.toSet())
                val oversized = names + extra
                for (onboarding in listOf(false, true)) {
                    val rejected = client.request(if (onboarding) BackendHttpRoutes.ONBOARDING_COMPLETE else BackendHttpRoutes.SETTINGS) {
                        method = if (onboarding) HttpMethod.Post else HttpMethod.Patch
                        trusted(user)
                        jsonBody(json.writeValueAsString(mapOf("enabledTools" to oversized, "narrateSteps" to true)))
                    }
                    assertEquals(HttpStatusCode.BadRequest, rejected.status)
                    assertEquals("invalid_request", rejected.jsonBody()["error"]["code"].asText())
                    val after = client.get(BackendHttpRoutes.SETTINGS) { trusted(user) }.jsonBody()["settings"]
                    assertEquals(names.toSet(), after["enabledTools"].map { it.asText() }.toSet())
                    assertFalse(after["narrateSteps"].asBoolean())
                }
            }
            val chat = createPublicChat(user)
            val turn = client.post(BackendHttpRoutes.chatMessages(chat)) { trusted(user); jsonBody("""{"content":"hello"}""") }
            assertEquals(HttpStatusCode.OK, turn.status)
            val execution = backend.executionRepository.listByChat(user, UUID.fromString(chat)).single()
            assertEquals(longNames, json.readTree(execution.metadata.getValue("enabledTools")).map { it.asText() })
            backend.awaitExecution(execution.id)
        }

    @Test
    fun `settings bodies are bounded in bytes with and without content length`() =
        backendE2eTest("e2e_settings_body_limits") {
            for (onboarding in listOf(false, true)) {
                val user = UUID.randomUUID().toString()
                for (knownLength in listOf(true, false)) {
                    for ((body, expected) in listOf(
                        ("{}" + " ".repeat(65_534)) to HttpStatusCode.OK,
                        ("{}" + " ".repeat(65_535)) to HttpStatusCode.PayloadTooLarge,
                        json.writeValueAsString(mapOf("ignored" to "я".repeat(40_000))) to HttpStatusCode.PayloadTooLarge,
                    )) {
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val response = client.request(if (onboarding) BackendHttpRoutes.ONBOARDING_COMPLETE else BackendHttpRoutes.SETTINGS) {
                            method = if (onboarding) HttpMethod.Post else HttpMethod.Patch
                            trusted(user)
                            setBody(object : OutgoingContent.ReadChannelContent() {
                                override val contentType = ContentType.Application.Json
                                override val contentLength = bytes.size.toLong().takeIf { knownLength }
                                override fun readFrom() = ByteReadChannel(bytes)
                            })
                        }
                        assertEquals(expected, response.status, "onboarding=$onboarding knownLength=$knownLength bytes=${bytes.size}")
                        if (expected == HttpStatusCode.PayloadTooLarge) {
                            assertEquals("settings_payload_too_large", response.jsonBody()["error"]["code"].asText())
                        }
                    }
                }
                // A receiver must stop at the byte limit without waiting for the rest of an unfinished body.
                coroutineScope {
                    val body = ByteChannel(autoFlush = true)
                    val writer = launch { body.writeFully(ByteArray(65_537) { ' '.code.toByte() }) }
                    try {
                        val response = withTimeout(5_000) {
                            client.request(if (onboarding) BackendHttpRoutes.ONBOARDING_COMPLETE else BackendHttpRoutes.SETTINGS) {
                                method = if (onboarding) HttpMethod.Post else HttpMethod.Patch
                                trusted(user)
                                setBody(object : OutgoingContent.ReadChannelContent() {
                                    override val contentType = ContentType.Application.Json
                                    override fun readFrom() = body
                                })
                            }
                        }
                        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
                    } finally {
                        body.cancel(null)
                        writer.cancelAndJoin()
                    }
                }
            }
        }

    @Test
    fun `oversized legacy selections cannot create executions and can be replaced`() =
        backendE2eTest("e2e_settings_legacy_limits") {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            client.get(BackendHttpRoutes.SETTINGS) { trusted(user) }
            for (names in listOf((0..512).map { "plugin.future.t$it" }, (0..256).map { longToolName(it) })) {
                sql { connection ->
                    connection.prepareStatement("update user_settings set settings_json = jsonb_set(settings_json, '{enabledTools}', ?::jsonb) where user_id = ?").use {
                        it.setString(1, json.writeValueAsString(names))
                        it.setString(2, user)
                        assertEquals(1, it.executeUpdate())
                    }
                }
                val rejected = client.post(BackendHttpRoutes.chatMessages(chat)) { trusted(user); jsonBody("""{"content":"hello"}""") }
                assertEquals(HttpStatusCode.BadRequest, rejected.status)
                assertEquals(emptyList(), backend.executionRepository.listByChat(user, UUID.fromString(chat)))
            }
            val replacement = client.patch(BackendHttpRoutes.SETTINGS) { trusted(user); jsonBody("""{"enabledTools":["plugin.future.report"]}""") }
            assertEquals(HttpStatusCode.OK, replacement.status)
            assertEquals(listOf("plugin.future.report"), replacement.jsonBody()["settings"]["enabledTools"].map { it.asText() })
        }
}

private fun longToolName(index: Int): String = "plugin.${"a".repeat(39)}.t${index.toString().padStart(16, '0')}"
