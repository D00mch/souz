package ru.souz.backend.channels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.spyk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.souz.backend.vk.VkMarkdown

class ChannelPreparedDeliveryTest {
    @Test
    fun `complete formatted delivery stores source Markdown and partial failure only accepted text`() = runBlocking {
        val service = spyk(ChannelDeliveryService(mockk(), mockk(), mockk()))
        coEvery { service.deliver(any(), any(), any()) } just Runs
        val chatId = UUID.randomUUID()
        val source = "**abcdefghijklmnop**"
        val chunks = VkMarkdown.chunks(source, maxLength = 6)
        val complete = service.sendPreparedChunks("user", chatId, source, "VK", chunks, { it.text }) {}
        assertIs<ChannelSendResult.Delivered>(complete)
        coVerify(exactly = 1) { service.deliver("user", chatId, source) }
        var count = 0
        val partial = service.sendPreparedChunks("user", chatId, source, "VK", chunks, { it.text }) {
            if (++count == 2) error("Rejected by VK")
        }
        assertIs<ChannelSendResult.Failed>(partial)
        coVerify(exactly = 1) { service.deliver("user", chatId, "abcdef") }
    }

    @Test
    fun `cancellation persists accepted chunk and propagates`() = runBlocking {
        val service = spyk(ChannelDeliveryService(mockk(), mockk(), mockk()))
        coEvery { service.deliver(any(), any(), any()) } just Runs
        val chatId = UUID.randomUUID()
        val chunks = VkMarkdown.chunks("**abcdefgh**", maxLength = 4)
        assertFailsWith<CancellationException> {
            service.sendPreparedChunks("user", chatId, "**abcdefgh**", "VK", chunks, { it.text }) {
                if (it.text == "efgh") throw CancellationException("lease lost")
            }
        }
        coVerify(exactly = 1) { service.deliver("user", chatId, "abcd") }
    }
}
