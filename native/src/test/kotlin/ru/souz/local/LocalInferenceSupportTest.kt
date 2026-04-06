package ru.souz.local

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.sun.jna.Pointer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalBridgeLoader
import ru.souz.llms.local.LocalHostInfo
import ru.souz.llms.local.LocalHostInfoProvider
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalNativeBridge
import ru.souz.llms.local.LocalPlatform
import ru.souz.llms.local.LocalPromptRenderer
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.llms.local.LocalProviderStatus
import ru.souz.llms.local.LocalStrictJsonParser
import ru.souz.llms.local.downloadPromptFor
import ru.souz.llms.local.prefersPlainTextLocalOutput

class LocalInferenceSupportTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private val calendarListEventsFn = LLMRequest.Function(
        name = "CalendarListEvents",
        description = "List events from a specific calendar for a specific date (or today).",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "calendarName" to LLMRequest.Property(
                    type = "string",
                    description = "Name of the calendar to search in.",
                ),
                "date" to LLMRequest.Property(
                    type = "string",
                    description = "Date to list events for in YYYY-MM-DD format. Defaults to today if omitted.",
                ),
            ),
            required = listOf("calendarName", "date"),
        ),
        fewShotExamples = listOf(
            LLMRequest.FewShotExample(
                request = "List today's calendar events",
                params = mapOf(
                    "calendarName" to "Work",
                    "date" to "2026-03-31",
                ),
            )
        ),
    )

    @Test
    fun `selectForRam chooses Qwen for supported local hosts`() {
        assertEquals(LocalModelProfiles.QWEN3_4B_INSTRUCT_2507, LocalModelProfiles.selectForRam(8))
        assertEquals(LocalModelProfiles.QWEN3_4B_INSTRUCT_2507, LocalModelProfiles.selectForRam(16))
    }

    @Test
    fun `availableForRam exposes only supported local profiles`() {
        assertEquals(
            listOf(
                LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
                LocalModelProfiles.GEMMA4_E2B_IT,
            ),
            LocalModelProfiles.availableForRam(8),
        )
        assertEquals(
            listOf(
                LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
                LocalModelProfiles.GEMMA4_E2B_IT,
                LocalModelProfiles.GEMMA4_E4B_IT,
            ),
            LocalModelProfiles.availableForRam(16),
        )
    }

    @Test
    fun `local provider availability returns unavailable when host detection fails`() {
        val hostInfoProvider = mockk<LocalHostInfoProvider>()
        every { hostInfoProvider.current() } throws NoClassDefFoundError("java/lang/management/ManagementFactory")

        val availability = LocalProviderAvailability(
            hostInfoProvider = hostInfoProvider,
            modelStore = mockk(relaxed = true),
            bridgeLoader = mockk<LocalBridgeLoader>(relaxed = true),
        )

        val status = availability.status()

        assertFalse(status.available)
        assertEquals("Local inference is unavailable because host detection failed.", status.message)
        assertNull(status.selectedProfile)
        assertTrue(status.availableModels.isEmpty())
    }

    @Test
    fun `local provider availability returns unavailable when system memory is unknown`() {
        val hostInfoProvider = mockk<LocalHostInfoProvider>()
        every { hostInfoProvider.current() } returns LocalHostInfo(
            osName = "Mac OS X",
            osArch = "aarch64",
            totalRamBytes = 0L,
            totalRamGb = 0,
            platform = LocalPlatform.MACOS_ARM64,
        )

        val availability = LocalProviderAvailability(
            hostInfoProvider = hostInfoProvider,
            modelStore = mockk(relaxed = true),
            bridgeLoader = mockk<LocalBridgeLoader>(relaxed = true),
        )

        val status = availability.status()

        assertFalse(status.available)
        assertEquals(
            "Local inference is unavailable because system memory could not be determined.",
            status.message,
        )
        assertNull(status.selectedProfile)
        assertTrue(status.availableModels.isEmpty())
    }

    @Test
    fun `qwen prompt renderer uses qwen separators`() {
        val renderer = LocalPromptRenderer()
        val chat = LLMRequest.Chat(
            model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            messages = listOf(
                LLMRequest.Message(LLMMessageRole.system, "System"),
                LLMRequest.Message(LLMMessageRole.user, "Проверь календарь"),
            ),
        )

        val prompt = renderer.render(
            body = chat,
            profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
        )

        assertFalse(chat.prefersPlainTextLocalOutput())
        assertTrue(prompt.startsWith("<|im_start|>system"))
        assertTrue(prompt.contains("Return exactly one JSON object and nothing else."))
        assertTrue(prompt.contains("<|im_start|>user\nПроверь календарь\n<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `gemma prompt renderer uses gemma turns and roles`() {
        val renderer = LocalPromptRenderer()
        val chat = LLMRequest.Chat(
            model = LocalModelProfiles.GEMMA4_E2B_IT.gigaModel.alias,
            messages = listOf(
                LLMRequest.Message(LLMMessageRole.system, "System"),
                LLMRequest.Message(LLMMessageRole.user, "Проверь календарь"),
                LLMRequest.Message(LLMMessageRole.assistant, "Сначала посмотрю историю"),
                LLMRequest.Message(
                    role = LLMMessageRole.function,
                    content = "\"ok\"",
                    functionsStateId = "call_1",
                    name = "CalendarListEvents",
                ),
            ),
        )

        val prompt = renderer.render(
            body = chat,
            profile = LocalModelProfiles.GEMMA4_E2B_IT,
        )

        assertTrue(prompt.startsWith("<|turn>system"))
        assertTrue(prompt.contains("<|turn>user\nПроверь календарь\n<turn|>"))
        assertTrue(prompt.contains("<|turn>assistant\nСначала посмотрю историю\n<turn|>"))
        assertTrue(prompt.contains("<|turn>user\n{\"tool_result\":{\"tool_name\":\"CalendarListEvents\",\"tool_call_id\":\"call_1\",\"content\":\"ok\"}}\n<turn|>"))
        assertTrue(prompt.endsWith("<|turn>assistant\n"))
        assertFalse(prompt.contains("<|im_start|>"))
    }

    @Test
    fun `classification prompts use plain text local output mode`() {
        val renderer = LocalPromptRenderer()
        val chat = LLMRequest.Chat(
            model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            messages = listOf(
                LLMRequest.Message(
                    LLMMessageRole.system,
                    """
                        Выбери категории.

                        Формат ответа:
                        CATEGORY1,CATEGORY2 0-100
                    """.trimIndent(),
                ),
                LLMRequest.Message(LLMMessageRole.user, "New message:\nнайди файл"),
            ),
        )

        val prompt = renderer.render(
            body = chat,
            profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
        )

        assertTrue(chat.prefersPlainTextLocalOutput())
        assertTrue(prompt.contains("CATEGORY1,CATEGORY2 0-100"))
        assertFalse(prompt.contains("Return exactly one JSON object and nothing else."))
    }

    @Test
    fun `gemma classification prompts use plain text local output mode`() {
        val renderer = LocalPromptRenderer()
        val chat = LLMRequest.Chat(
            model = LocalModelProfiles.GEMMA4_E4B_IT.gigaModel.alias,
            messages = listOf(
                LLMRequest.Message(
                    LLMMessageRole.system,
                    """
                        Выбери категории.

                        Формат ответа:
                        CATEGORY1,CATEGORY2 0-100
                    """.trimIndent(),
                ),
                LLMRequest.Message(LLMMessageRole.user, "New message:\nнайди файл"),
            ),
        )

        val prompt = renderer.render(
            body = chat,
            profile = LocalModelProfiles.GEMMA4_E4B_IT,
        )

        assertTrue(chat.prefersPlainTextLocalOutput())
        assertTrue(prompt.contains("<|turn>system"))
        assertTrue(prompt.contains("<|turn>user\nNew message:\nнайди файл\n<turn|>"))
        assertTrue(prompt.contains("CATEGORY1,CATEGORY2 0-100"))
        assertFalse(prompt.contains("Return exactly one JSON object and nothing else."))
    }

    @Test
    fun `strict json parser converts final response`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """{"type":"final","content":"hello"}""",
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
        assertEquals("hello", ok.choices.single().message.content)
        assertEquals(LLMMessageRole.assistant, ok.choices.single().message.role)
    }

    @Test
    fun `strict json parser extracts final response from control tokens`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """<|start_header_id|>assistant<|end_header_id|>

{"type":"final","content":"hello"}<|eot_id|>""",
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
        assertEquals("hello", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser strips gemma asymmetric control tokens`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """
                <|turn>assistant
                <turn|>
                <|channel>final
                <channel|>
                {"type":"final","content":"hello"}
            """.trimIndent(),
            requestModel = LocalModelProfiles.GEMMA4_E2B_IT.gigaModel.alias,
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
        assertEquals("hello", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser preserves control tokens inside valid json content`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """{"type":"final","content":"Use <|turn> and <turn|> literally."}""",
            requestModel = LocalModelProfiles.GEMMA4_E2B_IT.gigaModel.alias,
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
        assertEquals("Use <|turn> and <turn|> literally.", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser strips standalone control wrapper lines around plain text`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """
                <|turn>assistant
                FILES 95
                <turn|>
            """.trimIndent(),
            requestModel = LocalModelProfiles.GEMMA4_E2B_IT.gigaModel.alias,
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
        assertEquals("FILES 95", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser falls back to plain text final response`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """В календаре "Семья" на сегодня нет событий.""",
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
        assertEquals("""В календаре "Семья" на сегодня нет событий.""", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser treats result object as final response`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """{"result":"Список сообщений в почте: 7 штук."}""",
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
        assertEquals("Список сообщений в почте: 7 штук.", ok.choices.single().message.content)
    }

    @Test
    fun `strict json parser unwraps embedded result object inside final content`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """{"type":"final","content":"{\"result\":\"Список сообщений в почте: 7 штук.\"}"}""",
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
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
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
        val choice = ok.choices.single()
        assertEquals("ToolListFiles", choice.message.functionCall?.name)
        assertEquals("call_1", choice.message.functionsStateId)
        assertEquals(LLMResponse.FinishReason.function_call, choice.finishReason)
    }

    @Test
    fun `strict json parser recovers malformed tool call polluted by schema fields`() {
        val parser = LocalStrictJsonParser()

        val result = parser.parse(
            rawText = """
                {"type":"tool_calls","calls":[{"id":"call_1","name":"CalendarListEvents","arguments":{"date":"2026-03-29","calendarName":"Calendar"}],"required":["date","calendarName"]}
            """.trimIndent(),
            requestModel = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
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
            usage = LLMResponse.Usage(10, 5, 15, 0),
        )

        val ok = assertIs<LLMResponse.Chat.Ok>(result)
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
    fun `download prompt is built for gemma local models`() {
        val tempRoot = Files.createTempDirectory("souz-local-models-test")
        val store = LocalModelStore(rootDir = tempRoot)

        listOf(LocalModelProfiles.GEMMA4_E2B_IT, LocalModelProfiles.GEMMA4_E4B_IT).forEach { profile ->
            val prompt = store.downloadPromptFor(profile.gigaModel)

            assertNotNull(prompt)
            assertEquals(profile.gigaModel, prompt.model)
            assertEquals(profile, prompt.profile)
            assertEquals(store.modelPath(profile).toAbsolutePath().toString(), prompt.targetPath)
        }
    }

    @Test
    fun `download resumes from existing partial file`() = runTest {
        val tempRoot = Files.createTempDirectory("souz-local-models-test")
        val profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
        val target = tempRoot.resolve(profile.id).resolve(profile.ggufFilename)
        val tempFile = target.resolveSibling("${profile.ggufFilename}.part")
        Files.createDirectories(target.parent)
        Files.write(tempFile, "part".toByteArray())

        val requestSlot = slot<HttpRequest>()
        val httpClient = mockk<HttpClient>()
        val response = mockk<HttpResponse<InputStream>>()
        every { httpClient.send(capture(requestSlot), any<HttpResponse.BodyHandler<InputStream>>()) } returns response
        every { response.statusCode() } returns 206
        every { response.headers() } returns HttpHeaders.of(
            mapOf(
                "Content-Length" to listOf("11"),
                "Content-Range" to listOf("bytes 4-14/15"),
            )
        ) { _, _ -> true }
        every { response.body() } returns ByteArrayInputStream("ial-model!!".toByteArray())

        val store = LocalModelStore(rootDir = tempRoot, httpClient = httpClient)
        val progress = mutableListOf<Pair<Long, Long?>>()

        val result = store.download(profile) { downloadProgress ->
            progress += downloadProgress.bytesDownloaded to downloadProgress.totalBytes
        }

        assertEquals(target, result)
        assertEquals("bytes=4-", requestSlot.captured.headers().firstValue("Range").orElse(null))
        assertTrue(Files.isRegularFile(target))
        assertFalse(Files.exists(tempFile))
        assertContentEquals("partial-model!!".toByteArray(), Files.readAllBytes(target))
        assertEquals(4L, progress.first().first)
        assertEquals(15L, progress.first().second)
        assertEquals(15L, progress.last().first)
        assertEquals(15L, progress.last().second)
    }

    @Test
    fun `download restarts from zero when ranged request is unsatisfied for oversized partial file`() = runTest {
        val tempRoot = Files.createTempDirectory("souz-local-models-test")
        val profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
        val target = tempRoot.resolve(profile.id).resolve(profile.ggufFilename)
        val tempFile = target.resolveSibling("${profile.ggufFilename}.part")
        Files.createDirectories(target.parent)
        Files.write(tempFile, "stale-model".toByteArray())

        val requests = mutableListOf<HttpRequest>()
        val httpClient = mockk<HttpClient>()
        val rangeNotSatisfiable = mockk<HttpResponse<InputStream>>()
        val fullResponse = mockk<HttpResponse<InputStream>>()
        every {
            httpClient.send(
                capture(requests),
                any<HttpResponse.BodyHandler<InputStream>>(),
            )
        } returnsMany listOf(rangeNotSatisfiable, fullResponse)
        every { rangeNotSatisfiable.statusCode() } returns 416
        every { rangeNotSatisfiable.headers() } returns HttpHeaders.of(
            mapOf("Content-Range" to listOf("bytes */5"))
        ) { _, _ -> true }
        every { rangeNotSatisfiable.body() } returns ByteArrayInputStream(ByteArray(0))
        every { fullResponse.statusCode() } returns 200
        every { fullResponse.headers() } returns HttpHeaders.of(
            mapOf("Content-Length" to listOf("5"))
        ) { _, _ -> true }
        every { fullResponse.body() } returns ByteArrayInputStream("fresh".toByteArray())

        val store = LocalModelStore(rootDir = tempRoot, httpClient = httpClient)

        val result = store.download(profile)

        assertEquals(target, result)
        assertEquals(2, requests.size)
        assertEquals("bytes=11-", requests[0].headers().firstValue("Range").orElse(null))
        assertNull(requests[1].headers().firstValue("Range").orElse(null))
        assertTrue(Files.isRegularFile(target))
        assertFalse(Files.exists(tempFile))
        assertContentEquals("fresh".toByteArray(), Files.readAllBytes(target))
    }

    @Test
    fun `download cancellation keeps partial file for future resume`() = runTest {
        val tempRoot = Files.createTempDirectory("souz-local-models-test")
        val profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
        val httpClient = mockk<HttpClient>()
        val response = mockk<HttpResponse<InputStream>>()
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<InputStream>>()) } returns response
        every { response.statusCode() } returns 200
        every { response.headers() } returns HttpHeaders.of(
            mapOf("Content-Length" to listOf("5"))
        ) { _, _ -> true }
        every { response.body() } returns object : InputStream() {
            private val data = "abcde".toByteArray()
            private var index = 0

            override fun read(): Int {
                if (index >= data.size) return -1
                return data[index++].toInt() and 0xFF
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (index >= data.size) return -1
                buffer[off] = data[index++]
                return 1
            }
        }

        val store = LocalModelStore(rootDir = tempRoot, httpClient = httpClient)
        val tempFile = tempRoot.resolve(profile.id).resolve("${profile.ggufFilename}.part")

        val job = launch {
            store.download(profile) { progress ->
                if (progress.bytesDownloaded >= 1L) {
                    this.cancel(CancellationException("stop"))
                }
            }
        }

        job.join()
        assertTrue(job.isCancelled)
        assertTrue(Files.isRegularFile(tempFile))
        assertEquals(1L, Files.size(tempFile))
        assertFalse(Files.exists(tempRoot.resolve(profile.id).resolve(profile.ggufFilename)))
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
            body = LLMRequest.Chat(
                model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(LLMMessageRole.user, "Какие встречи у меня сегодня?")
                ),
                functions = listOf(calendarListEventsFn),
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
    fun `local prompt renderer switches to minimal tool signatures for large local toolsets`() {
        val renderer = LocalPromptRenderer()
        val functions = (1..60).map { index ->
            LLMRequest.Function(
                name = "Tool$index",
                description = "Tool number $index with verbose instructions that should not be copied into the local prompt when too many tools are active.",
                parameters = LLMRequest.Parameters(
                    type = "object",
                    properties = mapOf(
                        "query" to LLMRequest.Property(
                            type = "string",
                            description = "Search query for tool $index",
                        ),
                        "limit" to LLMRequest.Property(
                            type = "integer",
                            description = "Optional result limit for tool $index",
                        ),
                    ),
                    required = listOf("query"),
                ),
                fewShotExamples = listOf(
                    LLMRequest.FewShotExample(
                        request = "Use tool $index",
                        params = mapOf("query" to "Arthur", "limit" to 10),
                    )
                ),
            )
        }

        val prompt = renderer.render(
            body = LLMRequest.Chat(
                model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(LLMMessageRole.user, "Продолжи прошлое действие"),
                ),
                functions = functions,
            ),
            profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
        )

        assertTrue(prompt.contains("Tool signatures: `!` required, `?` optional."))
        assertTrue(prompt.contains("- Tool1(query!, limit:integer?)"))
        assertTrue(prompt.contains("- Tool60(query!, limit:integer?)"))
        assertFalse(prompt.contains("Example arguments JSON:"))
        assertFalse(prompt.contains("Tool number 1 with verbose instructions"))
        assertTrue(prompt.length < 8_000)
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
    fun `local chatStream completes after final response`() = runBlocking {
        val profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
        val availability = mockk<LocalProviderAvailability>()
        every { availability.status() } returns LocalProviderStatus(
            available = true,
            message = "OK",
            selectedProfile = profile,
            availableModels = listOf(profile.gigaModel),
        )

        val modelStore = mockk<LocalModelStore>()
        every { modelStore.requireAvailable(profile) } returns Path.of("/tmp/${profile.ggufFilename}")

        val promptRenderer = mockk<LocalPromptRenderer>()
        every { promptRenderer.render(any(), profile) } returns "prompt"

        val bridge = mockk<LocalNativeBridge>()
        val runtimePointer = Pointer(21)
        val modelPointer = Pointer(22)
        every { bridge.createRuntime() } returns runtimePointer
        every { bridge.loadModel(runtimePointer, any()) } returns modelPointer
        every { bridge.generateStream(runtimePointer, modelPointer, any(), any()) } returns """
            {"text":"{\"type\":\"final\",\"content\":\"stream done\"}","finish_reason":"stop","prompt_tokens":4,"completion_tokens":2,"total_tokens":6,"precached_prompt_tokens":0}
        """.trimIndent()

        val runtime = LocalLlamaRuntime(
            availability = availability,
            modelStore = modelStore,
            promptRenderer = promptRenderer,
            strictJsonParser = LocalStrictJsonParser(),
            bridge = bridge,
        )

        val responses = withTimeout(1_000) {
            runtime.chatStream(
                LLMRequest.Chat(
                    model = profile.gigaModel.alias,
                    messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
                )
            ).toList()
        }

        val ok = assertIs<LLMResponse.Chat.Ok>(responses.single())
        assertEquals("stream done", ok.choices.single().message.content)
        verify(exactly = 1) { bridge.generateStream(runtimePointer, modelPointer, any(), any()) }
    }

    @Test
    fun `local prompt renderer truncates oversized tool results for small context models`() {
        val renderer = LocalPromptRenderer()
        val oversizedResult = restJsonMapper.writeValueAsString(
            "{\"items\":[\"${"x".repeat(5_000)}\"]}"
        )

        val prompt = renderer.render(
            body = LLMRequest.Chat(
                model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
                        content = oversizedResult,
                        functionsStateId = "call_1",
                        name = "ToolTelegramReadInbox",
                    ),
                ),
            ),
            profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
        )

        assertTrue(prompt.contains("\"truncated\":true"))
        assertTrue(prompt.contains("truncated for local context window"))
        assertFalse(prompt.contains("x".repeat(4_500)))
    }

    @Test
    fun `gemma prompt renderer uses configured context budget for tool result truncation`() {
        val renderer = LocalPromptRenderer()
        val oversizedResult = restJsonMapper.writeValueAsString(
            "{\"items\":[\"${"x".repeat(5_000)}\"]}"
        )

        val prompt = renderer.render(
            body = LLMRequest.Chat(
                model = LocalModelProfiles.GEMMA4_E2B_IT.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
                        content = oversizedResult,
                        functionsStateId = "call_1",
                        name = "ToolTelegramReadInbox",
                    ),
                ),
                maxTokens = 4_096,
            ),
            profile = LocalModelProfiles.GEMMA4_E2B_IT,
        )

        assertTrue(prompt.contains("\"truncated\":true"))
        assertTrue(prompt.contains("truncated for local context window"))
        assertFalse(prompt.contains("x".repeat(4_500)))
    }

    @Test
    fun `local runtime resolves context size from configured window and profile defaults`() {
        val runtime = LocalLlamaRuntime(
            availability = mockk(relaxed = true),
            modelStore = mockk(relaxed = true),
            promptRenderer = mockk(relaxed = true),
            strictJsonParser = mockk(relaxed = true),
            bridge = mockk(relaxed = true),
        )

        assertEquals(
            4096,
            runtime.resolveContextSize(
                body = LLMRequest.Chat(
                    model = LocalModelProfiles.GEMMA4_E2B_IT.gigaModel.alias,
                    messages = emptyList(),
                    maxTokens = 4096,
                ),
                profile = LocalModelProfiles.GEMMA4_E2B_IT,
                prompt = "Короткий prompt",
            )
        )
        assertEquals(
            96_000,
            runtime.resolveContextSize(
                body = LLMRequest.Chat(
                    model = LocalModelProfiles.GEMMA4_E4B_IT.gigaModel.alias,
                    messages = emptyList(),
                    maxTokens = 256_000,
                ),
                profile = LocalModelProfiles.GEMMA4_E4B_IT,
                prompt = "x".repeat(60_000),
            )
        )
        assertEquals(
            8192,
            runtime.resolveContextSize(
                body = LLMRequest.Chat(
                    model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
                    messages = emptyList(),
                    maxTokens = 16000,
                ),
                profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
                prompt = "x".repeat(20_000),
            )
        )
        assertEquals(
            8192,
            runtime.resolveContextSize(
                body = LLMRequest.Chat(
                    model = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.gigaModel.alias,
                    messages = emptyList(),
                    maxTokens = 900,
                ),
                profile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507,
                prompt = "x".repeat(24_000),
            )
        )
    }

    @Test
    fun `configured local context window disables expansion retry and fits completion inside window`() = runTest {
        val profile = LocalModelProfiles.GEMMA4_E2B_IT
        val availability = mockk<LocalProviderAvailability>()
        every { availability.status() } returns LocalProviderStatus(
            available = true,
            message = "OK",
            selectedProfile = profile,
            availableModels = listOf(profile.gigaModel),
        )

        val modelStore = mockk<LocalModelStore>()
        every { modelStore.requireAvailable(profile) } returns Path.of("/tmp/${profile.ggufFilename}")

        val promptRenderer = mockk<LocalPromptRenderer>()
        every { promptRenderer.render(any(), profile) } returns "prompt"

        val requestSlot = slot<String>()
        val bridge = mockk<LocalNativeBridge>()
        val runtimePointer = Pointer(51)
        val modelPointer = Pointer(52)
        every { bridge.createRuntime() } returns runtimePointer
        every { bridge.loadModel(runtimePointer, any()) } returns modelPointer
        every { bridge.generate(runtimePointer, modelPointer, capture(requestSlot)) } returns """
            {"text":"{\"type\":\"final\",\"content\":\"done\"}","finish_reason":"stop","prompt_tokens":4,"completion_tokens":2,"total_tokens":6,"precached_prompt_tokens":0}
        """.trimIndent()

        val runtime = LocalLlamaRuntime(
            availability = availability,
            modelStore = modelStore,
            promptRenderer = promptRenderer,
            strictJsonParser = LocalStrictJsonParser(),
            bridge = bridge,
        )

        val response = runtime.chat(
            LLMRequest.Chat(
                model = profile.gigaModel.alias,
                messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
                maxTokens = 4096,
            )
        )

        assertIs<LLMResponse.Chat.Ok>(response)
        val request = restJsonMapper.readValue(requestSlot.captured, LocalLlamaRuntime.LocalGenerationRequest::class.java)
        assertEquals(4096, request.contextSize)
        assertEquals(1024, request.maxTokens)
        verify(exactly = 1) { bridge.generate(runtimePointer, modelPointer, any()) }
    }

    @Test
    fun `local runtime rejects unavailable local alias before model loading`() = runTest {
        val selectedProfile = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
        val unavailableProfile = LocalModelProfiles.GEMMA4_E4B_IT
        val availability = mockk<LocalProviderAvailability>()
        every { availability.status() } returns LocalProviderStatus(
            available = true,
            message = "OK",
            selectedProfile = selectedProfile,
            availableModels = listOf(
                selectedProfile.gigaModel,
                LocalModelProfiles.GEMMA4_E2B_IT.gigaModel,
            ),
        )

        val modelStore = mockk<LocalModelStore>(relaxed = true)
        val promptRenderer = mockk<LocalPromptRenderer>(relaxed = true)
        val bridge = mockk<LocalNativeBridge>(relaxed = true)

        val runtime = LocalLlamaRuntime(
            availability = availability,
            modelStore = modelStore,
            promptRenderer = promptRenderer,
            strictJsonParser = LocalStrictJsonParser(),
            bridge = bridge,
        )

        val response = runtime.chat(
            LLMRequest.Chat(
                model = unavailableProfile.gigaModel.alias,
                messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
            )
        )

        val error = assertIs<LLMResponse.Chat.Error>(response)
        assertTrue(error.message.contains(unavailableProfile.displayName))
        assertTrue(error.message.contains("unsupported on this host"))
        verify(exactly = 0) { modelStore.requireAvailable(any()) }
        verify(exactly = 0) { promptRenderer.render(any(), any()) }
        verify(exactly = 0) { bridge.createRuntime() }
        verify(exactly = 0) { bridge.loadModel(any(), any()) }
    }

    @Test
    fun `local runtime uses profile sampling defaults for gemma`() = runTest {
        val profile = LocalModelProfiles.GEMMA4_E2B_IT
        val availability = mockk<LocalProviderAvailability>()
        every { availability.status() } returns LocalProviderStatus(
            available = true,
            message = "OK",
            selectedProfile = profile,
            availableModels = listOf(profile.gigaModel),
        )

        val modelStore = mockk<LocalModelStore>()
        every { modelStore.requireAvailable(profile) } returns Path.of("/tmp/${profile.ggufFilename}")

        val promptRenderer = mockk<LocalPromptRenderer>()
        every { promptRenderer.render(any(), profile) } returns "prompt"

        val requestSlot = slot<String>()
        val bridge = mockk<LocalNativeBridge>()
        val runtimePointer = Pointer(31)
        val modelPointer = Pointer(32)
        every { bridge.createRuntime() } returns runtimePointer
        every { bridge.loadModel(runtimePointer, any()) } returns modelPointer
        every { bridge.generate(runtimePointer, modelPointer, capture(requestSlot)) } returns """
            {"text":"{\"type\":\"final\",\"content\":\"done\"}","finish_reason":"stop","prompt_tokens":4,"completion_tokens":2,"total_tokens":6,"precached_prompt_tokens":0}
        """.trimIndent()

        val runtime = LocalLlamaRuntime(
            availability = availability,
            modelStore = modelStore,
            promptRenderer = promptRenderer,
            strictJsonParser = LocalStrictJsonParser(),
            bridge = bridge,
        )

        val response = runtime.chat(
            LLMRequest.Chat(
                model = profile.gigaModel.alias,
                messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
            )
        )

        assertIs<LLMResponse.Chat.Ok>(response)
        val request = restJsonMapper.readValue(requestSlot.captured, LocalLlamaRuntime.LocalGenerationRequest::class.java)
        assertEquals(1.0f, request.temperature)
        assertEquals(0.95f, request.topP)
        assertEquals(64, request.topK)
    }

    @Test
    fun `explicit temperature overrides local profile default`() = runTest {
        val profile = LocalModelProfiles.GEMMA4_E4B_IT
        val availability = mockk<LocalProviderAvailability>()
        every { availability.status() } returns LocalProviderStatus(
            available = true,
            message = "OK",
            selectedProfile = profile,
            availableModels = listOf(profile.gigaModel),
        )

        val modelStore = mockk<LocalModelStore>()
        every { modelStore.requireAvailable(profile) } returns Path.of("/tmp/${profile.ggufFilename}")

        val promptRenderer = mockk<LocalPromptRenderer>()
        every { promptRenderer.render(any(), profile) } returns "prompt"

        val requestSlot = slot<String>()
        val bridge = mockk<LocalNativeBridge>()
        val runtimePointer = Pointer(41)
        val modelPointer = Pointer(42)
        every { bridge.createRuntime() } returns runtimePointer
        every { bridge.loadModel(runtimePointer, any()) } returns modelPointer
        every { bridge.generate(runtimePointer, modelPointer, capture(requestSlot)) } returns """
            {"text":"{\"type\":\"final\",\"content\":\"done\"}","finish_reason":"stop","prompt_tokens":4,"completion_tokens":2,"total_tokens":6,"precached_prompt_tokens":0}
        """.trimIndent()

        val runtime = LocalLlamaRuntime(
            availability = availability,
            modelStore = modelStore,
            promptRenderer = promptRenderer,
            strictJsonParser = LocalStrictJsonParser(),
            bridge = bridge,
        )

        val response = runtime.chat(
            LLMRequest.Chat(
                model = profile.gigaModel.alias,
                messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
                temperature = 0.35f,
            )
        )

        assertIs<LLMResponse.Chat.Ok>(response)
        val request = restJsonMapper.readValue(requestSlot.captured, LocalLlamaRuntime.LocalGenerationRequest::class.java)
        assertEquals(0.35f, request.temperature)
        assertEquals(0.95f, request.topP)
        assertEquals(64, request.topK)
    }

    @Test
    fun `local chat api reports unsupported features`() = runTest {
        val api = LocalChatAPI(runtime = mockk(relaxed = true))

        val embeddings = api.embeddings(LLMRequest.Embeddings(input = listOf("hello")))
        val balance = api.balance()

        assertIs<LLMResponse.Embeddings.Error>(embeddings)
        assertIs<LLMResponse.Balance.Error>(balance)
        assertFailsWith<UnsupportedOperationException> { api.uploadFile(createTempFile().toFile()) }
        assertFailsWith<UnsupportedOperationException> { api.downloadFile("file_1") }
    }
}
