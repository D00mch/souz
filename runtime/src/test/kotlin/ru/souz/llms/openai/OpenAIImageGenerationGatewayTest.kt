package ru.souz.llms.openai

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import ru.souz.db.SettingsProvider
import ru.souz.llms.runtime.ImageGenerationInput
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAIImageGenerationGatewayTest {

    @AfterEach
    fun cleanup() {
        System.clearProperty("OPENAI_IMAGE_MODEL")
    }

    @Test
    fun `buildRequestPayload ignores external image model overrides`() {
        System.setProperty("OPENAI_IMAGE_MODEL", "custom-image-model")
        val gateway = createGateway()

        val payload = gateway.buildRequestPayload(ImageGenerationInput(prompt = "Draw a cat"))

        assertEquals("gpt-image-1", payload["model"])
    }

    private fun createGateway(): OpenAIImageGenerationGateway {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.openaiKey } returns "test-key"
        return OpenAIImageGenerationGateway(settingsProvider)
    }
}
