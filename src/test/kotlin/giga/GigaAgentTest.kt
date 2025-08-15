package giga

import com.dumch.giga.*
import com.dumch.tool.desktop.ToolMouseClickMac
import com.dumch.tool.desktop.ToolHotkeyMac
import com.dumch.tool.desktop.ToolDesktopScreenShot
import com.dumch.tool.desktop.ToolMinimizeWindows
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.isAccessible
import java.io.File
import java.util.ArrayDeque

class GigaAgentTest {
    private fun okResponse(text: String) = GigaResponse.Chat.Ok(
        choices = listOf(
            GigaResponse.Choice(
                message = GigaResponse.Message(
                    content = text,
                    role = GigaMessageRole.assistant,
                    functionCall = null,
                    functionsStateId = null,
                ),
                index = 0,
                finishReason = GigaResponse.FinishReason.stop,
            )
        ),
        created = 0L,
        model = "GigaChat-Pro",
        usage = GigaResponse.Usage(0, 0, 0, 0),
    )

    private fun dummyTool(name: String) = object : GigaToolSetup {
        override val fn = GigaRequest.Function(
            name = name,
            description = name,
            parameters = GigaRequest.Parameters("object", emptyMap()),
            returnParameters = GigaRequest.Parameters("object", emptyMap())
        )
        override suspend fun invoke(functionCall: GigaResponse.FunctionCall): GigaRequest.Message =
            GigaRequest.Message(GigaMessageRole.function, "{}")
    }

    @BeforeTest
    fun setupTools() {
        mockkConstructor(ToolMouseClickMac::class)
        every { anyConstructed<ToolMouseClickMac>().toGiga() } returns dummyTool("MouseClick")

        mockkConstructor(ToolHotkeyMac::class)
        every { anyConstructed<ToolHotkeyMac>().toGiga() } returns dummyTool("Hotkey")

        mockkConstructor(ToolDesktopScreenShot::class)
        every { anyConstructed<ToolDesktopScreenShot>().toGiga() } returns dummyTool("ScreenShot")

        mockkConstructor(ToolMinimizeWindows::class)
        every { anyConstructed<ToolMinimizeWindows>().toGiga() } returns dummyTool("MinimizeWindows")
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `run emits assistant response in non-stream mode`() = runBlocking {
        val api = object : GigaChatAPI {
            override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = okResponse("hello")
            override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = emptyFlow()
            override suspend fun uploadImage(file: File): GigaResponse.UploadFile = error("unused")
        }
        val agent = GigaAgent(
            userMessages = flowOf("hi"),
            api = api,
            settings = GigaAgent.Settings(functions = emptyMap(), model = GigaModel.Pro, stream = false)
        )

        val outputs = agent.run().toList()
        assertEquals(listOf("hello"), outputs)
    }

    @Test
    fun `trySummarize resets conversation and appends summary`() = runBlocking {
        val api = object : GigaChatAPI {
            override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = okResponse("summary")
            override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = emptyFlow()
            override suspend fun uploadImage(file: File): GigaResponse.UploadFile = error("unused")
        }
        val agent = GigaAgent(
            userMessages = emptyFlow(),
            api = api,
            settings = GigaAgent.Settings(functions = emptyMap(), model = GigaModel.Max, stream = false)
        )
        val conversation = ArrayDeque<GigaRequest.Message>().apply {
            add(GigaRequest.Message(GigaMessageRole.user, "hello"))
        }

        val method = GigaAgent::class.declaredFunctions.first { it.name == "trySummarize" }
        method.isAccessible = true
        method.callSuspend(agent, 8000, conversation)

        assertEquals(2, conversation.size)
        assertEquals(GigaMessageRole.system, conversation.first().role)
        assertEquals("summary", conversation.last().content)
    }
}

