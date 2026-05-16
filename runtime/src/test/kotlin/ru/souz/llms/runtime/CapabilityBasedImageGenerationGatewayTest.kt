package ru.souz.llms.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilityBasedImageGenerationGatewayTest {

    @Test
    fun `delegates to OpenAI image generation even when chat model is local`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.openaiKey } returns "test-key"
        every { settingsProvider.gigaModel } returns LLMModel.LocalGemma4_E2B_It

        val openAiGateway = mockk<OpenAIImageGenerationGateway>()
        val generated = GeneratedImage(
            bytes = byteArrayOf(1, 2, 3),
            mimeType = "image/png",
            provider = "OPENAI",
            model = "gpt-image-1",
        )
        coEvery { openAiGateway.generate(any()) } returns generated

        val gateway = CapabilityBasedImageGenerationGateway(
            settingsProvider = settingsProvider,
            openAiGateway = openAiGateway,
        )

        val result = gateway.generate(ImageGenerationInput(prompt = "Draw a cat"))

        assertEquals(generated, result)
        coVerify(exactly = 1) { openAiGateway.generate(ImageGenerationInput(prompt = "Draw a cat")) }
    }
}
