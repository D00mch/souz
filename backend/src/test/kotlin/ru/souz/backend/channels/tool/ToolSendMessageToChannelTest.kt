package ru.souz.backend.channels.tool

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.channels.ChannelDescriptor
import ru.souz.backend.channels.ChannelProvider
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.BadInputException

class ToolSendMessageToChannelTest {
    private class FakeProvider(
        private val channelType: String,
        private val result: ChannelSendResult,
    ) : ChannelProvider {
        var lastCall: Triple<String, String, String>? = null
            private set

        override fun supports(channelType: String): Boolean = channelType == this.channelType
        override suspend fun listChannels(userId: String): List<ChannelDescriptor> = emptyList()
        override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
            lastCall = Triple(userId, channelId, text)
            return result
        }
    }

    @Test
    fun `returns a structured success payload on delivery`() = runTest {
        val provider = FakeProvider("telegram", ChannelSendResult.Delivered("Sent via Telegram."))
        val tool = ToolSendMessageToChannel(ChannelProviderRegistry(listOf(provider)))

        val result = tool.suspendInvoke(
            ToolSendMessageToChannel.Input("telegram", "chat-1", "hello"),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertTrue(result.contains("\"success\":true"))
        assertTrue(result.contains("Sent via Telegram."))
        assert(provider.lastCall == Triple("user-1", "chat-1", "hello"))
    }

    @Test
    fun `returns a structured failure payload without throwing`() = runTest {
        val provider = FakeProvider("telegram", ChannelSendResult.Failed("Telegram channel not found or not linked."))
        val tool = ToolSendMessageToChannel(ChannelProviderRegistry(listOf(provider)))

        val result = tool.suspendInvoke(
            ToolSendMessageToChannel.Input("telegram", "chat-1", "hello"),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertTrue(result.contains("\"success\":false"))
        assertTrue(result.contains("Telegram channel not found or not linked."))
    }

    @Test
    fun `throws BadInputException for blank text`() = runTest {
        val tool = ToolSendMessageToChannel(ChannelProviderRegistry(emptyList()))

        assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolSendMessageToChannel.Input("telegram", "chat-1", "   "),
                ToolInvocationMeta(userId = "user-1"),
            )
        }
    }

    @Test
    fun `throws BadInputException for blank channelId`() = runTest {
        val tool = ToolSendMessageToChannel(ChannelProviderRegistry(emptyList()))

        assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolSendMessageToChannel.Input("telegram", "  ", "hello"),
                ToolInvocationMeta(userId = "user-1"),
            )
        }
    }

    @Test
    fun `rejects sending to the current conversation without calling the registry`() = runTest {
        val provider = FakeProvider("telegram", ChannelSendResult.Delivered("Sent via Telegram."))
        val tool = ToolSendMessageToChannel(ChannelProviderRegistry(listOf(provider)))

        val result = tool.suspendInvoke(
            ToolSendMessageToChannel.Input("telegram", "chat-1", "hello"),
            ToolInvocationMeta(userId = "user-1", conversationId = "chat-1"),
        )

        assertTrue(result.contains("\"success\":false"))
        assertTrue(result.contains("current channel"))
        assert(provider.lastCall == null)
    }

    @Test
    fun `blank conversationId does not block sending`() = runTest {
        val provider = FakeProvider("telegram", ChannelSendResult.Delivered("Sent via Telegram."))
        val tool = ToolSendMessageToChannel(ChannelProviderRegistry(listOf(provider)))

        val result = tool.suspendInvoke(
            ToolSendMessageToChannel.Input("telegram", "chat-1", "hello"),
            ToolInvocationMeta(userId = "user-1", conversationId = ""),
        )

        assertTrue(result.contains("\"success\":true"))
        assert(provider.lastCall == Triple("user-1", "chat-1", "hello"))
    }
}
