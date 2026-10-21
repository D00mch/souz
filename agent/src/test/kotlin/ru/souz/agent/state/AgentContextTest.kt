package ru.souz.agent.state

import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
import ru.souz.llms.ToolInvocationMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AgentContextTest {
    @Test
    fun `map preserves execution metadata and validates the exchange boundary`() {
        val meta = ToolInvocationMeta(
            userId = "user-1",
            conversationId = "conversation-1",
            requestId = "request-1",
        )
        val eventSink = AgentRuntimeEventSink.NONE
        val context = AgentContext(
            input = "input",
            settings = AgentSettings(
                model = "gpt-5-mini",
                provider = LlmProvider.OPENAI,
                temperature = 0.2f,
                toolsByCategory = emptyMap(),
            ),
            history = listOf(LLMRequest.Message(role = LLMMessageRole.user, content = "input")),
            activeTools = emptyList(),
            systemPrompt = "system",
            toolInvocationMeta = meta,
            runtimeEventSink = eventSink,
            activeExchangeStartIndex = 1,
        )

        val mapped = context.map { 42 }

        assertEquals(meta, mapped.toolInvocationMeta)
        assertSame(eventSink, mapped.runtimeEventSink)
        assertEquals(1, mapped.activeExchangeStartIndex)
        for (invalid in listOf(-1, 2)) {
            assertFailsWith<IllegalArgumentException> { context.copy(activeExchangeStartIndex = invalid) }
        }
        assertFailsWith<IllegalArgumentException> { context.map<Int>(history = emptyList()) { 42 } }
        assertEquals(0, context.map(history = emptyList(), activeExchangeStartIndex = 0) { 42 }.activeExchangeStartIndex)
    }
}
