package ru.souz.backend.execution.service

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.llm.LlmSettingsStub
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionAnswer
import ru.souz.backend.options.model.OptionKind
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.backend.storage.postgres.StoredSettingsPayload
import ru.souz.backend.storage.postgres.toSettingsJson
import ru.souz.backend.testutil.TestToolCatalog
import ru.souz.llms.restJsonMapper

class ProgressSettingsTest {
    @Test
    fun `settings preserve narration while executions snapshot effective progress including legacy defaults`() = runTest {
        assertNull(restJsonMapper.readValue<StoredSettingsPayload>("{}").intermediateMessages)
        for (intermediate in listOf(null, false, true)) for (narrate in listOf(false, true)) {
            var stored = UserSettings("user", defaultModel = "test-chat", narrateSteps = narrate, intermediateMessages = intermediate)
            val repository = mockk<UserSettingsRepository>()
            coEvery { repository.get("user") } answers { stored }
            coEvery { repository.save(any()) } answers { firstArg<UserSettings>().also { stored = it } }
            val resolver = EffectiveSettingsResolver(LlmSettingsStub(), repository, mockk(), BackendFeatureFlags(),
                TestToolCatalog(), namedModelNames = setOf("test-chat"))
            val factory = AgentExecutionRequestFactory(resolver)
            val prepared = factory.prepareChatTurn("user", UUID.randomUUID(), "hello")
            val enabled = intermediate != false
            assertEquals(enabled, prepared.effectiveSettings.intermediateMessages)
            assertEquals(narrate, prepared.effectiveSettings.narrateSteps)
            assertEquals(narrate && enabled, prepared.runtimeRequest.narrateSteps)
            assertEquals(enabled.toString(), prepared.execution.metadata[METADATA_INTERMEDIATE_MESSAGES])
            assertEquals((narrate && enabled).toString(), prepared.execution.metadata[METADATA_NARRATE_STEPS])

            UserSettingsService(repository, resolver).patch("user", UserSettingsOverrides(intermediateMessages = !enabled))
            assertEquals(narrate, stored.narrateSteps)
            assertEquals(!enabled, restJsonMapper.readValue<StoredSettingsPayload>(stored.toSettingsJson()).intermediateMessages)
            val option = mockk<Option> {
                every { id } returns UUID.randomUUID()
                every { kind } returns OptionKind.GENERIC_SELECTION
                every { selectionMode } returns "single"
                every { options } returns emptyList()
                every { answer } returns OptionAnswer(emptySet(), freeText = "continue")
            }
            assertEquals(narrate && enabled, factory.createContinuationTurnRequest(prepared.execution, option).narrateSteps)
            val legacy = prepared.execution.copy(metadata = prepared.execution.metadata - METADATA_NARRATE_STEPS - METADATA_INTERMEDIATE_MESSAGES)
            assertEquals(false, factory.createContinuationTurnRequest(legacy, option).narrateSteps)
            assertEquals(!enabled, factory.prepareChatTurn("user", UUID.randomUUID(), "next").effectiveSettings.intermediateMessages)
        }
    }
}
