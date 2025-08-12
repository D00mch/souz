package giga

import com.dumch.giga.GigaGRPCChatApi
import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaMessageRole
import com.dumch.giga.GigaRequest
import com.dumch.giga.GigaResponse
import gigachat.v1.ChatServiceGrpcKt
import gigachat.v1.Gigachatv1
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GigaGRPCChatApiTest {
    private val authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    private fun sampleResponse(): Gigachatv1.ChatResponse {
        val msg = Gigachatv1.Message.newBuilder()
            .setRole("assistant")
            .setContent("response")
            .build()
        val alt = Gigachatv1.Alternative.newBuilder()
            .setMessage(msg)
            .setIndex(0)
            .setFinishReason("stop")
            .build()
        val usage = Gigachatv1.Usage.newBuilder()
            .setPromptTokens(1)
            .setCompletionTokens(2)
            .setTotalTokens(3)
            .build()
        return Gigachatv1.ChatResponse.newBuilder()
            .addAlternatives(alt)
            .setTimestamp(123L)
            .setModelInfo(Gigachatv1.ModelInfo.newBuilder().setName("GigaChat-Pro").build())
            .setUsage(usage)
            .build()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        System.clearProperty("GIGA_ACCESS_TOKEN")
    }

    @Test
    fun `retries once on UNAUTHENTICATED and returns mapped response`() = runBlocking {
        mockkObject(GigaAuth)
        coEvery { GigaAuth.requestToken(any(), any()) } returns "token2"

        val stub = mockk<ChatServiceGrpcKt.ChatServiceCoroutineStub>()
        val headers = mutableListOf<Metadata>()
        val response = sampleResponse()
        var calls = 0
        coEvery { stub.chat(any(), capture(headers)) } answers {
            calls++
            if (calls == 1) throw StatusRuntimeException(Status.UNAUTHENTICATED)
            response
        }

        val api = GigaGRPCChatApi(GigaAuth)
        val field = GigaGRPCChatApi::class.java.getDeclaredField("stub").apply { isAccessible = true }
        field.set(api, stub)

        System.setProperty("GIGA_ACCESS_TOKEN", "token1")
        val body = GigaRequest.Chat(
            model = "GigaChat-Pro",
            messages = listOf(GigaRequest.Message(GigaMessageRole.user, "hi"))
        )

        val result = api.message(body) as GigaResponse.Chat.Ok

        assertEquals(2, calls)
        assertEquals("Bearer token1", headers[0].get(authKey))
        assertEquals("Bearer token2", headers[1].get(authKey))
        assertEquals("response", result.choices.first().message.content)
    }

    @Test
    fun `propagates after second UNAUTHENTICATED`() = runBlocking {
        mockkObject(GigaAuth)
        coEvery { GigaAuth.requestToken(any(), any()) } returns "token2"

        val stub = mockk<ChatServiceGrpcKt.ChatServiceCoroutineStub>()
        var calls = 0
        coEvery { stub.chat(any(), any()) } answers {
            calls++
            throw StatusRuntimeException(Status.UNAUTHENTICATED)
        }

        val api = GigaGRPCChatApi(GigaAuth)
        val field = GigaGRPCChatApi::class.java.getDeclaredField("stub").apply { isAccessible = true }
        field.set(api, stub)

        System.setProperty("GIGA_ACCESS_TOKEN", "token1")
        val body = GigaRequest.Chat(
            model = "GigaChat-Pro",
            messages = listOf(GigaRequest.Message(GigaMessageRole.user, "hi"))
        )

        assertFailsWith<StatusRuntimeException> { api.message(body) }
        assertEquals(2, calls)
    }

    @Test
    fun `returns mapped response`() = runBlocking {
        mockkObject(GigaAuth)
        val stub = mockk<ChatServiceGrpcKt.ChatServiceCoroutineStub>()
        val headers = mutableListOf<Metadata>()
        val response = sampleResponse()
        coEvery { stub.chat(any(), capture(headers)) } returns response

        val api = GigaGRPCChatApi(GigaAuth)
        val field = GigaGRPCChatApi::class.java.getDeclaredField("stub").apply { isAccessible = true }
        field.set(api, stub)

        System.setProperty("GIGA_ACCESS_TOKEN", "token1")
        val body = GigaRequest.Chat(
            model = "GigaChat-Pro",
            messages = listOf(GigaRequest.Message(GigaMessageRole.user, "hi"))
        )

        val result = api.message(body) as GigaResponse.Chat.Ok

        assertEquals("response", result.choices.first().message.content)
        assertEquals(GigaMessageRole.assistant, result.choices.first().message.role)
        assertEquals(123L, result.created)
        assertEquals("GigaChat-Pro", result.model)
        assertEquals(1, result.usage.promptTokens)
        assertEquals("Bearer token1", headers[0].get(authKey))
        coVerify(exactly = 0) { GigaAuth.requestToken(any(), any()) }
    }
}
