package ru.souz.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreCorruptionException
import ru.souz.llms.restJsonMapper

class KnowledgeRecordCodecTest {
    private val codec = KnowledgeRecordCodec()

    @Test
    fun `complete records retain empty exact-cap and escaped content`() {
        for (text in listOf("", "a".repeat(1_048_576), "before\u0000\n\t\"\\🙂after")) {
            val entry = codec.createEntry(ID, "Tool", text)
            assertEquals(KnowledgeContent.Complete(text), entry.content)
            assertEquals(text.length, entry.originalLength)
            assertEquals(text.length, entry.storedLength)
            assertRoundTrip(entry, setOf("version", "id", "sourceTool", "originalLength", "content"))
        }
    }

    @Test
    fun `truncated records retain maximal whole code points in separate byte budgets`() {
        val head = "h".repeat(524_288)
        val tail = "t".repeat(524_288)
        val emojiPart = "🙂".repeat(131_071)
        for ((text, expectedHead, expectedTail) in listOf(
            Triple(head + "x".repeat(137) + tail, head, tail),
            Triple("a" + "🙂".repeat(262_145) + "b", "a$emojiPart", "${emojiPart}b"),
        )) {
            val entry = codec.createEntry(ID, "Tool", text)
            assertEquals(KnowledgeContent.Truncated(expectedHead, expectedTail), entry.content)
            assertEquals(text.length, entry.originalLength)
            assertEquals(expectedHead.length + expectedTail.length, entry.storedLength)
            assertRoundTrip(entry, setOf("version", "id", "sourceTool", "originalLength", "head", "tail"))
        }
    }

    @Test
    fun `invalid records versions ids and mixed content are rejected`() {
        val serialized = codec.serialize(codec.createEntry(ID, "Tool", "content"))
        for (record in listOf(
            "{}",
            serialized.replace("\"version\":1", "\"version\":9"),
            serialized.replace(ID, "123e4567-e89b-12d3-a456-426614174001"),
            serialized.dropLast(1) + """, "head":"head", "tail":"tail"}""",
        )) {
            assertFailsWith<KnowledgeStoreCorruptionException> { codec.deserialize(record, ID) }
        }
    }

    private fun assertRoundTrip(entry: KnowledgeEntry, fields: Set<String>) {
        val serialized = codec.serialize(entry)
        assertEquals(fields, restJsonMapper.readTree(serialized).fieldNames().asSequence().toSet())
        assertEquals(entry, codec.deserialize(serialized, entry.id))
    }

    private companion object {
        const val ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
