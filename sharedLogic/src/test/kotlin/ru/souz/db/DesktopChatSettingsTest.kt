package ru.souz.db

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.local.LocalProviderAvailability

class DesktopChatSettingsTest {
    private val stored = mutableMapOf<String, String>()
    private val localModels = mockk<LocalProviderAvailability>()

    @BeforeTest
    fun setUp() {
        val prefs = mockk<Preferences>()
        every { prefs.get(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
        mockkObject(ConfigStore)
        every { ConfigStore.prefs } returns prefs
        every { ConfigStore.put(any(), any()) } answers { stored[firstArg()] = secondArg<Any>().toString() }
        every { localModels.availableGigaModels() } returns emptyList()
        every { localModels.defaultGigaModel() } returns null
    }

    @AfterTest
    fun tearDown() = unmockkObject(ConfigStore)

    @Test
    fun `stored cloud model aliases survive language changes and reloads`() {
        for ((language, model) in listOf(REGION_RU to LLMModel.Pro, REGION_EN to LLMModel.OpenAIGpt52)) {
            stored["APP_LANGUAGE"] = language
            stored["GIGA_MODEL"] = model.name
            val settings = SettingsProviderImpl(ConfigStore, localModels)
            assertEquals(model, settings.gigaModel)
            settings.regionProfile = if (language == REGION_EN) REGION_RU else REGION_EN
            assertEquals(model, SettingsProviderImpl(ConfigStore, localModels).gigaModel)
            assertEquals(model.name, stored["GIGA_MODEL"])
        }
    }

    @Test
    fun `Qwen 3_8 selection stores the hosted ID and survives settings reconstruction`() {
        val settings = SettingsProviderImpl(ConfigStore, localModels)
        settings.gigaModel = LLMModel.Qwen38_27B
        assertEquals("qwen3.8-27b", stored["GIGA_MODEL"])
        assertEquals(LLMModel.Qwen38_27B, SettingsProviderImpl(ConfigStore, localModels).gigaModel)
    }

    @Test
    fun `automatic chat default uses keys and a supported local model in both languages`() {
        val settings = spyk(SettingsProviderImpl(ConfigStore, localModels))
        val local = LLMModel.LocalQwen3_4B_Instruct_2507
        val providers = mutableSetOf(LlmProvider.LOCAL)
        every { settings.hasKey(any<LlmProvider>()) } answers { firstArg<LlmProvider>() in providers }
        every { localModels.defaultGigaModel() } returns local
        assertEquals(LLMModel.QwenMax, settings.gigaModel)
        every { localModels.availableGigaModels() } returns listOf(local)
        assertEquals(local, settings.gigaModel)

        providers += setOf(LlmProvider.OPENAI, LlmProvider.GIGA)
        for (language in listOf(REGION_RU, REGION_EN)) {
            settings.regionProfile = language
            assertEquals(LLMModel.OpenAIGpt5Mini, settings.gigaModel)
            stored["OPENAI_MODEL"] = "provider-model"
            assertEquals(LLMModel.OpenAICompatibleCustom, settings.gigaModel)
            stored.remove("OPENAI_MODEL")
        }
        providers -= LlmProvider.OPENAI
        assertEquals(LLMModel.Max, settings.gigaModel)
    }
}
