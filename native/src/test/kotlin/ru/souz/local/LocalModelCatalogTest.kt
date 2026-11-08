package ru.souz.local

import com.sun.jna.Pointer
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.local.LocalEmbeddingProfiles
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalNativeBridge
import ru.souz.llms.local.LocalPromptRenderer
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.llms.local.LocalProviderStatus
import ru.souz.llms.local.LocalStrictJsonParser
import ru.souz.llms.local.downloadPromptFor
import ru.souz.llms.local.requiredDownloadProfiles
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalModelCatalogTest {
    @TempDir
    lateinit var root: Path

    private val models = listOf(LocalModelProfiles.QWEN35_4B, LocalModelProfiles.QWEN35_9B)

    @Test
    fun `catalog keeps small default and gates resident weights by RAM`() {
        assertEquals(models + listOf(LocalModelProfiles.GEMMA4_E2B_IT, LocalModelProfiles.GEMMA4_E4B_IT), LocalModelProfiles.all)
        assertEquals(listOf(8, 16), models.map { it.minRamGb })
        for (ram in listOf(8, 16, 24, 32)) {
            assertEquals(LocalModelProfiles.QWEN35_4B, LocalModelProfiles.selectForRam(ram))
            assertEquals(models.filter { ram >= it.minRamGb }, LocalModelProfiles.availableForRam(ram).filter { it in models })
        }
        for (model in models) {
            assertEquals(model, LocalModelProfiles.forAlias(model.gigaModel.alias.uppercase()))
            assertEquals(model, LocalModelProfiles.forAlias(model.gigaModel.name))
            assertEquals("Q4_K_M", model.quantization)
            assertEquals(8192, model.defaultContextSize)
            assertEquals(32_768, model.maxContextSize)
            assertEquals(listOf(model, LocalEmbeddingProfiles.default()), model.requiredDownloadProfiles())
        }
    }

    @Test
    fun `legacy selection requests replacement download even when old weights exist`() {
        val oldDir = Files.createDirectories(root.resolve("local-qwen3-4b-instruct-2507"))
        Files.writeString(oldDir.resolve("Qwen3-4B-Instruct-2507-Q4_K_M.gguf"), "old weights")
        val profile = assertNotNull(LocalModelProfiles.forAlias(" local-qwen3-4b-instruct-2507 "))
        assertEquals(LocalModelProfiles.QWEN35_4B, profile)
        val store = LocalModelStore(root)
        assertFalse(store.isPresent(profile))
        val prompt = assertNotNull(store.downloadPromptFor(profile.gigaModel))
        assertEquals(profile.requiredDownloadProfiles(), prompt.downloads)
        assertEquals(root.resolve("local-qwen3.5-4b/Qwen3.5-4B-Q4_K_M.gguf").toString(), prompt.targetPath(profile))
        assertTrue(Files.exists(oldDir.resolve("Qwen3-4B-Instruct-2507-Q4_K_M.gguf")))
    }

    @Test
    fun `all new profiles preserve dependent tool turns through runtime and stream parsing`() = runBlocking {
        for (profile in models) for (streaming in listOf(false, true)) {
            val availability = mockk<LocalProviderAvailability> {
                every { status() } returns LocalProviderStatus(true, "OK", profile, listOf(profile.gigaModel))
            }
            val store = mockk<LocalModelStore> { every { requireAvailable(profile) } returns root.resolve(profile.ggufFilename) }
            val bridge = mockk<LocalNativeBridge>(relaxed = true)
            every { bridge.createRuntime() } returns Pointer(1)
            every { bridge.loadModel(any(), any()) } returns Pointer(2)
            val requests = mutableListOf<LocalLlamaRuntime.LocalGenerationRequest>()
            val answers = ArrayDeque(listOf(
                """{"type":"final","content":"hello"}""",
                toolCall("step1", "Lookup", """{"key":"fixture"}"""),
                toolCall("step2", "Read", """{"id":42}"""),
                """{"type":"final","content":"SYNTHETIC_RESULT"}""",
            ))
            fun generate(request: String): String {
                requests += restJsonMapper.readValue(request, LocalLlamaRuntime.LocalGenerationRequest::class.java)
                return restJsonMapper.writeValueAsString(mapOf("text" to answers.removeFirst(), "finish_reason" to "stop"))
            }
            every { bridge.generate(any(), any(), any()) } answers { generate(thirdArg()) }
            every { bridge.generateStream(any(), any(), any(), any()) } answers { generate(thirdArg()) }
            val runtime = LocalLlamaRuntime(availability, store, LocalPromptRenderer(), LocalStrictJsonParser(), bridge)
            try {
                val history = mutableListOf(LLMRequest.Message(LLMMessageRole.user, "hello"))
                suspend fun chat(fallback: Boolean = false): LLMResponse.Chat.Ok {
                    val request = LLMRequest.Chat(
                        model = if (fallback) "selected-local-default" else profile.gigaModel.alias,
                        provider = if (fallback) null else LlmProvider.LOCAL,
                        messages = history.toList(),
                        functions = listOf(LLMRequest.Function("Lookup"), LLMRequest.Function("Read")),
                        contextSize = 12_288,
                    )
                    return assertIs(if (streaming) runtime.chatStream(request).single() else runtime.chat(request))
                }
                // A metadata-free request uses the selected local profile.
                assertEquals("hello", chat(fallback = true).choices.single().message.content)
                history += LLMRequest.Message(LLMMessageRole.user, "Lookup fixture, then read its id.")
                for ((name, args, result) in listOf(
                    Triple("Lookup", """{"key":"fixture"}""", """{"id":42}"""),
                    Triple("Read", """{"id":42}""", "SYNTHETIC_RESULT"),
                )) {
                    val choice = chat().choices.single()
                    assertEquals(LLMResponse.FinishReason.function_call, choice.finishReason)
                    assertEquals(name, choice.message.functionCall?.name)
                    assertEquals(restJsonMapper.readTree(args), restJsonMapper.valueToTree(choice.message.functionCall?.arguments))
                    history += LLMRequest.Message(LLMMessageRole.assistant, "", functionsStateId = choice.message.functionsStateId,
                        functionCall = LLMRequest.FunctionCall(name, args))
                    history += LLMRequest.Message(LLMMessageRole.function, result, name = name,
                        functionsStateId = choice.message.functionsStateId)
                }
                assertEquals("SYNTHETIC_RESULT", chat().choices.single().message.content)
                val last = requests.last()
                assertTrue(last.prompt.contains("\"id\":42"))
                assertTrue(last.prompt.contains("\"tool_call_id\":\"step1\""))
                assertTrue(last.prompt.contains("\"tool_call_id\":\"step2\""))
                assertTrue(last.prompt.contains("SYNTHETIC_RESULT"))
                assertEquals(12_288, last.contextSize)
                assertEquals(1024, last.maxTokens)
                assertFalse(last.prompt.startsWith("<|startoftext|>"))
                assertTrue(last.prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
            } finally {
                runtime.shutdown()
            }
        }
    }

    private fun toolCall(id: String, name: String, arguments: String): String =
        """{"type":"tool_calls","calls":[{"id":"$id","name":"$name","arguments":$arguments}]}"""
}
