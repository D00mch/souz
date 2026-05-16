package ru.souz.llms

import io.mockk.every
import io.mockk.mockk
import ru.souz.db.SettingsProvider
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAIImageGenerationGatewayTest {

    @Test
    fun `buildRequest targets gpt image endpoint with png output`() {
        val gateway = createGateway()

        val request = invokeBuildRequest(
            gateway = gateway,
            prompt = "Draw a red cube on a white table",
        )

        assertEquals("gpt-image-1", request["model"])
        assertEquals("Draw a red cube on a white table", request["prompt"])
        assertEquals("png", request["output_format"])
        assertEquals("1024x1024", request["size"])
        assertEquals("medium", request["quality"])
    }

    private fun createGateway(): OpenAIImageGenerationGateway {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.openaiKey } returns "test-key"
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        return OpenAIImageGenerationGateway(settingsProvider)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildRequest(
        gateway: OpenAIImageGenerationGateway,
        prompt: String,
    ): Map<String, Any> {
        val method = OpenAIImageGenerationGateway::class.java.getDeclaredMethod(
            "buildRequest",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(gateway, prompt) as Map<String, Any>
    }
}
