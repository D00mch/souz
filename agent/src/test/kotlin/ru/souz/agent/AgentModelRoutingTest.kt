package ru.souz.agent

import io.mockk.every
import io.mockk.mockk
import ru.souz.agent.nodes.toChatRequest
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentModelRoutingTest {
    @Test
    fun `context creation snapshots the host model ID and provider together`() {
        val settings = mockk<AgentSettingsProvider>(relaxed = true) {
            every { activeAgentId } returns AgentId.SKILLS_GRAPH
            every { gigaModel } returns LLMModel.OpenAICompatibleCustom
            every { getSystemPromptForAgentModel(any(), any()) } returns "system"
            every { executionModelId(any()) } answers {
                firstArg<LLMModel>().let { if (it == LLMModel.OpenAICompatibleCustom) "Custom/Parent" else it.alias }
            }
        }
        val catalog = AgentToolCatalog()
        val factory = AgentContextFactory(settings, catalog)
        val original = factory.create(AgentId.SKILLS_GRAPH)
        assertEquals("Custom/Parent", original.settings.model)
        assertEquals(LlmProvider.OPENAI, original.settings.provider)
        val request = original.toChatRequest(emptyList(), maxOutputTokens = 512)
        assertEquals(original.settings.model, request.model)
        assertEquals(original.settings.provider, request.provider)
        assertEquals(512, request.maxTokens)
        assertEquals(original.settings.contextSize, request.contextSize)

        val changed = factory.create(
            agentId = AgentId.SKILLS_GRAPH,
            history = emptyList(),
            model = LLMModel.AnthropicSonnet45,
            contextSize = original.settings.contextSize,
            temperature = original.settings.temperature,
        ).settings
        assertEquals(LLMModel.AnthropicSonnet45.alias, changed.model)
        assertEquals(LlmProvider.ANTHROPIC, changed.provider)
        assertEquals("Custom/Parent", original.settings.model)
    }
}
