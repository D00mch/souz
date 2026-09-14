package ru.souz.agent

import io.mockk.every
import io.mockk.mockk
import ru.souz.agent.nodes.toGigaRequest
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentModelRoutingTest {
    @Test
    fun `context creation and model changes snapshot the host model ID and provider together`() {
        val settings = mockk<AgentSettingsProvider>(relaxed = true) {
            every { activeAgentId } returns AgentId.GRAPH
            every { gigaModel } returns LLMModel.OpenAICompatibleCustom
            every { getSystemPromptForAgentModel(any(), any()) } returns "system"
            every { executionModelId(any()) } answers {
                firstArg<LLMModel>().let { if (it == LLMModel.OpenAICompatibleCustom) "Custom/Parent" else it.alias }
            }
        }
        val catalog = mockk<AgentToolCatalog> { every { toolsByCategory } returns emptyMap() }
        val factory = AgentContextFactory(settings, mockk(), catalog)
        val facade = AgentFacade(settings, factory, mockk(), mockk(), mockk())
        val original = facade.currentContext.value
        assertEquals("Custom/Parent", original.settings.model)
        assertEquals(LlmProvider.OPENAI, original.settings.provider)
        val request = original.toGigaRequest(emptyList())
        assertEquals(original.settings.model, request.model)
        assertEquals(original.settings.provider, request.provider)

        facade.setModel(LLMModel.AnthropicSonnet45)
        val changed = facade.currentContext.value.settings
        assertEquals(LLMModel.AnthropicSonnet45.alias, changed.model)
        assertEquals(LlmProvider.ANTHROPIC, changed.provider)
        assertEquals("Custom/Parent", original.settings.model)
    }
}
