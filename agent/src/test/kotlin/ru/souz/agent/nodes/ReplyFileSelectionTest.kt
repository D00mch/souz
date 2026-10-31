package ru.souz.agent.nodes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.llms.FileContent
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.MAX_MESSAGE_FILE_BYTES
import ru.souz.llms.restJsonMapper

class ReplyFileSelectionTest {
    @Test
    fun `byte limit checks the whole replacement and rejects all its changes together`() {
        val old = FileContent("old", "old.txt", "text/plain", 12)
        val replacement = old.copy(fileId = "new", sizeBytes = MAX_MESSAGE_FILE_BYTES)
        val change = LLMRequest.Message(LLMMessageRole.function, "saved", files = listOf(replacement),
            replyFiles = listOf(replacement), detachedReplyFiles = listOf(old.fileId))
        val accepted = listOf(old).applyReplyFileSelection(listOf(change))
        assertEquals(listOf(replacement), accepted.files)
        assertEquals(listOf(change), accepted.messages)

        val retained = old.copy(fileId = "retained")
        val selected = listOf(old, retained)
        val rejected = selected.applyReplyFileSelection(listOf(change))
        assertEquals(selected, rejected.files)
        val error = rejected.messages.single()
        assertEquals(change.files, error.files)
        assertEquals("attachment_reply_limit_exceeded", restJsonMapper.readTree(error.content)["error"].asText())
        assertTrue(error.replyFiles.isEmpty() && error.detachedReplyFiles.isEmpty())
    }

    @Test
    fun `selection follows tool order and replaces metadata for an existing id`() {
        val file = FileContent("file", "original.txt", "text/plain", 12)
        val corrected = file.copy(name = "corrected.txt")
        val attach = LLMRequest.Message(LLMMessageRole.function, "saved", replyFiles = listOf(corrected))
        val detach = LLMRequest.Message(LLMMessageRole.function, "removed", detachedReplyFiles = listOf(file.fileId))
        for ((changes, expected) in listOf(
            listOf(attach) to listOf(corrected),
            listOf(attach, detach) to emptyList(),
            listOf(detach, attach) to listOf(corrected),
        )) assertEquals(expected, listOf(file).applyReplyFileSelection(changes).files)
    }
}
