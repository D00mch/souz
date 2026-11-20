package ru.souz.db

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.local.LocalProviderAvailability
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopModelSettingsTest {
    private val stored = mutableMapOf<String, String>()
    private val local = mockk<LocalProviderAvailability>(relaxed = true)

    @BeforeTest
    fun setUp() {
        val preferences = mockk<Preferences>()
        every { preferences.get(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
        every { preferences.put(any(), any()) } answers { stored[firstArg()] = secondArg() }
        every { preferences.remove(any()) } answers { stored.remove(firstArg<String>()); return@answers }
        mockkObject(ConfigStore)
        every { ConfigStore.prefs } returns preferences
        every { ConfigStore.put(any(), any()) } answers { stored[firstArg()] = secondArg<Any>().toString() }
        every { ConfigStore.rm(any()) } answers { stored.remove(firstArg<String>()); return@answers }
        every { local.availableGigaModels() } returns emptyList()
        every { local.defaultGigaModel() } returns null
    }

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `existing regional settings preserve every supported saved cloud selection across reloads`() {
        for (region in listOf(REGION_RU, REGION_EN)) {
            for (model in listOf(LLMModel.Pro, LLMModel.AiTunnelClaudeHaiku, LLMModel.OpenAIGpt5Mini,
                LLMModel.AnthropicHaiku45, LLMModel.QwenMax, LLMModel.CodexGpt54)) {
                stored["APP_LANGUAGE"] = region
                stored["GIGA_MODEL"] = model.name
                val settings = SettingsProviderImpl(ConfigStore, local)
                assertEquals(model, settings.gigaModel)
                assertEquals(model.name, stored["GIGA_MODEL"])
                settings.regionProfile = if (region == REGION_RU) REGION_EN else REGION_RU
                assertEquals(model, settings.gigaModel)
                settings.gigaModel = model
                assertEquals(model.alias, stored["GIGA_MODEL"])
                assertEquals(model, SettingsProviderImpl(ConfigStore, local).gigaModel)
            }
        }
    }

    @Test
    fun `unknown unavailable local and unconfigured custom models fall back without rewriting saved values`() {
        stored["APP_LANGUAGE"] = REGION_RU
        val settings = spyk(SettingsProviderImpl(ConfigStore, local))
        every { settings.hasKey(any<LlmProvider>()) } returns false
        for (value in listOf("removed-model", LLMModel.LocalGemma4_E2B_It.alias, LLMModel.OpenAICompatibleCustom.alias)) {
            stored["GIGA_MODEL"] = value
            assertEquals(LLMModel.Max, settings.gigaModel)
            assertEquals(value, stored["GIGA_MODEL"])
        }
        stored["OPENAI_MODEL"] = "fixture-model"
        assertEquals(LLMModel.OpenAICompatibleCustom, settings.gigaModel)
        every { local.availableGigaModels() } returns listOf(LLMModel.LocalQwen3_4B_Instruct_2507)
        settings.gigaModel = LLMModel.LocalQwen3_4B_Instruct_2507
        assertEquals(LLMModel.LocalQwen3_4B_Instruct_2507, settings.gigaModel)
    }
}
