package ru.souz.backend.channels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.spyk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class ChannelDeliveryServiceTest {
    private val chatId = UUID.randomUUID()
    private val service = spyk(ChannelDeliveryService(mockk(), mockk(), mockk()))

    @Test
    fun `complete delivery persists source Markdown`() = runTest {
        coEvery { service.deliver(any(), any(), any()) } just Runs
        val sent = mutableListOf<String>()

        val result = service.sendChunks("user", chatId, "**abcdefgh**", "VK", listOf("abcd", "efgh"), { it }) {
            sent += it
        }

        assertIs<ChannelSendResult.Delivered>(result)
        assertEquals(listOf("abcd", "efgh"), sent)
        coVerify(exactly = 1) { service.deliver("user", chatId, "**abcdefgh**") }
    }

    @Test
    fun `failure and cancellation persist only accepted chunks`() = runTest {
        for (cancelled in listOf(false, true)) for (accepted in 0..1) {
            val delivery = spyk(ChannelDeliveryService(mockk(), mockk(), mockk()))
            coEvery { delivery.deliver(any(), any(), any()) } just Runs
            var attempted = 0
            val send = suspend {
                delivery.sendChunks("user", chatId, "**abcdefgh**", "VK", listOf("abcd", "efgh"), { it }) {
                    if (attempted++ == accepted) {
                        if (cancelled) throw CancellationException("lease lost")
                        else error("rejected")
                    }
                }
            }

            if (cancelled) assertFailsWith<CancellationException> { send() }
            else assertIs<ChannelSendResult.Failed>(send())
            coVerify(exactly = accepted) { delivery.deliver("user", chatId, "abcd") }
            coVerify(exactly = accepted) { delivery.deliver(any(), any(), any()) }
        }
    }
}
