package ru.souz.local

import com.sun.jna.Pointer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.AgentSettings
import ru.souz.agent.engine.GraphRuntime
import ru.souz.agent.engine.RetryPolicy
import ru.souz.agent.nodes.NodesCommon
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.toGiga
import ru.souz.giga.toSystemPromptMessage
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.calendar.ToolCalendarListEvents

class LocalInferenceSupportTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `selectForRam chooses Qwen on 8gb and Llama on 16gb`() {
        assertEquals(LocalModelProfiles.QWEN3_4B_INSTRUCT_2507, LocalModelProfiles.selectForRam(8))
        assertEquals(LocalModelProfiles.LLAMA_3_1_8B_INSTRUCT, LocalModelProfiles.selectForRam(16))
    }

    @Test
    fun `availableForRam exposes all eligible local models including Gigachat on 16gb`() {
        assertEquals(
            listOf(
                LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
                LocalModelProfiles.LLAMA_3_1_8B_INSTRUCT,
                LocalModelProfiles.GIGACHAT_3_1_10B_A1_8B,
            ),
            LocalModelProfiles.availableForRam(16),
        )
    }

    @Test
    fun `gigachat prompt renderer uses gigachat separators`() {
        val renderer = LocalPromptRenderer()

        val prompt = renderer.render(
            body = GigaRequest.Chat(
                model = LocalModelProfiles.GIGACHAT_3_1_10B_A1_8B.gigaModel.alias,
                messages = listOf(
                    GigaRequest.Message(GigaMessageRole.system, "System"),
                    GigaRequest.Message(GigaMessageRole.user, "Проверь календарь"),
                ),
            ),
            profile = LocalModelProfiles.GIGACHAT_3_1_10B_A1_8B,
        )

        assertTrue(prompt.startsWith("<s>"))
        assertTrue(prompt.contains("user<|role_sep|>Проверь календарь<|message_sep|>"))
        assertTrue(prompt.contains("available functions<|role_sep|>[]<|message_sep|>"))
        assertTrue(prompt.endsWith("assistant<|role_sep|>"))
    }

    @Test
    fun `strict json parser converts final response`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """{"type":"final","content":"hello"}""",
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = GigaResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<GigaResponse.Chat.Ok>(result)
        assertEquals("hello", ok.choices.single().message.content)
        assertEquals(GigaMessageRole.assistant, ok.choices.single().message.role)
    }

    @Test
    fun `strict json parser extracts final response from control tokens`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """<|start_header_id|>assistant<|end_header_id|>

{"type":"final","content":"hello"}<|eot_id|>""",
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = GigaResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<GigaResponse.Chat.Ok>(result)
        assertEquals("hello", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser falls back to plain text final response`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """В календаре "Семья" на сегодня нет событий.""",
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = GigaResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<GigaResponse.Chat.Ok>(result)
        assertEquals("""В календаре "Семья" на сегодня нет событий.""", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser treats result object as final response`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """{"result":"Список сообщений в почте: 7 штук."}""",
            requestModel = LocalModelProfiles.GIGACHAT_3_1_10B_A1_8B.gigaModel.alias,
            usage = GigaResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<GigaResponse.Chat.Ok>(result)
        assertEquals("Список сообщений в почте: 7 штук.", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser unwraps embedded result object inside final content`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """{"type":"final","content":"{\"result\":\"Список сообщений в почте: 7 штук.\"}"}""",
            requestModel = LocalModelProfiles.GIGACHAT_3_1_10B_A1_8B.gigaModel.alias,
            usage = GigaResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<GigaResponse.Chat.Ok>(result)
        assertEquals("Список сообщений в почте: 7 штук.", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser converts tool calls`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """
                {
                  "type":"tool_calls",
                  "calls":[
                    {"id":"call_1","name":"ToolListFiles","arguments":{"path":"/tmp"}}
                  ]
                }
            """.trimIndent(),
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = GigaResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<GigaResponse.Chat.Ok>(result)
        val choice = ok.choices.single()
        assertEquals("ToolListFiles", choice.message.functionCall?.name)
        assertEquals("call_1", choice.message.functionsStateId)
        assertEquals(GigaResponse.FinishReason.function_call, choice.finishReason)
    }

    @Test
    fun `strict json parser recovers malformed tool call polluted by schema fields`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """
                {"type":"tool_calls","calls":[{"id":"call_1","name":"CalendarListEvents","arguments":{"date":"2026-03-29","calendarName":"Calendar"}],"required":["date","calendarName"]}
            """.trimIndent(),
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = GigaResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<GigaResponse.Chat.Ok>(result)
        val choice = ok.choices.single()
        assertEquals("CalendarListEvents", choice.message.functionCall?.name)
        assertEquals("2026-03-29", choice.message.functionCall?.arguments?.get("date"))
        assertEquals("Calendar", choice.message.functionCall?.arguments?.get("calendarName"))
        assertEquals("call_1", choice.message.functionsStateId)
    }

    @Test
    fun `strict json parser accepts single tool call object from wrapped local output`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """
                <tool_call>
                {"id":"call_1","name":"ToolListFiles","arguments":{"path":"/tmp"}}
                </tool_call>
            """.trimIndent(),
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = GigaResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<GigaResponse.Chat.Ok>(result)
        val choice = ok.choices.single()
        assertEquals("ToolListFiles", choice.message.functionCall?.name)
        assertEquals("call_1", choice.message.functionsStateId)
    }

    @Test
    fun `download prompt is returned only when local model is missing`() {
        val tempRoot = Files.createTempDirectory("souz-local-models-test")
        val store = LocalModelStore(rootDir = tempRoot)
        val model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel

        val missingPrompt = store.downloadPromptFor(model)
        assertNotNull(missingPrompt)
        assertEquals(model, missingPrompt.model)

        val storedPath = store.modelPath(LocalModelProfiles.QWEN3_4B_INSTRUCT_2507)
        Files.createDirectories(storedPath.parent)
        Files.writeString(storedPath, "stub")

        assertNull(store.downloadPromptFor(model))
    }

    @Test
    fun `preload loads and warms downloaded local model only once`() = runTest {
        val profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
        val availability = mockk<LocalProviderAvailability>()
        every { availability.status() } returns LocalProviderStatus(
            available = true,
            message = "OK",
            selectedProfile = profile,
            availableModels = listOf(profile.gigaModel),
        )

        val modelStore = mockk<LocalModelStore>()
        every { modelStore.isPresent(profile) } returns true
        every { modelStore.requireAvailable(profile) } returns Path.of("/tmp/${profile.ggufFilename}")

        val promptRenderer = mockk<LocalPromptRenderer>()
        every { promptRenderer.render(any(), profile) } returns "warmup prompt"

        val bridge = mockk<LocalNativeBridge>()
        val runtimePointer = Pointer(1)
        val modelPointer = Pointer(2)
        every { bridge.createRuntime() } returns runtimePointer
        every { bridge.loadModel(runtimePointer, any()) } returns modelPointer
        every { bridge.generate(runtimePointer, modelPointer, any()) } returns """
            {"text":"ok","finish_reason":"stop","prompt_tokens":4,"completion_tokens":1,"total_tokens":5,"precached_prompt_tokens":0}
        """.trimIndent()

        val runtime = LocalLlamaRuntime(
            availability = availability,
            modelStore = modelStore,
            promptRenderer = promptRenderer,
            strictJsonParser = mockk(relaxed = true),
            bridge = bridge,
        )

        runtime.preload(profile.gigaModel.alias)
        runtime.preload(profile.gigaModel.alias)

        verify(exactly = 1) { bridge.createRuntime() }
        verify(exactly = 1) { bridge.loadModel(runtimePointer, any()) }
        verify(exactly = 1) { promptRenderer.render(any(), profile) }
        verify(exactly = 1) { bridge.generate(runtimePointer, modelPointer, any()) }
    }

    @Test
    fun `local prompt renderer uses compact tool guidance instead of raw json schema`() {
        val renderer = LocalPromptRenderer()

        val prompt = renderer.render(
            body = GigaRequest.Chat(
                model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
                messages = listOf(
                    GigaRequest.Message(GigaMessageRole.user, "Какие встречи у меня сегодня?")
                ),
                functions = listOf(ToolCalendarListEvents().toGiga().fn),
            ),
            profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
        )

        assertTrue(prompt.contains("CalendarListEvents: List events from a specific calendar for a specific date (or today)."))
        assertTrue(prompt.contains("calendarName (string, required)"))
        assertTrue(prompt.contains("Example arguments JSON:"))
        assertFalse(prompt.contains("\"required\":["))
        assertFalse(prompt.contains("\"properties\":"))
    }

    @Test
    fun `close unloads model and destroys runtime after local preload`() = runTest {
        val profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
        val availability = mockk<LocalProviderAvailability>()
        every { availability.status() } returns LocalProviderStatus(
            available = true,
            message = "OK",
            selectedProfile = profile,
            availableModels = listOf(profile.gigaModel),
        )

        val modelStore = mockk<LocalModelStore>()
        every { modelStore.isPresent(profile) } returns true
        every { modelStore.requireAvailable(profile) } returns Path.of("/tmp/${profile.ggufFilename}")

        val promptRenderer = mockk<LocalPromptRenderer>()
        every { promptRenderer.render(any(), profile) } returns "warmup prompt"

        val bridge = mockk<LocalNativeBridge>(relaxed = true)
        val runtimePointer = Pointer(11)
        val modelPointer = Pointer(12)
        every { bridge.createRuntime() } returns runtimePointer
        every { bridge.loadModel(runtimePointer, any()) } returns modelPointer
        every { bridge.generate(runtimePointer, modelPointer, any()) } returns """
            {"text":"ok","finish_reason":"stop","prompt_tokens":4,"completion_tokens":1,"total_tokens":5,"precached_prompt_tokens":0}
        """.trimIndent()

        val runtime = LocalLlamaRuntime(
            availability = availability,
            modelStore = modelStore,
            promptRenderer = promptRenderer,
            strictJsonParser = mockk(relaxed = true),
            bridge = bridge,
        )

        runtime.preload(profile.gigaModel.alias)
        runtime.close()

        verify(exactly = 1) { bridge.unloadModel(runtimePointer, modelPointer) }
        verify(exactly = 1) { bridge.destroyRuntime(runtimePointer) }
    }

    @Test
    fun `local runtime resolves context size dynamically within requested window and model cap`() {
        val runtime = LocalLlamaRuntime(
            availability = mockk(relaxed = true),
            modelStore = mockk(relaxed = true),
            promptRenderer = mockk(relaxed = true),
            strictJsonParser = mockk(relaxed = true),
            bridge = mockk(relaxed = true),
        )

        assertEquals(
            2048,
            runtime.resolveContextSize(
                body = GigaRequest.Chat(
                    model = LocalModelProfiles.LLAMA_3_1_8B_INSTRUCT.gigaModel.alias,
                    messages = emptyList(),
                    maxTokens = 4096,
                ),
                profile = LocalModelProfiles.LLAMA_3_1_8B_INSTRUCT,
                prompt = "Короткий prompt",
            )
        )
        assertEquals(
            16384,
            runtime.resolveContextSize(
                body = GigaRequest.Chat(
                    model = LocalModelProfiles.LLAMA_3_1_8B_INSTRUCT.gigaModel.alias,
                    messages = emptyList(),
                    maxTokens = 32000,
                ),
                profile = LocalModelProfiles.LLAMA_3_1_8B_INSTRUCT,
                prompt = "x".repeat(60_000),
            )
        )
        assertEquals(
            8192,
            runtime.resolveContextSize(
                body = GigaRequest.Chat(
                    model = LocalModelProfiles.LLAMA_3_1_8B_INSTRUCT.gigaModel.alias,
                    messages = emptyList(),
                    maxTokens = 16000,
                ),
                profile = LocalModelProfiles.LLAMA_3_1_8B_INSTRUCT,
                prompt = "x".repeat(20_000),
            )
        )
    }

    @Test
    fun `local model keeps static additional context but skips desktop search`() = runTest {
        mockkObject(ToolRunBashCommand)
        every { ToolRunBashCommand.sh(any()) } returns ""

        val desktopInfoRepository = mockk<DesktopInfoRepository>()
        coEvery { desktopInfoRepository.search(any(), any()) } returns listOf()
        val settingsProvider = mockk<SettingsProvider> {
            every { defaultCalendar } returns "Work"
        }
        val nodesCommon = NodesCommon(
            desktopInfoRepository = desktopInfoRepository,
            settingsProvider = settingsProvider,
            agentToolExecutor = mockk<AgentToolExecutor>(relaxed = true),
        )
        val context = AgentContext(
            input = "Проверь Telegram",
            settings = AgentSettings(
                model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
                temperature = 0.2f,
                toolsByCategory = emptyMap(),
            ),
            history = listOf(
                "system".toSystemPromptMessage(),
                GigaRequest.Message(GigaMessageRole.user, "Проверь Telegram"),
            ),
            activeTools = emptyList(),
            systemPrompt = "system",
        )
        val result = nodesCommon.nodeAppendAdditionalData().execute(
            ctx = context,
            runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
        )
        val injectedContext = assertNotNull(result.history.firstOrNull { it.content.contains("<context>") })

        assertTrue(nodesCommon.shouldAppendAdditionalData(LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias))
        assertFalse(nodesCommon.shouldSearchAdditionalData(LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias))
        assertTrue(nodesCommon.shouldSearchAdditionalData("gpt-5-nano"))
        assertTrue(injectedContext.content.contains("Календарь по умолчанию: Work"))
        assertTrue(injectedContext.content.contains("Текущие дата и время:"))
        coVerify(exactly = 0) { desktopInfoRepository.search(any(), any()) }
    }

    @Test
    fun `local chat api reports unsupported features`() = runTest {
        val api = LocalChatAPI(runtime = mockk(relaxed = true))

        val embeddings = api.embeddings(GigaRequest.Embeddings(input = listOf("hello")))
        val balance = api.balance()

        assertIs<GigaResponse.Embeddings.Error>(embeddings)
        assertIs<GigaResponse.Balance.Error>(balance)
        assertFailsWith<UnsupportedOperationException> { api.uploadFile(createTempFile()) }
        assertFailsWith<UnsupportedOperationException> { api.downloadFile("file_1") }
    }
}
