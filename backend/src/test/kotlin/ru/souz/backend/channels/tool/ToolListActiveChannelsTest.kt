package ru.souz.backend.channels.tool

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.channels.ChannelDescriptor
import ru.souz.backend.channels.ChannelProvider
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.llms.ToolInvocationMeta

class ToolListActiveChannelsTest {
    private class FakeProvider(private val channels: List<ChannelDescriptor>) : ChannelProvider {
        override val channelType: String = "fake"
        override suspend fun listChannels(userId: String): List<ChannelDescriptor> = channels
        override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult =
            error("not used")
    }

    @Test
    fun `returns the channels reported by the registry as json`() = runTest {
        val channels = listOf(ChannelDescriptor("telegram", "chat-1", "Telegram"))
        val tool = ToolListActiveChannels(ChannelProviderRegistry(listOf(FakeProvider(channels))))

        val result = tool.suspendInvoke(ToolListActiveChannels.Input(), ToolInvocationMeta(userId = "user-1"))

        assertTrue(result.contains("\"channelType\":\"telegram\""))
        assertTrue(result.contains("\"channelId\":\"chat-1\""))
        assertTrue(result.contains("\"label\":\"Telegram\""))
    }

    @Test
    fun `returns an empty list when the user has no other channels`() = runTest {
        val tool = ToolListActiveChannels(ChannelProviderRegistry(emptyList()))

        val result = tool.suspendInvoke(ToolListActiveChannels.Input(), ToolInvocationMeta(userId = "user-1"))

        assertTrue(result.contains("\"channels\":[]"))
    }

    @Test
    fun `excludes the current conversation from the list`() = runTest {
        val channels = listOf(
            ChannelDescriptor("telegram", "chat-1", "Telegram"),
            ChannelDescriptor("mobile_app", "chat-2", "Mobile"),
        )
        val tool = ToolListActiveChannels(ChannelProviderRegistry(listOf(FakeProvider(channels))))

        val result = tool.suspendInvoke(
            ToolListActiveChannels.Input(),
            ToolInvocationMeta(userId = "user-1", conversationId = "chat-1"),
        )

        assertTrue(result.contains("\"channelId\":\"chat-2\""))
        assertTrue(!result.contains("\"channelId\":\"chat-1\""))
    }

    @Test
    fun `blank conversationId does not exclude anything`() = runTest {
        val channels = listOf(ChannelDescriptor("telegram", "", "Telegram"))
        val tool = ToolListActiveChannels(ChannelProviderRegistry(listOf(FakeProvider(channels))))

        val result = tool.suspendInvoke(
            ToolListActiveChannels.Input(),
            ToolInvocationMeta(userId = "user-1", conversationId = ""),
        )

        assertTrue(result.contains("\"channelId\":\"\""))
    }
}
