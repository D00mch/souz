package giga

import com.dumch.giga.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class GigaAgentTest {
    @Test
    fun `singleResponse emits assistant reply`() = runBlocking {
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
        coEvery { api.message(any()) } returnsMany listOf(classifyResponse, response)

        val agent = GigaAgent(
            userMessages = flowOf("hi"),
            api = api,
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

        val fnCallMsg = GigaResponse.Message(
            content = "",
            role = GigaMessageRole.assistant,
            functionCall = GigaResponse.FunctionCall(
                name = "ListFiles",
                arguments = mapOf("path" to "src/test/resources"),
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
            settings = GigaAgent.Settings(
                toolsByCategory = mapOf(
                    GigaAgent.ToolCategory.IO to mapOf("ListFiles" to dummyTool("ListFiles"))
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
            settings = GigaAgent.Settings(toolsByCategory = emptyMap(), model = GigaModel.Pro, stream = true),
        )
        val results = agent.run().toList()

        assertEquals(listOf("streamed"), results)
        coVerify(exactly = 1) { api.messageStream(any()) }
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
            userMessages = flowOf("создай файл"),
            api = api,
            settings = GigaAgent.Settings(
                toolsByCategory = mapOf(
                    GigaAgent.ToolCategory.IO to mapOf("ListFiles" to dummyTool("ListFiles")),
                    GigaAgent.ToolCategory.BROWSER to mapOf("OpenUrl" to dummyTool("OpenUrl"))
                ),
                model = GigaModel.Pro,
                stream = false,
            ),
        )
        val outputs = agent.run().toList()

        assertEquals(listOf("done"), outputs)
        // second body is chat request; it should include only IO functions
        val fnNames = bodies[1].functions.map { it.name }
        assertEquals(listOf("ListFiles"), fnNames)
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

