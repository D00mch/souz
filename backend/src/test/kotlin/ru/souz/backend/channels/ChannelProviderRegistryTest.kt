package ru.souz.backend.channels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class ChannelProviderRegistryTest {
    private class FakeProvider(
        private val channelType: String,
        private val channels: List<ChannelDescriptor> = emptyList(),
        private val result: ChannelSendResult = ChannelSendResult.Delivered("ok"),
    ) : ChannelProvider {
        var sendCalls: Int = 0
            private set

        override fun supports(channelType: String): Boolean = channelType == this.channelType

        override suspend fun listChannels(userId: String): List<ChannelDescriptor> = channels

        override suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult {
            sendCalls += 1
            return result
        }
    }

    @Test
    fun `listAll flattens channels from all providers`() = runTest {
        val telegram = FakeProvider("telegram", listOf(ChannelDescriptor("telegram", "1", "Telegram")))
        val mobile = FakeProvider("mobile_app", listOf(ChannelDescriptor("mobile_app", "chat-1", "Mobile")))
        val registry = ChannelProviderRegistry(listOf(telegram, mobile))

        val channels = registry.listAll("user-1")

        assertEquals(
            setOf(
                ChannelDescriptor("telegram", "1", "Telegram"),
                ChannelDescriptor("mobile_app", "chat-1", "Mobile"),
            ),
            channels.toSet(),
        )
    }

    @Test
    fun `send routes to the provider that supports the channel type`() = runTest {
        val telegram = FakeProvider("telegram")
        val mobile = FakeProvider("mobile_app")
        val registry = ChannelProviderRegistry(listOf(telegram, mobile))

        registry.send("user-1", "mobile_app", "chat-1", "hi")

        assertEquals(0, telegram.sendCalls)
        assertEquals(1, mobile.sendCalls)
    }

    @Test
    fun `send fails when no provider supports the channel type`() = runTest {
        val registry = ChannelProviderRegistry(listOf(FakeProvider("telegram")))

        val result = registry.send("user-1", "unknown", "id", "hi")

        assertIs<ChannelSendResult.Failed>(result)
    }

    @Test
    fun `listAll excludes the given channel id`() = runTest {
        val telegram = FakeProvider("telegram", listOf(ChannelDescriptor("telegram", "chat-1", "Telegram")))
        val mobile = FakeProvider("mobile_app", listOf(ChannelDescriptor("mobile_app", "chat-2", "Mobile")))
        val registry = ChannelProviderRegistry(listOf(telegram, mobile))

        val channels = registry.listAll("user-1", excludeChannelId = "chat-1")

        assertEquals(setOf(ChannelDescriptor("mobile_app", "chat-2", "Mobile")), channels.toSet())
    }

    @Test
    fun `send rejects the given channel id without calling any provider`() = runTest {
        val telegram = FakeProvider("telegram")
        val registry = ChannelProviderRegistry(listOf(telegram))

        val result = registry.send("user-1", "telegram", "chat-1", "hi", excludeChannelId = "chat-1")

        assertIs<ChannelSendResult.Failed>(result)
        assertEquals(0, telegram.sendCalls)
    }

    @Test
    fun `send ignores excludeChannelId when it does not match the target`() = runTest {
        val telegram = FakeProvider("telegram")
        val registry = ChannelProviderRegistry(listOf(telegram))

        val result = registry.send("user-1", "telegram", "chat-1", "hi", excludeChannelId = "chat-2")

        assertIs<ChannelSendResult.Delivered>(result)
        assertEquals(1, telegram.sendCalls)
    }
}
