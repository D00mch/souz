package giga

import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.giga.*
import ru.gigadesk.tool.ToolCategory
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import ru.gigadesk.agent.nodes.NodesClassification
import ru.gigadesk.di.mainDiModule
import kotlin.getValue
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

@Ignore // we don't use GigaAgent anymore
class GigaAgentTest {
    private val di = DI.invoke { import(mainDiModule) }
    val nodesClassification: NodesClassification by di.instance()

    @Test
    fun `singleResponse emits assistant reply`() = runBlocking {
        val api = mockk<GigaChatAPI>()
        val usage = GigaResponse.Usage(1, 1, 2, 0)
        val classifyMsg = GigaResponse.Message(
            content = "files",
            role = GigaMessageRole.assistant,
            functionCall = null,
            functionsStateId = null,
        )
        val classifyResponse = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(classifyMsg, 0, GigaResponse.FinishReason.stop)),
            created = 0L,
            model = "m",
            usage = usage,
        )
        val msg = GigaResponse.Message(
            content = "hello",
            role = GigaMessageRole.assistant,
            functionCall = null,
            functionsStateId = null,
        )
        val response = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(msg, 0, GigaResponse.FinishReason.stop)),
            created = 0L,
            model = "m",
            usage = usage,
        )
        coEvery { api.message(any()) } returnsMany listOf(classifyResponse, response)

        val agent = GigaAgent(
            userMessages = flowOf("hi"),
            api = api,
            ragRepo = mockRagRepo(),
            nodesClassification = nodesClassification,
            settings = GigaAgent.Settings(toolsByCategory = emptyMap(), model = GigaModel.Pro, stream = false),
        )
        val results = agent.run().toList()

        assertEquals(listOf("hello"), results)
        coVerify(exactly = 2) { api.message(any()) }
    }

    @Test
    fun `executes tool and sends result back to api`() = runBlocking {
        val api = mockk<GigaChatAPI>()
        val usage = GigaResponse.Usage(1, 1, 2, 0)
        val classifyMsg = GigaResponse.Message(
            content = "coder",
            role = GigaMessageRole.assistant,
            functionCall = null,
            functionsStateId = null,
        )
        val classifyResponse = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(classifyMsg, 0, GigaResponse.FinishReason.stop)),
            created = 0L,
            model = "m",
            usage = usage,
        )

        val fnCallMsg = GigaResponse.Message(
            content = "",
            role = GigaMessageRole.assistant,
            functionCall = GigaResponse.FunctionCall(
                name = "ListFiles",
                arguments = mapOf("path" to "src/jvmTest/resources"),
            ),
            functionsStateId = "1",
        )
        val fnResponse = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(fnCallMsg, 0, GigaResponse.FinishReason.function_call)),
            created = 0L,
            model = "m",
            usage = usage,
        )
        val finalMsg = GigaResponse.Message(
            content = "done",
            role = GigaMessageRole.assistant,
            functionCall = null,
            functionsStateId = null,
        )
        val finalResponse = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(finalMsg, 0, GigaResponse.FinishReason.stop)),
            created = 0L,
            model = "m",
            usage = usage,
        )

        val bodies = mutableListOf<GigaRequest.Chat>()
        coEvery { api.message(capture(bodies)) } returnsMany listOf(classifyResponse, fnResponse, finalResponse)

        val agent = GigaAgent(
            userMessages = flowOf("list"),
            api = api,
            ragRepo = mockRagRepo(),
            nodesClassification = nodesClassification,
            settings = GigaAgent.Settings(
                toolsByCategory = mapOf(
                    ToolCategory.FILES to mapOf("ListFiles" to dummyTool("ListFiles"))
                ),
                model = GigaModel.Pro,
                stream = false,
            ),
        )
        val outputs = agent.run().toList().also {
            coVerify(exactly = 3) { api.message(any()) }
        }

        assertEquals(listOf("done"), outputs)
        val fnResult = bodies[2].messages[bodies[2].messages.size - 2]
        assertEquals(GigaMessageRole.function, fnResult.role)
        assertEquals("{}", fnResult.content)
    }

    @Test
    fun `stream mode uses messageStream`() = runBlocking {
        val api = mockk<GigaChatAPI>()
        val usage = GigaResponse.Usage(1, 1, 2, 0)
        val classifyMsg = GigaResponse.Message(
            content = "coder",
            role = GigaMessageRole.assistant,
            functionCall = null,
            functionsStateId = null,
        )
        val classifyResponse = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(classifyMsg, 0, GigaResponse.FinishReason.stop)),
            created = 0L,
            model = "m",
            usage = usage,
        )
        val msg = GigaResponse.Message(
            content = "streamed",
            role = GigaMessageRole.assistant,
            functionCall = null,
            functionsStateId = null,
        )
        val response = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(msg, 0, GigaResponse.FinishReason.stop)),
            created = 0L,
            model = "m",
            usage = usage,
        )
        coEvery { api.message(any()) } returns classifyResponse
        coEvery { api.messageStream(any()) } returns flowOf(response)

        val agent = GigaAgent(
            userMessages = flowOf("hi"),
            api = api,
            ragRepo = mockRagRepo(),
            nodesClassification = nodesClassification,
            settings = GigaAgent.Settings(toolsByCategory = emptyMap(), model = GigaModel.Pro, stream = true),
        )
        val results = agent.run().toList()

        assertEquals(listOf("streamed"), results)
        coVerify(exactly = 1) {
            @Suppress("UnusedFlow")
            api.messageStream(any())
        }
        coVerify(exactly = 1) { api.message(any()) }
    }

    @Test
    fun `falls back to local classifier on api error`() = runBlocking {
        val api = mockk<GigaChatAPI>()
        val usage = GigaResponse.Usage(1, 1, 2, 0)
        val msg = GigaResponse.Message(
            content = "done",
            role = GigaMessageRole.assistant,
            functionCall = null,
            functionsStateId = null,
        )
        val response = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(msg, 0, GigaResponse.FinishReason.stop)),
            created = 0L,
            model = "m",
            usage = usage,
        )
        val bodies = mutableListOf<GigaRequest.Chat>()
        coEvery { api.message(capture(bodies)) } returnsMany listOf(
            GigaResponse.Chat.Error(500, "fail"),
            response,
        )

        val agent = GigaAgent(
            userMessages = flowOf("создай файл readme"),
            api = api,
            ragRepo = mockRagRepo(),
            nodesClassification = nodesClassification,
            settings = GigaAgent.Settings(
                toolsByCategory = mapOf(
                    ToolCategory.FILES to mapOf("ListFiles" to dummyTool("ListFiles")),
                    ToolCategory.BROWSER to mapOf("OpenUrl" to dummyTool("OpenUrl"))
                ),
                model = GigaModel.Pro,
                stream = false,
            ),
        )
        val outputs = agent.run().toList()

        assertEquals(listOf("done"), outputs)
        // second body is chat request; it should include only CODER functions
        val fnNames = bodies[1].functions.map { it.name }
        assertEquals(listOf("ListFiles", "OpenUrl"), fnNames)
    }

    @Test
    fun `passes temperature from settings`() = runBlocking {
        val api = mockk<GigaChatAPI>()
        val usage = GigaResponse.Usage(1, 1, 2, 0)
        val classifyMsg = GigaResponse.Message(
            content = "io",
            role = GigaMessageRole.assistant,
            functionCall = null,
            functionsStateId = null,
        )
        val classifyResponse = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(classifyMsg, 0, GigaResponse.FinishReason.stop)),
            created = 0L,
            model = "m",
            usage = usage,
        )
        val msg = GigaResponse.Message(
            content = "hello",
            role = GigaMessageRole.assistant,
            functionCall = null,
            functionsStateId = null,
        )
        val response = GigaResponse.Chat.Ok(
            choices = listOf(GigaResponse.Choice(msg, 0, GigaResponse.FinishReason.stop)),
            created = 0L,
            model = "m",
            usage = usage,
        )
        val bodies = mutableListOf<GigaRequest.Chat>()
        coEvery { api.message(capture(bodies)) } returnsMany listOf(classifyResponse, response)

        val agent = GigaAgent(
            userMessages = flowOf("hi"),
            api = api,
            ragRepo = mockRagRepo(),
            nodesClassification = nodesClassification,
            settings = GigaAgent.Settings(
                toolsByCategory = emptyMap(),
                model = GigaModel.Pro,
                stream = false,
                temperature = 0.33f,
            ),
        )

        agent.run().toList()

        assertEquals(0.33f, bodies.last().temperature)
    }

    @Test
    fun `squeezeTexts merges function call and text`() {
        val agent = GigaAgent(
            userMessages = flowOf<String>(),
            api = mockk(),
            ragRepo = mockRagRepo(),
            nodesClassification = nodesClassification,
            settings = GigaAgent.Settings(toolsByCategory = emptyMap(), model = GigaModel.Pro, stream = true),
        )
        val conversation = ArrayDeque<GigaRequest.Message>().apply {
            add(GigaRequest.Message(GigaMessageRole.user, "u1"))
            add(
                GigaRequest.Message(
                    GigaMessageRole.assistant,
                    "fn",
                    functionsStateId = "id1"
                )
            )
            add(
                GigaRequest.Message(
                    GigaMessageRole.assistant,
                    "text",
                    functionsStateId = null
                )
            )
            add(GigaRequest.Message(GigaMessageRole.user, "u2"))
        }
        val m = GigaAgent::class.java.getDeclaredMethod("squeezeTexts", ArrayDeque::class.java)
        m.isAccessible = true
        m.invoke(agent, conversation)

        assertEquals(3, conversation.size)
        val assistant = conversation.elementAt(1)
        assertEquals("fn\ntext", assistant.content)
        assertEquals("id1", assistant.functionsStateId)
    }

    @Test
    fun `squeezeTexts merges consecutive assistant texts`() {
        val agent = GigaAgent(
            userMessages = flowOf<String>(),
            api = mockk(),
            ragRepo = mockRagRepo(),
            nodesClassification = nodesClassification,
            settings = GigaAgent.Settings(toolsByCategory = emptyMap(), model = GigaModel.Pro, stream = true),
        )
        val conversation = ArrayDeque<GigaRequest.Message>().apply {
            add(GigaRequest.Message(GigaMessageRole.user, "u"))
            add(GigaRequest.Message(GigaMessageRole.assistant, "a1"))
            add(GigaRequest.Message(GigaMessageRole.assistant, "a2"))
            add(GigaRequest.Message(GigaMessageRole.assistant, "a3"))
        }
        val m = GigaAgent::class.java.getDeclaredMethod("squeezeTexts", ArrayDeque::class.java)
        m.isAccessible = true
        m.invoke(agent, conversation)

        assertEquals(2, conversation.size)
        assertEquals("a1\na2\na3", conversation.last().content)
    }

    private fun mockRagRepo(): DesktopInfoRepository = mockk<DesktopInfoRepository> {
        coEvery { search(any(), any()) } returns emptyList()
    }

    private fun dummyTool(name: String): GigaToolSetup = object : GigaToolSetup {
        override val fn: GigaRequest.Function = GigaRequest.Function(
            name = name,
            description = "",
            parameters = GigaRequest.Parameters(
                type = "object",
                properties = emptyMap(),
            ),
            returnParameters = GigaRequest.Parameters(
                type = "object",
                properties = emptyMap(),
            )
        )

        override suspend fun invoke(functionCall: GigaResponse.FunctionCall): GigaRequest.Message {
            return GigaRequest.Message(
                role = GigaMessageRole.function,
                content = "{}",
            )
        }
    }
}

