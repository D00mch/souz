package ru.souz.service.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotFatherReplyParserTest {

    @Test
    fun `extractToken uses only messages after baseline`() {
        val oldToken = "12345678:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val newToken = "87654321:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        val messages = listOf(
            BotFatherMessageSnapshot(id = 10, text = "Old token: $oldToken", isOutgoing = false),
            BotFatherMessageSnapshot(id = 11, text = "some text", isOutgoing = false),
            BotFatherMessageSnapshot(id = 20, text = "Use this token: $newToken", isOutgoing = false),
        )

        val extracted = BotFatherReplyParser.extractToken(messages, minMessageIdExclusive = 15)

        assertEquals(newToken, extracted)
    }

    @Test
    fun `extractToken ignores outgoing messages`() {
        val token = "87654321:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        val messages = listOf(
            BotFatherMessageSnapshot(id = 20, text = "Use this token: $token", isOutgoing = true),
        )

        val extracted = BotFatherReplyParser.extractToken(messages, minMessageIdExclusive = 15)

        assertNull(extracted)
    }

    @Test
    fun `isDeleteConfirmed matches only new success responses`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 8, text = "Done! @souz_bot deleted", isOutgoing = false),
            BotFatherMessageSnapshot(id = 25, text = "Done! @souz_bot deleted", isOutgoing = false),
        )

        assertTrue(BotFatherReplyParser.isDeleteConfirmed(messages, minMessageIdExclusive = 20, username = "souz_bot"))
        assertFalse(BotFatherReplyParser.isDeleteConfirmed(messages, minMessageIdExclusive = 25, username = "souz_bot"))
        assertFalse(BotFatherReplyParser.isDeleteConfirmed(messages, minMessageIdExclusive = 20, username = "other_bot"))
    }

    @Test
    fun `isDeleteConfirmed supports generic bot gone phrase`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 30, text = "Done! The bot is gone. /help", isOutgoing = false),
        )

        assertTrue(BotFatherReplyParser.isDeleteConfirmed(messages, minMessageIdExclusive = 20, username = "other_bot"))
    }

    @Test
    fun `requiresDeleteConfirmationText detects exact phrase request`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 30, text = "Send 'Yes, I am totally sure.' to confirm you really want to delete this bot.", isOutgoing = false),
        )

        assertTrue(
            BotFatherReplyParser.requiresDeleteConfirmationText(
                messages = messages,
                minMessageIdExclusive = 20,
            )
        )
    }

    @Test
    fun `hasNoBots detects already deleted or empty account state`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 35, text = "You don't have any bots yet. Use the /newbot command to create a new one.", isOutgoing = false),
        )

        assertTrue(BotFatherReplyParser.hasNoBots(messages, minMessageIdExclusive = 20))
        assertFalse(BotFatherReplyParser.hasNoBots(messages, minMessageIdExclusive = 40))
    }

    @Test
    fun `listedBotUsernames extracts usernames from botfather response`() {
        val messages = listOf(
            BotFatherMessageSnapshot(
                id = 40,
                text = "Choose a bot: @souz_control_191296537_8011_bot or @another_demo_bot",
                isOutgoing = false,
            ),
        )

        val listed = BotFatherReplyParser.listedBotUsernames(messages, minMessageIdExclusive = 20)

        assertEquals(setOf("souz_control_191296537_8011_bot", "another_demo_bot"), listed)
    }
}
