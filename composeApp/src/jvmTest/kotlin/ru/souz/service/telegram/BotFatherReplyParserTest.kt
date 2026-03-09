package ru.souz.service.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotFatherReplyParserTest {

    @Test
    fun `extractToken reads newest incoming token from TDLib ordered messages`() {
        val oldToken = "12345678:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val newToken = "87654321:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        val messages = listOf(
            BotFatherMessageSnapshot(id = 20, text = "Use this token: $newToken", isOutgoing = false),
            BotFatherMessageSnapshot(id = 11, text = "some text", isOutgoing = false),
            BotFatherMessageSnapshot(id = 10, text = "Old token: $oldToken", isOutgoing = false),
        )

        val extracted = BotFatherReplyParser.extractToken(messages)

        assertEquals(newToken, extracted)
    }

    @Test
    fun `extractToken ignores outgoing messages`() {
        val token = "87654321:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        val messages = listOf(
            BotFatherMessageSnapshot(id = 20, text = "Use this token: $token", isOutgoing = true),
        )

        val extracted = BotFatherReplyParser.extractToken(messages)

        assertNull(extracted)
    }

    @Test
    fun `isDeleteConfirmed checks only latest incoming response`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 25, text = "Done! @souz_bot deleted", isOutgoing = false),
            BotFatherMessageSnapshot(id = 8, text = "Done! @souz_bot deleted", isOutgoing = false),
        )

        assertTrue(BotFatherReplyParser.isDeleteConfirmed(messages, username = "souz_bot"))
        assertFalse(BotFatherReplyParser.isDeleteConfirmed(messages, username = "other_bot"))
    }

    @Test
    fun `isDeleteConfirmed ignores older success when latest message is unrelated`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 30, text = "Choose a bot to delete.", isOutgoing = false),
            BotFatherMessageSnapshot(id = 25, text = "Done! @souz_bot deleted", isOutgoing = false),
        )

        assertFalse(BotFatherReplyParser.isDeleteConfirmed(messages, username = "souz_bot"))
    }

    @Test
    fun `isDeleteConfirmed supports generic bot gone phrase`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 30, text = "Done! The bot is gone. /help", isOutgoing = false),
        )

        assertTrue(BotFatherReplyParser.isDeleteConfirmed(messages, username = "other_bot"))
    }

    @Test
    fun `requiresDeleteConfirmationText detects exact phrase request`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 30, text = "Send 'Yes, I am totally sure.' to confirm you really want to delete this bot.", isOutgoing = false),
        )

        assertTrue(
            BotFatherReplyParser.requiresDeleteConfirmationText(
                messages = messages,
            )
        )
    }

    @Test
    fun `hasNoBots detects already deleted or empty account state`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 35, text = "You don't have any bots yet. Use the /newbot command to create a new one.", isOutgoing = false),
        )

        assertTrue(BotFatherReplyParser.hasNoBots(messages))
    }

    @Test
    fun `hasNoBots checks only latest incoming response`() {
        val messages = listOf(
            BotFatherMessageSnapshot(id = 40, text = "Choose a bot from the list below.", isOutgoing = false),
            BotFatherMessageSnapshot(id = 35, text = "You don't have any bots yet. Use the /newbot command to create a new one.", isOutgoing = false),
        )

        assertFalse(BotFatherReplyParser.hasNoBots(messages))
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

        val listed = BotFatherReplyParser.listedBotUsernames(messages)

        assertEquals(setOf("souz_control_191296537_8011_bot", "another_demo_bot"), listed)
    }

    @Test
    fun `isWaitingForProfilePhoto detects BotFather request`() {
        val messages = listOf(
            BotFatherMessageSnapshot(
                id = 41,
                text = "OK. Send me the new profile photo for the bot.",
                isOutgoing = false,
            ),
        )

        assertTrue(BotFatherReplyParser.isWaitingForProfilePhoto(messages))
    }

    @Test
    fun `isWaitingForProfilePhoto checks only latest incoming response`() {
        val messages = listOf(
            BotFatherMessageSnapshot(
                id = 42,
                text = "Choose a bot to change the list of commands.",
                isOutgoing = false,
            ),
            BotFatherMessageSnapshot(
                id = 41,
                text = "OK. Send me the new profile photo for the bot.",
                isOutgoing = false,
            ),
        )

        assertFalse(BotFatherReplyParser.isWaitingForProfilePhoto(messages))
    }
}
