package ru.souz.service.telegram

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import it.tdlight.client.GenericUpdateHandler
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.jni.TdApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.CompletableFuture

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TelegramHistoryCacheTest {
    private class Fixture(scope: TestScope) {
        val client = mockk<SimpleTelegramClient>()
        var activeClient = client
        val opened = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        var fetchCount = 0
        val cache = TelegramHistoryCache({ it === activeClient }) { message ->
            TelegramMessageView(message.chatId, "Fixture", message.id, null, message.date.toLong(),
                (message.content as? TdApi.MessageText)?.text?.text)
        }
        val handler = slot<GenericUpdateHandler<TdApi.Update>>()

        init {
            every { client.send(ofType<TdApi.OpenChat>()) } answers {
                opened += firstArg<TdApi.OpenChat>().chatId
                CompletableFuture.completedFuture(TdApi.Ok())
            }
            every { client.send(ofType<TdApi.CloseChat>()) } answers {
                closed += firstArg<TdApi.CloseChat>().chatId
                CompletableFuture.completedFuture(TdApi.Ok())
            }
            val builder = mockk<SimpleTelegramClientBuilder>()
            every { builder.addUpdatesHandler(capture(handler)) } just Runs
            cache.registerUpdates(builder, scope.backgroundScope) { client }
        }

        fun emit(update: TdApi.Update) = handler.captured.onUpdate(update)

        suspend fun read(
            chatId: Long = 1,
            limit: Int = 1,
            force: Boolean = false,
            fetch: suspend () -> List<TdApi.Message> = { listOf(message(chatId, 10)) },
        ) = cache.get(activeClient, chatId, limit, force) { fetchCount++; fetch() }
    }

    @Test
    fun `first cached read after idle includes delayed incoming and outgoing events without refetch`() = runTest {
        val f = Fixture(this)
        assertEquals(listOf(10L), f.read().map { it.messageId })
        assertEquals(listOf(1L), f.opened)
        assertTrue(f.closed.isEmpty())
        // No history request is running when TDLib finally delivers supergroup/channel updates.
        f.emit(TdApi.UpdateNewMessage(message(1, 12)))
        f.emit(TdApi.UpdateNewMessage(message(1, 13).apply { isOutgoing = true }))
        runCurrent()
        assertEquals(listOf(13L, 12L, 10L), f.read(limit = 3).map { it.messageId })
        assertEquals(1, f.fetchCount)
        assertTrue(f.closed.isEmpty())
        verify(exactly = 0) { f.client.send(ofType<TdApi.ViewMessages>()) }
        verify(exactly = 0) { f.client.send(ofType<TdApi.ToggleChatIsMarkedAsUnread>()) }
    }

    @Test
    fun `edits and both kinds of deletion update idle cached history in callback order`() = runTest {
        val f = Fixture(this)
        f.read(limit = 3) { listOf(message(1, 12), message(1, 11), message(1, 10)) }
        f.emit(TdApi.UpdateMessageContent(1, 12, text("edited")))
        f.emit(TdApi.UpdateMessageContent(1, 12, text("edited again")))
        f.emit(TdApi.UpdateDeleteMessages(1, longArrayOf(11), true, false))
        f.emit(TdApi.UpdateDeleteMessages(1, longArrayOf(10), false, true))
        runCurrent()
        val history = f.read()
        assertEquals(listOf(12L), history.map { it.messageId })
        assertEquals("edited again", history.single().text)
        assertEquals(1, f.fetchCount)
    }

    @Test
    fun `forced refresh replaces old history but replays new edit and delete events received during fetch`() = runTest {
        val f = Fixture(this)
        f.read(limit = 3) { listOf(message(1, 10), message(1, 9), message(1, 8)) }
        val fetched = CompletableDeferred<List<TdApi.Message>>()
        val request = async { f.read(limit = 3, force = true) { fetched.await() } }
        runCurrent()
        f.emit(TdApi.UpdateNewMessage(message(1, 12)))
        f.emit(TdApi.UpdateMessageContent(1, 10, text("latest edit")))
        f.emit(TdApi.UpdateDeleteMessages(1, longArrayOf(9), true, false))
        runCurrent()
        fetched.complete(listOf(message(1, 10, "stale edit"), message(1, 9)))
        val result = request.await()
        assertEquals(listOf(12L, 10L), result.map { it.messageId })
        assertEquals("latest edit", result.last().text)
        assertEquals(result, f.read(limit = 2))
        assertEquals(2, f.fetchCount)
    }

    @Test
    fun `ordinary fetch merges and deduplicates while forced empty refresh replaces`() = runTest {
        val f = Fixture(this)
        f.read { listOf(message(1, 10)) }
        assertEquals(listOf(11L, 10L), f.read(limit = 3) {
            listOf(message(1, 11), message(1, 11))
        }.map { it.messageId })
        assertTrue(f.read(force = true) { emptyList() }.isEmpty())
        assertEquals(listOf(1L), f.opened)
        assertTrue(f.closed.isEmpty())
    }

    @Test
    fun `cold fetch receives delayed updates without losing older fetched messages`() = runTest {
        val f = Fixture(this)
        val fetched = CompletableDeferred<List<TdApi.Message>>()
        val request = async { f.read(limit = 3) { fetched.await() } }
        runCurrent()
        f.emit(TdApi.UpdateNewMessage(message(1, 12)))
        f.emit(TdApi.UpdateMessageContent(1, 11, text("cold edit")))
        f.emit(TdApi.UpdateDeleteMessages(1, longArrayOf(10), true, false))
        runCurrent()
        fetched.complete(listOf(message(1, 11), message(1, 10), message(1, 9)))
        val result = request.await()
        assertEquals(listOf(12L, 11L, 9L), result.map { it.messageId })
        assertEquals("cold edit", result[1].text)
    }

    @Test
    fun `LRU ignores unrelated updates and closes evicted subscriptions with their owning client`() = runTest {
        val f = Fixture(this)
        for (id in 1L..200L) f.read(chatId = id)
        f.emit(TdApi.UpdateNewMessage(message(999, 15)))
        // Background traffic must not pin the least recently used entry either.
        f.emit(TdApi.UpdateNewMessage(message(2, 15)))
        runCurrent()
        f.read(chatId = 1)
        f.read(chatId = 201)
        assertEquals(listOf(2L), f.closed)
        assertEquals(201, f.opened.size)
        assertTrue(999L !in f.opened)
        val oldClient = f.client
        f.activeClient = mockk()
        f.cache.clear()
        assertEquals(f.opened.toSet(), f.closed.toSet())
        verify(exactly = 201) { oldClient.send(ofType<TdApi.CloseChat>()) }
        verify(exactly = 0) { f.activeClient.send(ofType<TdApi.CloseChat>()) }
    }

    @Test
    fun `message cache remains bounded under an idle update stream`() = runTest {
        val f = Fixture(this)
        f.read()
        for (id in 11L..520L) f.emit(TdApi.UpdateNewMessage(message(1, id)))
        runCurrent()
        val history = f.read(limit = 500)
        assertEquals(500, history.size)
        assertEquals(520L, history.first().messageId)
        assertEquals(21L, history.last().messageId)
        assertEquals(1, f.fetchCount)
    }

    @Test
    fun `failed or cancelled cold reads release subscriptions and allow retry`() = runTest {
        val f = Fixture(this)
        assertFailsWith<IllegalStateException> { f.read { error("fixture error") } }
        assertEquals(listOf(1L), f.closed)
        val waiting = CompletableDeferred<List<TdApi.Message>>()
        val request = async { f.read { waiting.await() } }
        runCurrent()
        request.cancelAndJoin()
        assertEquals(listOf(1L, 1L), f.closed)
        assertEquals(10L, f.read().single().messageId)
        assertEquals(3, f.opened.size)
    }

    @Test
    fun `failed and cancelled forced reads retain cache including concurrent updates`() = runTest {
        val f = Fixture(this)
        f.read()
        assertFailsWith<IllegalStateException> { f.read(force = true) { error("fixture error") } }
        val waiting = CompletableDeferred<List<TdApi.Message>>()
        val request = async { f.read(force = true) { waiting.await() } }
        runCurrent()
        f.emit(TdApi.UpdateNewMessage(message(1, 12)))
        runCurrent()
        request.cancelAndJoin()
        assertEquals(listOf(12L, 10L), f.read(limit = 2).map { it.messageId })
        assertTrue(f.closed.isEmpty())
    }

    @Test
    fun `reset rejects old fetches and delayed callbacks after a client switch`() = runTest {
        val f = Fixture(this)
        val waiting = CompletableDeferred<List<TdApi.Message>>()
        val request = async {
            assertFailsWith<IllegalStateException> { f.read { waiting.await() } }
        }
        runCurrent()
        f.cache.clear()
        val replacement = mockk<SimpleTelegramClient>()
        every { replacement.send(ofType<TdApi.OpenChat>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        every { replacement.send(ofType<TdApi.CloseChat>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        f.activeClient = replacement
        assertEquals(20L, f.read { listOf(message(1, 20)) }.single().messageId)
        f.emit(TdApi.UpdateNewMessage(message(1, 99)))
        runCurrent()
        waiting.complete(listOf(message(1, 88)))
        request.await()
        assertEquals(20L, f.read().single().messageId)
        f.cache.onUpdate(replacement, TdApi.UpdateNewMessage(message(1, 21)))
        assertEquals(21L, f.read().single().messageId)
        assertEquals(listOf(1L), f.closed)
    }

    @Test
    fun `outgoing send completion replaces temporary message without a duplicate`() = runTest {
        val f = Fixture(this)
        f.read()
        f.emit(TdApi.UpdateNewMessage(message(1, 19).apply { isOutgoing = true }))
        f.emit(TdApi.UpdateMessageSendSucceeded(message(1, 20).apply { isOutgoing = true }, 19))
        runCurrent()
        assertEquals(listOf(20L, 10L), f.read(limit = 2).map { it.messageId })
    }

    @Test
    fun `cancelling a pending open releases it once TDLib completes`() = runTest {
        val f = Fixture(this)
        val opened = CompletableFuture<TdApi.Ok>()
        every { f.client.send(ofType<TdApi.OpenChat>()) } returns opened
        val request = async { f.read() }
        runCurrent()
        request.cancel()
        opened.complete(TdApi.Ok())
        request.join()
        assertEquals(listOf(1L), f.closed)
        assertEquals(0, f.fetchCount)
    }

    @Test
    fun `uncertain open stays owned until its close succeeds before retrying open`() = runTest {
        val f = Fixture(this)
        every { f.client.send(ofType<TdApi.OpenChat>()) } returns
            CompletableFuture.failedFuture(IllegalStateException("open fixture error"))
        every { f.client.send(ofType<TdApi.CloseChat>()) } returns
            CompletableFuture.failedFuture(IllegalStateException("close fixture error"))
        assertFailsWith<IllegalStateException> { f.read() }
        // Another open is unsafe while the previous subscription's state is unknown.
        assertFailsWith<IllegalStateException> { f.read() }
        verify(exactly = 1) { f.client.send(ofType<TdApi.OpenChat>()) }
        every { f.client.send(ofType<TdApi.CloseChat>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        every { f.client.send(ofType<TdApi.OpenChat>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        assertEquals(10L, f.read().single().messageId)
        verify(exactly = 2) { f.client.send(ofType<TdApi.OpenChat>()) }
    }

    @Test
    fun `failed eviction does not exceed subscription capacity`() = runTest {
        val f = Fixture(this)
        for (id in 1L..200L) f.read(chatId = id)
        every { f.client.send(ofType<TdApi.CloseChat>()) } returns
            CompletableFuture.failedFuture(IllegalStateException("close fixture error"))
        assertFailsWith<IllegalStateException> { f.read(chatId = 201) }
        assertEquals(200, f.opened.size)
        every { f.client.send(ofType<TdApi.CloseChat>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        f.read(chatId = 201)
        assertEquals(201, f.opened.size)
    }

    @Test
    fun `overlapping refreshes serialize snapshots without blocking idle events`() = runTest {
        val f = Fixture(this)
        f.read()
        val firstPage = CompletableDeferred<List<TdApi.Message>>()
        val first = async { f.read(force = true) { firstPage.await() } }
        runCurrent()
        val second = async { f.read(force = true) { listOf(message(1, 13)) } }
        runCurrent()
        assertEquals(2, f.fetchCount)
        f.emit(TdApi.UpdateNewMessage(message(1, 12)))
        runCurrent()
        firstPage.complete(listOf(message(1, 11)))
        assertEquals(12L, first.await().single().messageId)
        assertEquals(13L, second.await().single().messageId)
        assertEquals(3, f.fetchCount)
        assertTrue(f.closed.isEmpty())
    }

    @Test
    fun `history deletion invalidates cached and in-flight data even when closing fails`() = runTest {
        val f = Fixture(this)
        f.read()
        val fetched = CompletableDeferred<List<TdApi.Message>>()
        val refresh = async {
            assertFailsWith<IllegalStateException> { f.read(force = true) { fetched.await() } }
        }
        runCurrent()
        every { f.client.send(ofType<TdApi.CloseChat>()) } returns
            CompletableFuture.failedFuture(IllegalStateException("close fixture error"))
        assertFailsWith<IllegalStateException> { f.cache.remove(1) }
        fetched.complete(listOf(message(1, 10)))
        refresh.await()
        every { f.client.send(ofType<TdApi.CloseChat>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        assertTrue(f.read { emptyList() }.isEmpty())
        assertEquals(2, f.opened.size)
    }

    companion object {
        private fun text(value: String) = TdApi.MessageText().apply {
            text = TdApi.FormattedText(value, emptyArray())
        }

        private fun message(chatId: Long, id: Long, body: String = "fixture $id") = TdApi.Message().apply {
            this.chatId = chatId
            this.id = id
            content = text(body)
        }
    }
}
