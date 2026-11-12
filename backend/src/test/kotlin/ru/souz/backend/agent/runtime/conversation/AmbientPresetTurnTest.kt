package ru.souz.backend.agent.runtime.conversation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.jev.JevClient
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AmbientPresetTurnTest {
    @Test
    fun `explicit preset keeps literal text and skips providers`() = runTest {
        val command = RecordingCommand()
        val meta = ToolInvocationMeta(userId = "ambient-owner")
        HttpClient(MockEngine { error("Explicit requests do not need Jev") }) { providerHttpClientDefaults() }.use { client ->
            val turn = AmbientPresetTurn(JevClient(client, "test-key"), bundleProvider)
            val result = assertNotNull(turn.tryRender("ambient-background blue «true»", meta, { command }) {
                error("The supplied phrase should not be generated")
            })
            assertEquals(command.file, result.file)
            assertNull(result.routedModel)
            assertEquals("blue" to "true", command.inputs.single())
            assertEquals(meta, command.invocation)
        }
    }

    @Test
    fun `Jev selects a scene and requests one generated phrase only when eligible`() = runTest {
        var accepted = true
        val engine = MockEngine {
            respond("""
                {"answers":{
                  "requires_agent":{"type":"noul","noul":${if (accepted) 0.02 else 0.9}},
                  "invent_phrase":{"type":"noul","noul":0.99},
                  "choice":{"type":"choice","probabilities":{"blue":0.8,"green":0.1,"other":0.1}}
                }}
            """.trimIndent())
        }
        val command = RecordingCommand()
        var generated = 0
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            val turn = AmbientPresetTurn(JevClient(client, "test-key"), bundleProvider)
            val meta = ToolInvocationMeta(userId = "ambient-owner")
            val result = assertNotNull(turn.tryRender("Живой фон перед сном. Придумай фразу.", meta, { command }) { description ->
                assertEquals("Calm blue", description)
                generated++
                "Спокойной ночи"
            })
            assertEquals(AmbientPresetTurn.phraseModel, result.routedModel)
            assertEquals("blue" to "Спокойной ночи", command.inputs.single())
            assertEquals(1, generated)

            accepted = false
            assertNull(turn.tryRender("Сделай фон с портретом из файла.", meta, { command }) {
                error("An unsupported brief should use the normal agent")
            })
            assertEquals(1, command.inputs.size)
            assertEquals(2, engine.requestHistory.size)
        }
    }

    private class RecordingCommand : LLMToolSetup {
        override val fn = LLMRequest.Function("RunSkillCommand")
        val file = FileContent("test-html", "blue.html", "text/html", 50, FilePresentation.document)
        val inputs = mutableListOf<Pair<String, String>>()
        var invocation: ToolInvocationMeta? = null

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            error("Invocation metadata is required")

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
            invocation = meta
            val input = restJsonMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(functionCall.arguments)
            assertEquals("ambient-background", input["skillId"].asText())
            val args = input["arguments"]
            assertEquals("preset", args["composite"].asText())
            val fields = args["inputs"]
            inputs += fields["preset"].asText() to restJsonMapper.readTree(fields["phrase"].asText()).asText()
            return LLMRequest.Message(LLMMessageRole.function, "{}", replyFiles = listOf(file))
        }
    }

    private val bundleProvider = object : SkillBundleProvider {
        override suspend fun listSkills(userId: String) = emptyList<ru.souz.agent.skills.registry.StoredSkill>()

        override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle {
            assertEquals("ambient-owner", userId)
            assertEquals("ambient-background", skillId.value)
            return SkillBundle.fromFiles(skillId, listOf(
                SkillFile("SKILL.md", """
                    ---
                    name: ambient-background
                    description: Animated backgrounds
                    commands:
                      preset:
                        inputs: [preset, phrase]
                        steps:
                          - id: render
                            runtime: PYTHON
                            script: scripts/render.py
                        returns: done
                    ---
                    Render a preset.
                """.trimIndent().toByteArray()),
                SkillFile("scripts/render.py", "print('test')".toByteArray()),
                SkillFile("assets/ambient-presets.json",
                    """{"schema":1,"presets":{"blue":{"description":"Calm blue"},"green":{"description":"Forest"}}}""".toByteArray()),
            ))
        }
    }
}
