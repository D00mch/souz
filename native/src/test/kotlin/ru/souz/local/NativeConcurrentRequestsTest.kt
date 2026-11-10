package ru.souz.local

import com.sun.jna.Pointer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalNativeBridge
import ru.souz.llms.local.LocalPromptRenderer
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.llms.local.LocalProviderStatus
import ru.souz.llms.local.LocalStrictJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeConcurrentRequestsTest {
    @Test
    fun `cancelled child joins native cleanup before another model uses the runtime`() = runBlocking {
        for (streaming in listOf(false, true)) withTimeout(10_000) {
            val first = LocalModelProfiles.QWEN3_4B_INSTRUCT_2507
            val second = LocalModelProfiles.GEMMA4_E2B_IT
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val availability = mockk<LocalProviderAvailability> {
                every { status() } returns LocalProviderStatus(true, "OK", first, listOf(first.gigaModel, second.gigaModel))
            }
            val store = mockk<LocalModelStore> {
                every { requireAvailable(first) } returns Path.of("/tmp/first.gguf")
                every { requireAvailable(second) } returns Path.of("/tmp/second.gguf")
            }
            val renderer = mockk<LocalPromptRenderer> { every { render(any(), any()) } returns "prompt" }
            val bridge = mockk<LocalNativeBridge>(relaxed = true)
            val runtimePointer = Pointer(1)
            val firstPointer = Pointer(2)
            val secondPointer = Pointer(3)
            every { bridge.createRuntime() } returns runtimePointer
            every { bridge.loadModel(runtimePointer, any()) } answers {
                if (secondArg<String>().contains("first.gguf")) firstPointer else secondPointer
            }
            val result = """{"text":"done","finish_reason":"stop"}"""
            fun generate(pointer: Pointer): String {
                if (pointer == firstPointer) {
                    started.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS)) { "Native cleanup was not released" }
                }
                return result
            }
            every { bridge.generate(runtimePointer, any(), any()) } answers { generate(secondArg()) }
            every { bridge.generateStream(runtimePointer, any(), any(), any()) } answers { generate(secondArg()) }
            every { bridge.cancel(runtimePointer) } answers { cancelled.complete(Unit); Unit }
            val runtime = LocalLlamaRuntime(availability, store, renderer, LocalStrictJsonParser(), bridge)
            suspend fun chat(model: String): LLMResponse.Chat {
                val body = LLMRequest.Chat(model = model, provider = LlmProvider.LOCAL,
                    messages = listOf(LLMRequest.Message(LLMMessageRole.user, "task")))
                return if (streaming) runtime.chatStream(body).single() else runtime.chat(body)
            }
            try {
                val active = async { chat(first.gigaModel.alias) }
                started.await()
                val queued = async(start = CoroutineStart.UNDISPATCHED) { chat(second.gigaModel.alias) }
                queued.cancelAndJoin()
                verify(exactly = 0) { bridge.cancel(any()) }
                val sibling = async(start = CoroutineStart.UNDISPATCHED) { chat(second.gigaModel.alias) }
                active.cancel()
                cancelled.await()
                assertFalse(active.isCompleted)
                assertFalse(sibling.isCompleted)
                verify(exactly = 1) { bridge.loadModel(runtimePointer, any()) }
                verify(exactly = 0) { bridge.unloadModel(any(), any()) }
                release.countDown()
                assertFailsWith<CancellationException> { active.await() }
                val answer = assertIs<LLMResponse.Chat.Ok>(sibling.await())
                assertEquals("done", answer.choices.single().message.content)
                verify(exactly = 2) { bridge.loadModel(runtimePointer, any()) }
                verify(exactly = 1) { bridge.unloadModel(runtimePointer, firstPointer) }
                verify(exactly = 1) { bridge.cancel(runtimePointer) }
                assertTrue(cancelled.isCompleted)
            } finally {
                release.countDown()
                runtime.shutdown()
            }
        }
    }
}
