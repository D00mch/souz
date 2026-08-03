package ru.souz.agent.skills

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.implementations.activation.FakeSkillValidator
import ru.souz.agent.skills.implementations.bundle.SkillBundleLoader
import ru.souz.agent.skills.implementations.bundle.skillFixturePath
import ru.souz.agent.skills.implementations.registry.InMemorySkillRegistryRepository
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.json.JsonUtils
import ru.souz.llms.restJsonMapper
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationLevel
import ru.souz.agent.skills.validation.SkillValidationRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SkillApprovalGateTest {
    @Test
    fun `approval validates bundle once and reuses cache`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        val bundle = fixtureBundle()
        repository.saveSkillBundle(USER_ID, bundle)
        val validator = FakeSkillValidator.approving()
        val gate = SkillApprovalGate(repository, validator)

        repeat(2) {
            val result = gate.ensureApproved(input(bundle))
            assertIs<SkillApprovalGate.Result.Approved>(result)
        }

        assertEquals(1, validator.invocationCount)
    }

    @Test
    fun `approval revalidates when bundle hash changes`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        val firstBundle = fixtureBundle()
        repository.saveSkillBundle(USER_ID, firstBundle)
        val validator = FakeSkillValidator.approving()
        val gate = SkillApprovalGate(repository, validator)

        assertIs<SkillApprovalGate.Result.Approved>(gate.ensureApproved(input(firstBundle)))

        val changedBundle = SkillBundle.fromFiles(
            skillId = SKILL_ID,
            files = firstBundle.files + SkillFile("notes.txt", "changed".toByteArray()),
        )
        repository.saveSkillBundle(USER_ID, changedBundle)

        assertIs<SkillApprovalGate.Result.Approved>(gate.ensureApproved(input(changedBundle)))
        assertEquals(2, validator.invocationCount)
    }

    @Test
    fun `approval rejects cached rejection without calling validator`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        val bundle = fixtureBundle()
        val bundleHash = SkillBundleHasher.hash(bundle)
        repository.saveSkillBundle(USER_ID, bundle)
        repository.saveValidation(
            SkillValidationRecord(
                userId = USER_ID,
                skillId = SKILL_ID,
                bundleHash = bundleHash,
                policyVersion = "skills-policy/v1",
                approved = false,
                findings = listOf(
                    SkillValidationFinding(
                        code = "test.rejected",
                        message = "Rejected earlier.",
                        level = SkillValidationLevel.ERROR,
                    )
                ),
                createdAt = java.time.Instant.EPOCH,
            )
        )
        val validator = FakeSkillValidator.approving()
        val gate = SkillApprovalGate(repository, validator)

        val result = gate.ensureApproved(input(bundle))

        val rejected = assertIs<SkillApprovalGate.Result.Rejected>(result)
        assertEquals("Rejected earlier.", rejected.reason)
        assertEquals(0, validator.invocationCount)
    }

    @Test
    fun `factory resolves validation model when approval runs`() = runTest {
        val repository = InMemorySkillRegistryRepository()
        val firstBundle = fixtureBundle()
        val settingsProvider = MutableAgentSettingsProvider(LLMModel.Max)
        val api = CapturingApprovalChatApi()
        val gate = SkillApprovalGate.from(
            validationStore = repository,
            llmApi = api,
            settingsProvider = settingsProvider,
            jsonUtils = JsonUtils(restJsonMapper),
        )
        repository.saveSkillBundle(USER_ID, firstBundle)

        settingsProvider.gigaModel = LLMModel.OpenAIGpt5Mini
        val firstApproval = assertIs<SkillApprovalGate.Result.Approved>(
            gate.ensureApproved(input(firstBundle))
        )

        val changedBundle = SkillBundle.fromFiles(
            skillId = SKILL_ID,
            files = firstBundle.files + SkillFile("notes.txt", "changed".toByteArray()),
        )
        repository.saveSkillBundle(USER_ID, changedBundle)
        settingsProvider.gigaModel = LLMModel.QwenMax
        val secondApproval = assertIs<SkillApprovalGate.Result.Approved>(
            gate.ensureApproved(input(changedBundle))
        )

        assertEquals(
            listOf(LLMModel.OpenAIGpt5Mini.alias, LLMModel.QwenMax.alias),
            api.models,
        )
        assertEquals(true, firstApproval.record?.approved)
        assertEquals(true, secondApproval.record?.approved)
    }

    private fun input(bundle: SkillBundle): SkillApprovalGate.Input = SkillApprovalGate.Input(
        userId = USER_ID,
        skillId = SKILL_ID,
        bundle = bundle,
    )

    private fun fixtureBundle(): SkillBundle = SkillBundleLoader().loadDirectory(
        skillId = SKILL_ID,
        rootDirectory = skillFixturePath("paper-summarize-academic"),
    )

    private class MutableAgentSettingsProvider(
        override var gigaModel: LLMModel,
    ) : AgentSettingsProvider {
        override var defaultCalendar: String? = null
        override var regionProfile: String = "default"
        override var activeAgentId: AgentId = AgentId.default
        override var useStreaming: Boolean = false
        override var contextSize: Int = 0
        override var temperature: Float = 0f

        override fun getSystemPromptForAgentModel(
            agentId: AgentId,
            model: LLMModel,
        ): String? = null

        override fun setSystemPromptForAgentModel(
            agentId: AgentId,
            model: LLMModel,
            prompt: String?,
        ) = Unit
    }

    private class CapturingApprovalChatApi : LLMChatAPI {
        val models = mutableListOf<String>()

        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
            models += body.model
            return LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = APPROVAL_JSON,
                            role = LLMMessageRole.assistant,
                            functionsStateId = null,
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.stop,
                    ),
                ),
                created = 1L,
                model = body.model,
                usage = LLMResponse.Usage(0, 0, 0, 0),
            )
        }

        override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = emptyFlow()

        override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings {
            error("Not used in this test")
        }

        override suspend fun uploadFile(file: File): LLMResponse.UploadFile {
            error("Not used in this test")
        }

        override suspend fun downloadFile(fileId: String): String? {
            error("Not used in this test")
        }

        override suspend fun balance(): LLMResponse.Balance {
            error("Not used in this test")
        }
    }

    private companion object {
        const val USER_ID = "user-1"
        val SKILL_ID = SkillId("paper-summarize-academic")
        val APPROVAL_JSON = """
            {
              "decision":"approve",
              "confidence":0.96,
              "riskLevel":"low",
              "reasons":["Benign test fixture"],
              "requestedCapabilities":["paper summarization"],
              "suspiciousFiles":[],
              "findings":[]
            }
        """.trimIndent()
    }
}
