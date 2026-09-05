package ru.souz.backend.agent.runtime.conversation

import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.chat.model.CLIENT_HISTORY_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.model.CROSS_CHANNEL_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

class BackendConversationRuntimeTest {
    @Test
    fun `execute startup paginates history only through its durable input barrier`() = runTest {
        val fixture = Fixture(
            messages = (1L..1_001L).map { seq -> historyMessage(seq, roleFor(seq), "history-$seq") } +
                ordinaryMessage(1_002L, ChatRole.USER, "execute trigger") +
                crossChannelMessage(1_003L, "too-late"),
        )

        val loaded = loadInitialConversationMessages(
            messageRepository = fixture.repository,
            key = fixture.key,
            basedOnMessageSeq = 0L,
            inputMessageSeq = 1_002L,
        )

        assertEquals(1_002L, loaded.observedThroughSeq)
        assertEquals(1_001, loaded.messages.size)
        assertEquals("history-1", loaded.messages.first().content)
        assertEquals(LLMMessageRole.user, loaded.messages.first().role)
        assertEquals(LLMMessageRole.assistant, loaded.messages[1].role)
        assertEquals("history-1001", loaded.messages.last().content)
        assertEquals(3, fixture.listCalls)
    }

    @Test
    fun `non-execute continuation stops before client history without skipping its cursor gap`() = runTest {
        val fixture = Fixture(
            messages = (1L..500L).map { seq ->
                ordinaryMessage(seq, ChatRole.ASSISTANT, "already in session")
            } + listOf(
                crossChannelMessage(501L, "visible cross-channel"),
                ordinaryMessage(502L, ChatRole.ASSISTANT, "already in session"),
                historyMessage(503L, ChatRole.USER, "pending history"),
                crossChannelMessage(504L, "blocked behind history"),
            ),
        )

        val loaded = loadInitialConversationMessages(
            messageRepository = fixture.repository,
            key = fixture.key,
            basedOnMessageSeq = 0L,
            inputMessageSeq = null,
        )

        assertEquals(502L, loaded.observedThroughSeq)
        assertEquals(
            listOf(LLMRequest.Message(LLMMessageRole.assistant, "visible cross-channel")),
            loaded.messages,
        )
        assertEquals(2, fixture.listCalls)
    }

    private class Fixture(messages: List<ChatMessage>) {
        val key = AgentConversationKey(userId = "user-1", conversationId = TEST_CHAT_ID.toString())
        val repository = mockk<MessageRepository>()
        var listCalls = 0
            private set

        init {
            coEvery { repository.list(any(), any(), any(), any(), any()) } coAnswers {
                val userId = arg<String>(0)
                val chatId = arg<UUID>(1)
                val afterSeq = arg<Long?>(2)
                val beforeSeq = arg<Long?>(3)
                val limit = arg<Int>(4)
                listCalls += 1
                messages.asSequence()
                    .filter { it.userId == userId && it.chatId == chatId }
                    .filter { afterSeq == null || it.seq > afterSeq }
                    .filter { beforeSeq == null || it.seq < beforeSeq }
                    .sortedBy(ChatMessage::seq)
                    .take(limit)
                    .toList()
            }
        }
    }

    companion object {
        private val TEST_CHAT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        private fun historyMessage(seq: Long, role: ChatRole, content: String): ChatMessage =
            message(seq, role, content, mapOf(CLIENT_HISTORY_MESSAGE_METADATA_KEY to "true"))

        private fun crossChannelMessage(seq: Long, content: String): ChatMessage =
            message(seq, ChatRole.ASSISTANT, content, mapOf(CROSS_CHANNEL_MESSAGE_METADATA_KEY to "true"))

        private fun ordinaryMessage(seq: Long, role: ChatRole, content: String): ChatMessage =
            message(seq, role, content, emptyMap())

        private fun message(
            seq: Long,
            role: ChatRole,
            content: String,
            metadata: Map<String, String>,
        ) = ChatMessage(
            id = UUID.randomUUID(),
            userId = "user-1",
            chatId = TEST_CHAT_ID,
            seq = seq,
            role = role,
            content = content,
            metadata = metadata,
            createdAt = Instant.EPOCH.plusSeconds(seq),
        )

        private fun roleFor(seq: Long): ChatRole =
            if (seq % 2L == 0L) ChatRole.ASSISTANT else ChatRole.USER
    }
}
