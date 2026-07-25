package ru.souz.knowledge

import com.fasterxml.jackson.databind.JsonNode
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.slf4j.Logger
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreCorruptionException
import ru.souz.agent.knowledge.KnowledgeStorePersistenceException
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxConversationKnowledgeStoreTest {
    @Test
    fun `round trips complete exact-cap and empty entries with compact canonical records`() = runTest {
        withFixture { fixture ->
            val exactCap = "a".repeat(SandboxConversationKnowledgeStore.MAX_RETAINED_CONTENT_BYTES.toInt())
            val exact = fixture.store.put(fixture.meta(), "ReadFile", exactCap).storedEntry()
            val empty = fixture.store.put(fixture.meta(), "EmptyTool", "").storedEntry()

            val retrieved = assertNotNull(fixture.store.get(fixture.meta(), exact.id))
            assertEquals(exactCap, assertIs<KnowledgeContent.Complete>(retrieved.content).content)
            assertEquals(exact, fixture.store.get(fixture.meta(), exact.id.uppercase()))
            assertEquals(exactCap.length, exact.storedLength)
            assertEquals(empty, fixture.store.get(fixture.meta(), empty.id))
            assertEquals("", assertIs<KnowledgeContent.Complete>(empty.content).content)

            val recordNode: JsonNode = restJsonMapper.readTree(Files.readString(fixture.recordPath(fixture.meta(), exact.id)))
            assertEquals(
                setOf("version", "id", "sourceTool", "originalLength", "content"),
                recordNode.fieldNames().asSequence().toSet(),
            )
            assertFalse(recordNode.has("storedLength"))
            assertFalse(recordNode.has("head"))
            assertFalse(recordNode.has("tail"))
        }
    }

    @Test
    fun `oversized ascii content retains equal head and tail budgets`() = runTest {
        withFixture { fixture ->
            val omittedChars = 137
            val partLength = SandboxConversationKnowledgeStore.PART_BYTE_BUDGET.toInt()
            val content = "h".repeat(partLength) + "x".repeat(omittedChars) + "t".repeat(partLength)

            val entry = fixture.store.put(
                fixture.meta(),
                sourceTool = "LargeTool",
                content = content,
            ).storedEntry()
            val truncated = assertIs<KnowledgeContent.Truncated>(entry.content)

            assertEquals(content.length, entry.originalLength)
            assertEquals(SandboxConversationKnowledgeStore.MAX_RETAINED_CONTENT_BYTES.toInt(), entry.storedLength)
            assertEquals(partLength, truncated.head.length)
            assertEquals(partLength, truncated.tail.length)
            assertTrue(truncated.head.all { it == 'h' })
            assertTrue(truncated.tail.all { it == 't' })

            val recordNode: JsonNode = restJsonMapper.readTree(Files.readString(fixture.recordPath(fixture.meta(), entry.id)))
            assertFalse(recordNode.has("content"))
            assertTrue(recordNode.has("head"))
            assertTrue(recordNode.has("tail"))
        }
    }

    @Test
    fun `multibyte truncation preserves code points and utf16 offsets`() = runTest {
        withFixture { fixture ->
            val content = "🙂".repeat(262_145)

            val entry = fixture.store.put(
                fixture.meta(),
                sourceTool = "EmojiTool",
                content = content,
            ).storedEntry()
            val truncated = assertIs<KnowledgeContent.Truncated>(entry.content)

            assertEquals(524_290, entry.originalLength)
            assertEquals(524_288, entry.storedLength)
            assertEquals(
                1_048_576,
                listOf(truncated.head, truncated.tail).sumOf { it.toByteArray(StandardCharsets.UTF_8).size },
            )
            listOf(truncated.head, truncated.tail).forEach { part ->
                assertFalse(part.first().isLowSurrogate())
                assertFalse(part.last().isHighSurrogate())
            }
        }
    }

    @Test
    fun `null or blank conversation is unavailable without resolving a sandbox`() = runTest {
        val fixture = Fixture()
        try {
            val unavailableMetas = listOf(
                ToolInvocationMeta(userId = "user-1"),
                ToolInvocationMeta(userId = "user-1", conversationId = " \t "),
            )

            unavailableMetas.forEach { meta ->
                assertEquals(
                    KnowledgeWriteResult.ConversationUnavailable,
                    fixture.store.put(meta, "Tool", "content"),
                )
                assertFailsWith<KnowledgeStoreUnavailableException> { fixture.store.get(meta, VALID_ID) }
                assertFailsWith<KnowledgeStoreUnavailableException> { fixture.store.clearConversation(meta) }
            }
            assertEquals(0, fixture.resolveCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `invalid ids miss without filesystem resolution`() = runTest {
        val fixture = Fixture()
        try {
            assertNull(fixture.store.get(fixture.meta(), "../../not-a-uuid"))
            assertNull(fixture.store.get(fixture.meta(), VALID_ID.dropLast(1)))
            assertEquals(0, fixture.resolveCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `entries are isolated by long unicode user and conversation identities`() = runTest {
        withFixture { fixture ->
            val owner = fixture.meta(
                userId = "用".repeat(256),
                conversationId = "会話/../".repeat(80),
            )
            val entry = fixture.store.put(owner, "ScopedTool", "private").storedEntry()

            assertEquals(entry, fixture.store.get(owner, entry.id))
            assertNull(fixture.store.get(owner.copy(userId = "another-user"), entry.id))
            assertNull(fixture.store.get(owner.copy(conversationId = "another-conversation"), entry.id))

            val recordPath = fixture.recordPath(owner, entry.id)
            assertTrue(Files.exists(recordPath))
            assertEquals(43, recordPath.parent.fileName.toString().length)
            assertEquals(43, recordPath.parent.parent.parent.fileName.toString().length)
        }
    }

    @Test
    fun `exact invocation identities remain isolated through targeted cleanup`() = runTest {
        withFixture { fixture ->
            val plain = fixture.meta(userId = "user", conversationId = "chat")
            val spacedConversation = fixture.meta(userId = "user", conversationId = " chat ")
            val spacedUser = fixture.meta(userId = " user ", conversationId = "chat")

            val plainEntry = fixture.store.put(plain, "Tool", "plain").storedEntry()
            val spacedConversationEntry = fixture.store
                .put(spacedConversation, "Tool", "spaced conversation")
                .storedEntry()
            val spacedUserEntry = fixture.store.put(spacedUser, "Tool", "spaced user").storedEntry()

            assertEquals(plainEntry, fixture.store.get(plain, plainEntry.id))
            assertEquals(
                spacedConversationEntry,
                fixture.store.get(spacedConversation, spacedConversationEntry.id),
            )
            assertEquals(spacedUserEntry, fixture.store.get(spacedUser, spacedUserEntry.id))
            assertNull(fixture.store.get(spacedConversation, plainEntry.id))
            assertNull(fixture.store.get(spacedUser, plainEntry.id))

            fixture.store.clearConversation(spacedConversation)

            assertNull(fixture.store.get(spacedConversation, spacedConversationEntry.id))
            assertEquals(plainEntry, fixture.store.get(plain, plainEntry.id))
            assertEquals(spacedUserEntry, fixture.store.get(spacedUser, spacedUserEntry.id))

            fixture.store.clearConversation(spacedUser)

            assertNull(fixture.store.get(spacedUser, spacedUserEntry.id))
            assertEquals(plainEntry, fixture.store.get(plain, plainEntry.id))
        }
    }

    @Test
    fun `clear removes only one conversation and is idempotent`() = runTest {
        withFixture { fixture ->
            val firstMeta = fixture.meta(conversationId = "conversation-1")
            val secondMeta = fixture.meta(conversationId = "conversation-2")
            val first = fixture.store.put(firstMeta, "Tool", "first").storedEntry()
            val second = fixture.store.put(secondMeta, "Tool", "second").storedEntry()

            fixture.store.clearConversation(firstMeta)
            fixture.store.clearConversation(firstMeta)

            assertNull(fixture.store.get(firstMeta, first.id))
            assertEquals(second, fixture.store.get(secondMeta, second.id))
        }
    }

    @Test
    fun `corrupt and oversized records are rejected`() = runTest {
        withFixture { fixture ->
            val meta = fixture.meta()
            val corrupt = fixture.store.put(meta, "Tool", "content").storedEntry()
            Files.writeString(fixture.recordPath(meta, corrupt.id), "{}")
            assertFailsWith<KnowledgeStoreCorruptionException> { fixture.store.get(meta, corrupt.id) }

            val inconsistent = fixture.store.put(meta, "Tool", "mixed representation").storedEntry()
            Files.writeString(
                fixture.recordPath(meta, inconsistent.id),
                """
                {
                  "version": 1,
                  "id": "${inconsistent.id}",
                  "sourceTool": "Tool",
                  "originalLength": 20,
                  "content": "complete",
                  "head": "head",
                  "tail": "tail"
                }
                """.trimIndent(),
            )
            assertFailsWith<KnowledgeStoreCorruptionException> { fixture.store.get(meta, inconsistent.id) }

            val oversized = fixture.store.put(meta, "Tool", "other").storedEntry()
            Files.write(
                fixture.recordPath(meta, oversized.id),
                ByteArray(SandboxConversationKnowledgeStore.MAX_SERIALIZED_RECORD_BYTES.toInt() + 1),
            )
            assertFailsWith<KnowledgeStoreCorruptionException> { fixture.store.get(meta, oversized.id) }
        }
    }

    @Test
    fun `filesystem failures are typed and cancellation propagates`() = runTest {
        val ioFixture = Fixture(
            fileSystemTransform = { delegate ->
                object : SandboxFileSystem by delegate {
                    override fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger) {
                        throw IOException("write failed")
                    }
                }
            }
        )
        try {
            val error = assertFailsWith<KnowledgeStorePersistenceException> {
                ioFixture.store.put(ioFixture.meta(), "Tool", "content")
            }
            assertTrue(error.causeChain().any { it is IOException })
        } finally {
            ioFixture.close()
        }

        val cancellationFixture = Fixture(
            fileSystemTransform = { delegate ->
                object : SandboxFileSystem by delegate {
                    override fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger) {
                        throw CancellationException("cancelled")
                    }
                }
            }
        )
        try {
            assertFailsWith<CancellationException> {
                cancellationFixture.store.put(
                    cancellationFixture.meta(),
                    "Tool",
                    "content",
                )
            }
        } finally {
            cancellationFixture.close()
        }
    }

    @Test
    fun `sandbox is resolved for every storage operation`() = runTest {
        withFixture { fixture ->
            val meta = fixture.meta()
            val entry = fixture.store.put(meta, "Tool", "content").storedEntry()
            assertNotNull(fixture.store.get(meta, entry.id))
            fixture.store.clearConversation(meta)

            assertEquals(3, fixture.resolveCount)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        fileSystemTransform: (SandboxFileSystem) -> SandboxFileSystem = { it },
    ) : AutoCloseable {
        private val root: Path = createTempDirectory("souz-knowledge-store-")
        private val stateRoot: Path = root.resolve("state").createDirectories()
        private val settingsProvider = mockk<SettingsProvider> {
            every { forbiddenFolders } returns emptyList()
        }
        private val baseSandbox = LocalRuntimeSandbox(
            scope = SandboxScope.localDefault(),
            settingsProvider = settingsProvider,
            homePath = root,
            stateRoot = stateRoot,
            workspaceRoot = root,
        )
        private val sandbox: RuntimeSandbox = object : RuntimeSandbox by baseSandbox {
            override val fileSystem: SandboxFileSystem = fileSystemTransform(baseSandbox.fileSystem)
        }
        var resolveCount: Int = 0
            private set
        private val resolver = ToolInvocationRuntimeSandboxResolver {
            resolveCount += 1
            sandbox
        }
        val store = SandboxConversationKnowledgeStore(resolver)

        fun meta(
            userId: String = "user-1",
            conversationId: String = "conversation-1",
        ): ToolInvocationMeta = ToolInvocationMeta(
            userId = userId,
            conversationId = conversationId,
        )

        fun recordPath(meta: ToolInvocationMeta, id: String): Path = stateRoot
            .resolve("knowledge")
            .resolve("users")
            .resolve(scopeKey(meta.userId))
            .resolve("conversations")
            .resolve(scopeKey(requireNotNull(meta.conversationId)))
            .resolve("$id.json")

        override fun close() {
            root.toFile().deleteRecursively()
        }

        private fun scopeKey(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(StandardCharsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }

    private fun KnowledgeWriteResult.storedEntry(): KnowledgeEntry =
        assertIs<KnowledgeWriteResult.Stored>(this).entry

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private companion object {
        const val VALID_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
