package ru.souz.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.mockk.every
import io.mockk.mockk
import ru.souz.db.DesktopModelCatalog
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.ui.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ModelsSettingsContentTest {
    @Test
    fun `saved OpenAI embedding label and provider selection survive overlapping aliases`() = runComposeUiTest {
        val settings = mockk<SettingsProvider>(relaxed = true)
        every { settings.regionProfile } returns "ru"
        every { settings.gigaModel } returns LLMModel.OpenAIGpt5Mini
        every { settings.hasKey(any<LlmProvider>()) } answers {
            val provider = firstArg<LlmProvider>()
            provider == LlmProvider.AI_TUNNEL || provider == LlmProvider.OPENAI
        }
        val tunnel = EmbeddingsModel.AiTunnelEmbedding3Small
        val openAi = EmbeddingsModel.OpenAITextEmbedding3Small
        val available = settings.availableEmbeddingsModels(DesktopModelCatalog())
        assertTrue(tunnel in available && openAi in available)
        assertEquals(tunnel.alias, openAi.alias)
        var state by mutableStateOf(SettingsState(
            gigaModel = settings.gigaModel,
            availableLlmModels = listOf(settings.gigaModel),
            embeddingsModel = openAi,
            availableEmbeddingsModels = available,
        ))
        val selectedModels = mutableListOf<EmbeddingsModel>()
        setContent {
            AppTheme {
                ModelsSettingsContent(state) { event ->
                    if (event is SettingsEvent.SelectEmbeddingsModel) {
                        selectedModels += event.model
                        state = state.copy(embeddingsModel = event.model)
                    }
                }
            }
        }

        // A persisted OpenAI selection must not display the first model sharing its wire alias.
        onNodeWithText(openAi.displayName).assertIsDisplayed().performClick()
        onNodeWithText(tunnel.displayName).performClick()
        runOnIdle { assertEquals(listOf(tunnel), selectedModels) }
        onNodeWithText(tunnel.displayName).assertIsDisplayed().performClick()
        onNodeWithText(openAi.displayName).performClick()
        runOnIdle { assertEquals(listOf(tunnel, openAi), selectedModels) }
        onNodeWithText(openAi.displayName).assertIsDisplayed()
    }
}
