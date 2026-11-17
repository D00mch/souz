package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.polza.MissingPolzaVoiceKeyException
import ru.souz.llms.polza.PolzaVoiceAPI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolzaVoiceAPITest {
    @Test
    fun `cold and subsequent captures each upload once with current key timeout and intact WAV`() = runTest {
        var key = " pza_fixture_first "
        var timeout = 1_234L
        val settings = mockk<SettingsProvider> {
            every { polzaApiKey } answers { key }
            every { requestTimeoutMillis } answers { timeout }
        }
        val pcm = byteArrayOf(1, 2, 3, 4, -1, -2)
        val engine = MockEngine { request ->
            assertEquals("https://polza.ai/api/v1/audio/transcriptions", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer ${key.trim()}", request.headers[HttpHeaders.Authorization])
            assertEquals(timeout, request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            val body = request.body.toByteArray()
            val wire = body.toString(Charsets.ISO_8859_1)
            assertTrue(wire.contains("name=\"model\""))
            assertTrue(wire.contains("\r\n\r\nai-sage/gigaam-v3\r\n"))
            assertTrue(wire.contains("filename=\"capture.wav\""))
            assertTrue(wire.contains("Content-Type: audio/wav"))
            assertFalse(wire.contains("diariz", ignoreCase = true))
            val start = wire.indexOf("RIFF")
            assertTrue(start >= 0)
            val wav = body.copyOfRange(start, start + 44 + pcm.size)
            val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
            assertEquals(1, header.getShort(22).toInt())
            assertEquals(16_000, header.getInt(24))
            assertEquals(16, header.getShort(34).toInt())
            assertEquals(pcm.size, header.getInt(40))
            assertContentEquals(pcm, wav.copyOfRange(44, wav.size))
            respond("""{"text":"  Привет, мир!  ","usage":{"seconds":1}}""", headers = jsonHeaders)
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            val api = PolzaVoiceAPI(settings, client)
            assertEquals("Привет, мир!", api.recognize(pcm))
            assertEquals(1, engine.requestHistory.size)
            key = "pza_fixture_rotated"
            timeout = 2_345L
            assertEquals("Привет, мир!", api.recognize(pcm))
            assertEquals(2, engine.requestHistory.size)
        }
    }

    @Test
    fun `missing key fails before upload`() = runTest {
        val engine = MockEngine { error("No request expected") }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            val settings = mockk<SettingsProvider> { every { polzaApiKey } returns "  " }
            assertFailsWith<MissingPolzaVoiceKeyException> { PolzaVoiceAPI(settings, client).recognize(byteArrayOf()) }
            assertTrue(engine.requestHistory.isEmpty())
        }
    }

    @Test
    fun `provider errors and malformed responses fail without retry or leaking response data`() = runTest {
        for ((status, body) in listOf(
            HttpStatusCode.Unauthorized to "pza_fixture_secret",
            HttpStatusCode.TooManyRequests to "pza_fixture_secret",
            HttpStatusCode.InternalServerError to "pza_fixture_secret",
            HttpStatusCode.OK to """{"error":"pza_fixture_secret"}""",
            HttpStatusCode.OK to "{}",
            HttpStatusCode.OK to """{"text":null}""",
        )) {
            val engine = MockEngine { respond(body, status, jsonHeaders) }
            HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
                val failure = assertFailsWith<IllegalStateException> { PolzaVoiceAPI(settings(), client).recognize(byteArrayOf()) }
                assertFalse(failure.message.orEmpty().contains("pza_fixture_secret"))
                assertEquals(1, engine.requestHistory.size)
            }
        }
    }

    @Test
    fun `empty transcript stays empty and cancellation propagates`() = runTest {
        val engine = MockEngine { respond("""{"text":" "}""", headers = jsonHeaders) }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            assertEquals("", PolzaVoiceAPI(settings(), client).recognize(byteArrayOf()))
        }
        var attempts = 0
        val cancelled = MockEngine { attempts++; throw CancellationException("fixture cancellation") }
        HttpClient(cancelled) { providerHttpClientDefaults() }.use { client ->
            assertFailsWith<CancellationException> { PolzaVoiceAPI(settings(), client).recognize(byteArrayOf()) }
            assertEquals(1, attempts)
        }
    }

    private fun settings(): SettingsProvider = mockk {
        every { polzaApiKey } returns "pza_fixture"
        every { requestTimeoutMillis } returns 1_000L
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
}
