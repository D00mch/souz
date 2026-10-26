package ru.souz.backend.memory.hindsight

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationId
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryOwnerId

class HindsightCaptureBarrierTest {
    @Test
    fun `completed owners leave no capture queues after successful or failed IO`() = runBlocking {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.InternalServerError, null)) {
            HttpClient(MockEngine {
                if (status == null) throw IOException("simulated transport failure")
                respond("""{"success":true}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { providerHttpClientDefaults() }.use { client ->
                val memory = HindsightConversationMemoryRuntime(client, "http://hindsight.test")
                repeat(200) { index ->
                    val owner = "owner-$index"
                    memory.captureCompletedTurn(turn(owner))
                    assertEquals(0, memory.captureQueueCount())
                    val deleted = try {
                        memory.forgetAll(owner) {}
                        true
                    } catch (_: HindsightHttpFailure) {
                        false
                    } catch (_: IOException) {
                        false
                    }
                    assertEquals(status == HttpStatusCode.OK, deleted)
                    assertEquals(0, memory.captureQueueCount())
                }
            }
        }
    }

    @Test
    fun `deletion discards concurrent completed turns only on success and only for its owner`() = runBlocking {
        for (failure in listOf(null, "history", "delete")) {
            val retained = CopyOnWriteArrayList<String>()
            HttpClient(MockEngine { request ->
                if (request.method == HttpMethod.Post) retained += request.url.encodedPath
                respond("""{"success":true}""",
                    if (failure == "delete" && request.method == HttpMethod.Delete) HttpStatusCode.InternalServerError
                    else HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }) { providerHttpClientDefaults() }.use { client ->
                val memory = HindsightConversationMemoryRuntime(client, "http://hindsight.test")
                val deletingHistory = CompletableDeferred<Unit>()
                val releaseHistory = CompletableDeferred<Unit>()
                val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        memory.forgetAll("owner") {
                            deletingHistory.complete(Unit)
                            releaseHistory.await()
                            check(failure != "history") { "History capture did not drain" }
                        }
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IllegalStateException) {
                        false
                    }
                }
                deletingHistory.await()
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { memory.captureCompletedTurn(turn("owner")) }
                cancelled.cancelAndJoin()
                // Cancelling a waiter must not evict the active deletion's barrier.
                assertEquals(1, memory.captureQueueCount())
                val queued = async(start = CoroutineStart.UNDISPATCHED) { memory.captureCompletedTurn(turn("owner")) }
                assertFalse(queued.isCompleted)
                memory.captureCompletedTurn(turn("other"))
                assertEquals(listOf("/v1/default/banks/other/memories"), retained.toList())
                releaseHistory.complete(Unit)
                assertEquals(failure == null, deletion.await())
                queued.await()
                assertEquals(if (failure == null) 1 else 2, retained.size)
                assertEquals(0, memory.captureQueueCount())

                // A later capture is allowed and is also a barrier for discarded queued work.
                memory.captureCompletedTurn(turn("owner"))
                assertEquals(if (failure == null) 2 else 3, retained.size)
                assertTrue(retained.last().contains("/owner/"))
                assertEquals(0, memory.captureQueueCount())
            }
        }
    }

    @Test
    fun `cancelling a deletion releases its idle owner queue`() = runBlocking {
        HttpClient(MockEngine { error("No HTTP request expected") }).use { client ->
            val memory = HindsightConversationMemoryRuntime(client, "http://hindsight.test")
            val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                memory.forgetAll("owner") { CompletableDeferred<Unit>().await() }
            }
            assertEquals(1, memory.captureQueueCount())
            deletion.cancelAndJoin()
            assertEquals(0, memory.captureQueueCount())
        }
    }
}

// Inspect retained coordinator state without adding a production API solely for leak testing.
private fun HindsightConversationMemoryRuntime.captureQueueCount(): Int =
    (javaClass.getDeclaredField("completedTurnCaptures").apply { isAccessible = true }.get(this) as Map<*, *>).size

private fun turn(owner: String) = CompletedTurnMemoryInput(
    context = MemoryContext(MemoryOwnerId(owner), ConversationId("another-chat"), null, null),
    conversationId = "another-chat", userMessageId = "message", assistantMessageId = null,
    userMessage = "I prefer trains", assistantMessage = "Understood",
)
