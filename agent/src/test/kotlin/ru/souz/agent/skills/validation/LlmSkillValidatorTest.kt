package ru.souz.agent.skills.validation

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.json.JsonUtils
import ru.souz.llms.restJsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmSkillValidatorTest {
    @Test
    fun `validator rejects malformed or unsupported model output without throwing`() = runTest {
        val cases = listOf(
            "malformed json" to "not-json",
            "missing fields" to """{"decision":"approve"}""",
            "unknown decision" to """
                {
                  "decision":"maybe",
                  "confidence":0.5,
                  "riskLevel":"low",
                  "reasons":[],
                  "requestedCapabilities":[],
                  "suspiciousFiles":[],
                  "findings":[]
                }
            """.trimIndent(),
            "unknown finding severity" to """
                {
                  "decision":"approve",
                  "confidence":0.5,
                  "riskLevel":"low",
                  "reasons":[],
                  "requestedCapabilities":[],
                  "suspiciousFiles":[],
                  "findings":[
                    {"code":"bad","message":"bad severity","severity":"fatal","filePath":"SKILL.md"}
                  ]
                }
            """.trimIndent(),
            "out of range confidence" to """
                {
                  "decision":"approve",
                  "confidence":1.5,
                  "riskLevel":"low",
                  "reasons":[],
                  "requestedCapabilities":[],
                  "suspiciousFiles":[],
                  "findings":[]
                }
            """.trimIndent(),
        )

        cases.forEach { (label, rawResponse) ->
            val validator = LlmSkillValidator(
                llmApi = FixedResponseChatApi(rawResponse),
                model = "validator-model",
                jsonUtils = JsonUtils(restJsonMapper),
            )

            val verdict = validator.validate(validationInput())

            assertEquals(SkillLlmValidationDecision.REJECT, verdict.decision, label)
            assertEquals(1.0, verdict.confidence, label)
            assertEquals(SkillRiskLevel.HIGH, verdict.riskLevel, label)
            assertEquals("validator-model", verdict.model, label)
            assertTrue(
                verdict.reasons.single().startsWith("Validator returned malformed or unsupported output:"),
                label,
            )
            assertEquals(1, verdict.findings.size, label)
            assertEquals("validator_parse_failed", verdict.findings.single().code, label)
            assertEquals(SkillValidationSeverity.ERROR, verdict.findings.single().severity, label)
        }
    }

    private fun validationInput(): SkillLlmValidationInput = SkillLlmValidationInput(
        userId = "user-1",
        skillId = SkillId("paper-summarize"),
        bundleHash = "bundle-hash",
        policy = SkillValidationPolicy.default(),
        manifest = SkillManifest(
            name = "paper_summarize",
            description = "Summarize academic papers.",
            author = "test",
            version = "1.0.0",
            rawFrontmatter = "",
        ),
        filePaths = listOf("SKILL.md"),
        skillMarkdown = "# Paper Summarize",
        supportingFileExcerpts = mapOf("README.md" to "Usage details"),
        structuralFindings = emptyList(),
        staticFindings = emptyList(),
    )

    private class FixedResponseChatApi(
        private val content: String,
    ) : LLMChatAPI {
        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = chatOk(content)

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
        fun chatOk(content: String): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
            choices = listOf(
                LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = content,
                        role = LLMMessageRole.assistant,
                        functionsStateId = null,
                    ),
                    index = 0,
                    finishReason = LLMResponse.FinishReason.stop,
                ),
            ),
            created = 1L,
            model = "validator-response-model",
            usage = LLMResponse.Usage(0, 0, 0, 0),
        )
    }
}
