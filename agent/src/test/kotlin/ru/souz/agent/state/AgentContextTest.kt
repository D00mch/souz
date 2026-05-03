package ru.souz.agent.state

import ru.souz.llms.LLMRequest
import ru.souz.llms.ToolInvocationMeta
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentContextTest {
    @Test
    fun `map preserves tool invocation metadata`() {
        val meta = ToolInvocationMeta(
            userId = "user-1",
            conversationId = "conversation-1",
            requestId = "request-1",
        )
        val context = AgentContext(
            input = "input",
            settings = AgentSettings(
                model = "gpt-5-nano",
                temperature = 0.2f,
                toolsByCategory = emptyMap(),
            ),
            history = listOf(LLMRequest.Message(role = ru.souz.llms.LLMMessageRole.user, content = "input")),
            activeTools = emptyList(),
            systemPrompt = "system",
            toolInvocationMeta = meta,
        )

        val mapped = context.map { 42 }

        assertEquals(meta, mapped.toolInvocationMeta)
    }
}
