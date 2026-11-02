package ru.souz.llms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import kotlin.test.*
import ru.souz.db.SettingsProvider
import ru.souz.llms.codex.*
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.runtime.*

class ImageGatewayTest {
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
    private val base64 = Base64.getEncoder().encodeToString(png)
    private val settings = mockk<SettingsProvider>(relaxed = true).also {
        every { it.requestTimeoutMillis } returns 5_000L
        every { it.codexAccountId } returns "account"
    }

    @Test fun `OpenAI generation and editing use the same route and preserve source bytes`() = runTest {
        val requests = mutableListOf<Pair<String, ByteArray>>()
        HttpClient(MockEngine { request ->
            assertEquals("Bearer key", request.headers[HttpHeaders.Authorization])
            requests += request.url.toString() to request.body.toByteArray()
            respond("""{"data":[{"b64_json":"$base64"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }.use { http ->
            val gateway = OpenAIImageGenerationGateway(settings, http, { "key" }, "https://images.example/v1")
            for (sources in listOf(emptyList(), listOf(ImageSource.read(png), ImageSource.read(png)))) {
                val result = gateway.generate(ImageGenerationInput("draw", model = "selected-model", images = sources))
                assertContentEquals(png, result.bytes)
                assertEquals("selected-model", result.model)
            }
        }
        assertEquals("https://images.example/v1/images/generations", requests[0].first)
        assertEquals("selected-model", restJsonMapper.readTree(requests[0].second)["model"].asText())
        assertEquals("https://images.example/v1/images/edits", requests[1].first)
        val multipart = requests[1].second.toString(Charsets.ISO_8859_1)
        assertTrue(multipart.contains(png.toString(Charsets.ISO_8859_1)))
        assertTrue(multipart.contains("source-0.png") && multipart.contains("source-1.png"))
    }

    @Test fun `Codex requires one image and completed stream without retrying ambiguous outcomes`() = runTest {
        val oauth = mockk<CodexOAuthService>()
        coEvery { oauth.refreshTokenIfNeeded() } returns "key"
        val image = "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"result\":\"$base64\"}}\n\n"
        val complete = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
        for ((stream, success) in listOf(image + complete to true, image to false, image + image + complete to false,
            image + "data: {\"type\":\"response.failed\"}\n\n" to false)) {
            var calls = 0
            HttpClient(MockEngine { request ->
                calls++
                val body = restJsonMapper.readTree(request.body.toByteArray())
                assertEquals("edit", body["tools"][0]["action"].asText())
                assertEquals("data:image/png;base64,$base64", body["input"][0]["content"][1]["image_url"].asText())
                respond(stream, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }) { providerHttpClientDefaults() }.use { http ->
                val gateway = CodexImageGenerationGateway(settings, oauth, http)
                val input = ImageGenerationInput("edit", "sol-test", images = listOf(ImageSource.read(png)))
                if (success) assertContentEquals(png, gateway.generate(input).bytes)
                else assertFailsWith<IllegalStateException> { gateway.generate(input) }
            }
            assertEquals(1, calls)
        }
    }

    @Test fun `source limits reject malformed images and vision resizes without changing originals`() = runTest {
        assertFailsWith<IllegalArgumentException> { ImageSource.read(ByteArray(ImageSource.MAX_BYTES + 1)) }
        for (bytes in listOf(byteArrayOf(1, 2, 3), png.copyOf(33))) {
            assertFailsWith<InvalidImageException> { ImageSource.read(bytes) }
        }
        assertFailsWith<IllegalArgumentException> { ImageGenerationInput("edit", images = List(6) { ImageSource.read(png) }) }
        for (width in listOf(3000, 4096, 6001)) {
            val bytes = rgbaPng(width, 10, 8)
            val source = ImageSource.read(bytes)
            val resized = Base64.getDecoder().decode(source.forVision().substringAfter(','))
            assertEquals(2048, ImageIO.read(resized.inputStream()).width)
            assertContentEquals(bytes, source.bytes)
        }
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpeg", it) }.toByteArray()
        assertEquals("image/jpeg", ImageSource.read(jpeg).mimeType)
        val webp = Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA=")
        assertEquals("image/webp", ImageSource.read(webp).mimeType)
        assertTrue(ImageSource.read(webp).forVision().startsWith("data:image/webp;base64,"))
        assertFailsWith<CancellationException> {
            withContext(Job().apply { cancel() }) { ImageSource.read(png) }
        }
    }

    @Test fun `decoded budget includes bit depth and parallel processing keeps rasters bounded`() = runTest {
        // Stream compressed scanlines so the regression fixture itself does not allocate the 320 MB raster.
        for (bytes in listOf(rgbaPng(8000, 5000, 16), rgbaPng(4000, 2200, 16))) {
            assertTrue(bytes.size < 1_000_000)
            assertEquals("image_decoded_size_exceeded", assertFailsWith<InvalidImageException> {
                ImageSource.read(bytes)
            }.code)
        }
        // The same dimensions fit at eight bits per channel. Exercise concurrent decoding with real ImageIO.
        val bytes = rgbaPng(4000, 2200, 8)
        val sources = List(6) { async(Dispatchers.Default) { ImageSource.read(bytes) } }.awaitAll()
        sources.forEach { source ->
            assertContentEquals(bytes, source.bytes)
            val vision = Base64.getDecoder().decode(source.forVision().substringAfter(','))
            assertEquals(2048, ImageIO.read(vision.inputStream()).width)
        }
    }

    private fun rgbaPng(width: Int, height: Int, bitDepth: Int): ByteArray {
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { output ->
            fun chunk(type: String, data: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                output.writeInt(data.size)
                output.write(name)
                output.write(data)
                output.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
            }
            output.writeLong(0x89504e470d0a1a0aUL.toLong())
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use {
                it.writeInt(width)
                it.writeInt(height)
                it.write(byteArrayOf(bitDepth.toByte(), 6, 0, 0, 0))
            }
            chunk("IHDR", header.toByteArray())
            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { deflater ->
                val row = ByteArray(1 + width * 4 * (bitDepth / 8))
                repeat(height) { deflater.write(row) }
            }
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", byteArrayOf())
        }
        return result.toByteArray()
    }
}
