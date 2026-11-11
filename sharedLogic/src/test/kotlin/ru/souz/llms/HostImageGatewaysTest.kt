package ru.souz.llms

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.local.LocalVisionGateway
import ru.souz.llms.runtime.ChatVisionGateway
import ru.souz.llms.runtime.ImageSource
import ru.souz.llms.runtime.VisionInput

class HostImageGatewaysTest {
    @Test
    fun `local vision passes ordered projected paths and removes them on every exit`() = runTest {
        val model = LLMModel.LocalQwen3_4B_Instruct_2507
        val settings = mockk<SettingsProvider> {
            every { gigaModel } returns model
            every { executionModelId(model) } returns model.alias
            every { contextSize } returns 24_000
        }
        val image = ImageSource.read(ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(3000, 10, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray())
        val api = mockk<LLMChatAPI>()
        val paths = mutableListOf<Path>()
        for (failure in listOf(null, IllegalStateException("failed"), CancellationException("cancelled"))) {
            coEvery { api.message(any()) } coAnswers {
                val request = firstArg<LLMRequest.Chat>()
                assertEquals(LlmProvider.LOCAL, request.provider)
                assertEquals(model.alias, request.model)
                assertEquals("compare", request.messages.single().content)
                paths.clear()
                paths.addAll(request.messages.single().attachments.orEmpty().map { Path.of(it) })
                assertEquals(2, paths.size)
                assertTrue(paths[0] != paths[1])
                paths.forEach { path ->
                    assertTrue(Files.isRegularFile(path))
                    assertEquals(2048, ImageIO.read(path.toFile()).width)
                }
                assertContentEquals(Files.readAllBytes(paths[0]), Files.readAllBytes(paths[1]))
                if (failure != null) throw failure
                LLMResponse.Chat.Ok(listOf(LLMResponse.Choice(
                    LLMResponse.Message("observed", LLMMessageRole.assistant, functionsStateId = null), 0, null,
                )), 0, model.alias, LLMResponse.Usage(0, 0, 0, 0))
            }
            val gateway = LocalVisionGateway(settings, api)
            val input = VisionInput(listOf(image, image), "compare")
            when (failure) {
                null -> assertEquals("observed", gateway.analyze(input))
                is CancellationException -> assertFailsWith<CancellationException> { gateway.analyze(input) }
                else -> assertFailsWith<IllegalStateException> { gateway.analyze(input) }
            }
            paths.forEach { assertFalse(Files.exists(it)) }
        }
    }

    @Test
    fun `remote vision uses shared pixels for Codex as well as OpenAI and Anthropic`() = runTest {
        val image = ImageSource.read(ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray())
        for (model in listOf(LLMModel.OpenAIGpt52, LLMModel.AnthropicSonnet45, LLMModel.CodexGpt61Sol)) {
            val settings = mockk<SettingsProvider> {
                every { gigaModel } returns model
                every { executionModelId(model) } returns model.alias
                every { contextSize } returns 24_000
            }
            val api = mockk<LLMChatAPI>()
            coEvery { api.message(any()) } coAnswers {
                val request = firstArg<LLMRequest.Chat>()
                assertEquals(model.provider, request.provider)
                assertEquals(listOf(image.forVision()), request.messages.single().attachments)
                LLMResponse.Chat.Ok(listOf(LLMResponse.Choice(
                    LLMResponse.Message("observed", LLMMessageRole.assistant, functionsStateId = null), 0, null,
                )), 0, model.alias, LLMResponse.Usage(0, 0, 0, 0))
            }
            assertEquals("observed", ChatVisionGateway(settings, api).analyze(VisionInput(listOf(image), "look")))
        }
    }
}
