package agent

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.AgentSettings
import ru.gigadesk.agent.nodes.toGigaRequest
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaMessageRole

class ContextSizeRequestMappingTest {

    @Test
    fun `toGigaRequest maps contextSize to maxTokens`() {
        val expectedContextSize = 64_000
        val ctx = AgentContext(
            input = "test",
            settings = AgentSettings(
                model = "qwen-plus",
                temperature = 0.7f,
                toolsByCategory = emptyMap(),
                contextSize = expectedContextSize,
            ),
            history = emptyList(),
            activeTools = emptyList(),
            systemPrompt = "system"
        )
        val history = listOf(GigaRequest.Message(GigaMessageRole.user, "hello"))

        val request = ctx.toGigaRequest(history)

        assertEquals(expectedContextSize, request.maxTokens)
    }
}
