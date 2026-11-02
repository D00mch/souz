package ru.souz.service.telegram

import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.jni.TdApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private const val MAX_HISTORY_CHATS = 200
internal const val TELEGRAM_MAX_HISTORY_LIMIT = 500

/** Open chats belong to requested histories, independently of the chat lookup cache. */
internal class TelegramHistoryCache(
    private val isCurrentClient: (SimpleTelegramClient) -> Boolean,
    private val messageToView: (TdApi.Message) -> TelegramMessageView,
) {
    private data class CachedMessage(val view: TelegramMessageView, val replyMarkup: TdApi.ReplyMarkup?)

    private class Entry(val client: SimpleTelegramClient) {
        val fetchMutex = Mutex()
        var users = 0
        var opened = false
        var loaded = false
        var messages = emptyList<CachedMessage>()
        // Only retained during a fetch: replay events over its potentially older snapshot.
        var updatesDuringFetch: MutableList<TdApi.Update>? = null
    }

    private val logger = LoggerFactory.getLogger(TelegramHistoryCache::class.java)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<Long, Entry>(MAX_HISTORY_CHATS, 0.75f, true)

    fun registerUpdates(
        builder: SimpleTelegramClientBuilder,
        scope: CoroutineScope,
        sourceClient: () -> SimpleTelegramClient?,
    ) {
        builder.addUpdatesHandler { update ->
            if (chatId(update) != null) {
                // Enqueue at the TDLib callback boundary, preserving delivery order at the mutex.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    sourceClient()?.let { onUpdate(it, update) }
                }
            }
        }
    }

    suspend fun get(
        client: SimpleTelegramClient,
        chatId: Long,
        limit: Int,
        forceRefresh: Boolean,
        fetch: suspend () -> List<TdApi.Message>,
    ): List<TelegramMessageView> {
        val entry = acquire(client, chatId)
        try {
            currentCoroutineContext().ensureActive()
            return entry.fetchMutex.withLock fetchLock@{
                mutex.withLock cacheLock@{
                    checkCurrent(chatId, entry)
                    if (!forceRefresh && entry.loaded && entry.messages.size >= limit) {
                        return@cacheLock entry.messages.take(limit).map { it.view }
                    }
                    entry.updatesDuringFetch = mutableListOf()
                    null
                }?.let { return@fetchLock it }

                try {
                    val fetched = fetch()
                    mutex.withLock {
                        checkCurrent(chatId, entry)
                        val merged = LinkedHashMap<Long, CachedMessage>()
                        if (!forceRefresh) entry.messages.forEach { merged[it.view.messageId] = it }
                        fetched.forEach { merged[it.id] = cacheMessage(it) }
                        entry.updatesDuringFetch.orEmpty().forEach { applyUpdate(merged, it) }
                        entry.messages = boundedMessages(merged)
                        entry.loaded = true
                        entry.messages.take(limit).map { it.view }
                    }
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock { entry.updatesDuringFetch = null }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    entry.users--
                    if (entries[chatId] === entry && !entry.loaded && entry.users == 0) {
                        close(chatId, entry)
                        entries.remove(chatId)
                    }
                }
            }
        }
    }

    private suspend fun acquire(client: SimpleTelegramClient, chatId: Long): Entry =
        withContext(NonCancellable) {
            mutex.withLock {
                check(isCurrentClient(client)) { "Telegram client changed; retry history request" }
                // Keep ownership of an uncertain open if its compensating close failed.
                entries[chatId]?.takeIf { !it.opened }?.let {
                    close(chatId, it)
                    entries.remove(chatId)
                }
                val entry = entries[chatId] ?: run {
                    if (entries.size >= MAX_HISTORY_CHATS) {
                        val eldest = entries.entries.firstOrNull { it.value.users == 0 }
                        checkNotNull(eldest) { "Telegram history cache is busy; retry history request" }
                        close(eldest.key, eldest.value)
                        entries.remove(eldest.key)
                    }
                    val created = Entry(client).also { entries[chatId] = it }
                    try {
                        client.send(TdApi.OpenChat(chatId)).awaitResult()
                        created.opened = true
                    } catch (error: Exception) {
                        // An interrupted/failed open may already have reached TDLib.
                        try {
                            close(chatId, created)
                            entries.remove(chatId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (closeError: Exception) {
                            error.addSuppressed(closeError)
                        }
                        throw error
                    }
                    created
                }
                check(entry.client === client) { "Telegram history belongs to another client" }
                entry.users++
                entry
            }
        }

    suspend fun onUpdate(client: SimpleTelegramClient, update: TdApi.Update) {
        val chatId = chatId(update) ?: return
        mutex.withLock {
            if (!isCurrentClient(client)) return
            // Background updates must neither populate the LRU nor promote an idle chat.
            val entry = entries.entries.firstOrNull { it.key == chatId }?.value ?: return
            if (entry.client !== client) return
            val messages = entry.messages.associateByTo(LinkedHashMap()) { it.view.messageId }
            applyUpdate(messages, update)
            entry.messages = boundedMessages(messages)
            entry.updatesDuringFetch?.add(update)
        }
    }

    suspend fun remove(chatId: Long) {
        withContext(NonCancellable) {
            mutex.withLock {
                entries[chatId]?.let {
                    it.opened = false
                    it.loaded = false
                    it.messages = emptyList()
                    close(chatId, it)
                }
                entries.remove(chatId)
            }
        }
    }

    suspend fun clear() = withContext(NonCancellable) {
        mutex.withLock {
            entries.forEach { (chatId, entry) ->
                try {
                    close(chatId, entry)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.debug("Error closing Telegram history subscription", error)
                }
            }
            // The owning client is closed by restart even if an individual CloseChat fails.
            entries.clear()
        }
    }

    private suspend fun close(chatId: Long, entry: Entry) {
        entry.client.send(TdApi.CloseChat(chatId)).awaitResult()
    }

    private fun checkCurrent(chatId: Long, entry: Entry) {
        check(isCurrentClient(entry.client) && entry.opened && entries[chatId] === entry) {
            "Telegram history subscription changed; retry history request"
        }
    }

    private fun cacheMessage(message: TdApi.Message) = CachedMessage(messageToView(message), message.replyMarkup)

    private fun boundedMessages(messages: Map<Long, CachedMessage>) =
        messages.values.sortedByDescending { it.view.messageId }.take(TELEGRAM_MAX_HISTORY_LIMIT)

    private fun chatId(update: TdApi.Update): Long? = when (update) {
        is TdApi.UpdateNewMessage -> update.message?.chatId
        is TdApi.UpdateMessageContent -> update.chatId
        is TdApi.UpdateDeleteMessages -> update.chatId
        is TdApi.UpdateMessageSendSucceeded -> update.message?.chatId
        is TdApi.UpdateMessageSendFailed -> update.message?.chatId
        else -> null
    }

    private fun applyUpdate(messages: MutableMap<Long, CachedMessage>, update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewMessage -> update.message?.let { messages[it.id] = cacheMessage(it) }
            is TdApi.UpdateMessageContent -> messages[update.messageId]?.let { old ->
                // Render only the changed content with its existing keyboard. Retain the view's
                // sender/title/date without keeping the full TDLib message or media metadata.
                val edited = TdApi.Message().apply {
                    content = update.newContent
                    replyMarkup = old.replyMarkup
                }
                messages[update.messageId] = old.copy(view = old.view.copy(text = messageToView(edited).text))
            }
            is TdApi.UpdateDeleteMessages -> update.messageIds?.forEach { messages.remove(it) }
            is TdApi.UpdateMessageSendSucceeded -> {
                messages.remove(update.oldMessageId)
                update.message?.let { messages[it.id] = cacheMessage(it) }
            }
            is TdApi.UpdateMessageSendFailed -> {
                messages.remove(update.oldMessageId)
                update.message?.let { messages[it.id] = cacheMessage(it) }
            }
        }
    }
}
