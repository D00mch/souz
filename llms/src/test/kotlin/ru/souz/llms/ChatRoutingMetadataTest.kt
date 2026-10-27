package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatRoutingMetadataTest {
    @Test
    fun `file references round trip in message snapshots and old text messages remain readable`() {
        val old = restJsonMapper.readValue<LLMRequest.Message>("""{"role":"user","content":"hello"}""")
        assertEquals(emptyList(), old.files)
        assertFalse(restJsonMapper.readTree(restJsonMapper.writeValueAsString(old)).has("files"))
        val file = FileContent(FileReference("file-id"), "report.md", "text/markdown", 12)
        val message = old.copy(files = listOf(file), replyFiles = listOf(file),
            detachedReplyFiles = listOf(FileReference("obsolete")))
        assertEquals(message, restJsonMapper.readValue<LLMRequest.Message>(restJsonMapper.writeValueAsString(message)))
        val replacement = file.copy(name = "corrected.md")
        assertEquals(listOf(replacement), listOf(file).applyReplyFileSelection(listOf(
            old.copy(detachedReplyFiles = listOf(file.file)), old.copy(replyFiles = listOf(replacement)),
        )).files)
        assertEquals(emptyList(), listOf(file).applyReplyFileSelection(listOf(
            old.copy(replyFiles = listOf(replacement)), old.copy(detachedReplyFiles = listOf(file.file)),
        )).files)
    }

    @Test
    fun `selections beyond message limits become tool errors and leave the selection unchanged`() {
        val files = (0..MAX_MESSAGE_FILES).map { FileContent(FileReference("file-$it"), "$it.md", "text/markdown", 12) }
        val calls = files.map { LLMRequest.Message(LLMMessageRole.function, "saved ${it.name}", name = "AttachFileToReply", replyFiles = listOf(it)) }
        val selection = emptyList<FileContent>().applyReplyFileSelection(calls)
        assertEquals(files.take(MAX_MESSAGE_FILES), selection.files)
        assertEquals(calls.take(MAX_MESSAGE_FILES), selection.messages.take(MAX_MESSAGE_FILES))
        val rejected = selection.messages.last()
        assertEquals("attachment_reply_limit_exceeded", restJsonMapper.readTree(rejected.content)["error"].asText())
        assertEquals("saved ${files.last().name}", restJsonMapper.readTree(rejected.content)["result"].asText())
        assertEquals(emptyList(), rejected.replyFiles)
        // A combined replacement is accepted when its result fits; oversized bytes are rejected as a whole.
        val replace = LLMRequest.Message(LLMMessageRole.function, "replaced", detachedReplyFiles = listOf(files.first().file),
            replyFiles = listOf(files.last()))
        assertEquals(files.drop(1), selection.files.applyReplyFileSelection(listOf(replace)).files)
        val large = files.last().copy(sizeBytes = MAX_MESSAGE_FILE_BYTES)
        assertEquals(listOf(files.first()), listOf(files.first()).applyReplyFileSelection(listOf(
            replace.copy(replyFiles = listOf(large), detachedReplyFiles = emptyList()),
        )).files)
    }

    @Test
    fun `provider route and agent context budget survive copies but cannot cross the JSON boundary`() {
        val request = LLMRequest.Chat(model = "Deployment/v2", provider = LlmProvider.ANTHROPIC, messages = emptyList(), contextSize = 125_000, maxTokens = 512)
        assertEquals(request.provider, request.copy(stream = true).provider)
        assertEquals(125_000, request.copy(stream = true).contextSize)
        val json = restJsonMapper.writeValueAsString(request)
        assertFalse(restJsonMapper.readTree(json).has("provider"))
        assertFalse(restJsonMapper.readTree(json).has("contextSize"))
        assertEquals(512, restJsonMapper.readTree(json)["max_tokens"].asInt())
        val decoded = restJsonMapper.readValue<LLMRequest.Chat>("""{"model":"Deployment/v2","provider":"OPENAI","contextSize":125000,"messages":[]}""")
        assertNull(decoded.provider)
        assertNull(decoded.contextSize)
        assertEquals(request.model, decoded.model)
    }
}
