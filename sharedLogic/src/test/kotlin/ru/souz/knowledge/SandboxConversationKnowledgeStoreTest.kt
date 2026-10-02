package ru.souz.knowledge

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
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreCorruptionException
import ru.souz.agent.knowledge.KnowledgeStorePersistenceException
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.docker.DockerSandboxFileSystem
import ru.souz.runtime.sandbox.docker.DockerSandboxLayout
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxConversationKnowledgeStoreTest {
    @Test
    fun `round trips complete and truncated records resolving the sandbox for every operation`() = runTest {
        withFixture { fixture ->
            val meta = fixture.meta()
            for (text in listOf("content", "h".repeat(1_100_000))) {
                val resolvesBefore = fixture.resolveCount
                val entry = fixture.store.put(meta, "Tool", text).storedEntry()
                assertEquals(entry, fixture.store.get(meta, " ${entry.id.uppercase()} "))
                fixture.store.clearConversation(meta)
                assertEquals(resolvesBefore + 3, fixture.resolveCount)
            }
        }
    }

    @Test
    fun `unavailable conversations and invalid ids never resolve a sandbox`() = runTest {
        withFixture { fixture ->
            for (conversationId in listOf(null, " \t ")) {
                val meta = fixture.meta().copy(conversationId = conversationId)
                assertEquals(KnowledgeWriteResult.ConversationUnavailable, fixture.store.put(meta, "Tool", "content"))
                assertFailsWith<KnowledgeStoreUnavailableException> { fixture.store.get(meta, VALID_ID) }
                assertFailsWith<KnowledgeStoreUnavailableException> { fixture.store.clearConversation(meta) }
            }
            for (id in listOf("../../not-a-uuid", VALID_ID.dropLast(1))) {
                assertNull(fixture.store.get(fixture.meta(), id))
            }
            assertEquals(0, fixture.resolveCount)
        }
    }

    @Test
    fun `long unicode and spaced identities remain isolated through targeted cleanup`() = runTest {
        withFixture { fixture ->
            val owner = fixture.meta(userId = "用".repeat(256), conversationId = "会話/../".repeat(80))
            val entry = fixture.store.put(owner, "Tool", "private").storedEntry()
            val otherEntries = listOf(
                owner.copy(userId = " ${owner.userId} "),
                owner.copy(conversationId = " ${owner.conversationId} "),
            ).associateWith { fixture.store.put(it, "Tool", "other").storedEntry() }

            assertEquals(entry, fixture.store.get(owner, entry.id))
            otherEntries.keys.forEach { assertNull(fixture.store.get(it, entry.id)) }
            val recordPath = fixture.recordPath(owner, entry.id)
            assertTrue(Files.exists(recordPath))
            assertEquals(43, recordPath.parent.fileName.toString().length)
            assertEquals(43, recordPath.parent.parent.parent.fileName.toString().length)

            otherEntries.forEach { (scope, other) ->
                assertEquals(other, fixture.store.get(scope, other.id))
                fixture.store.clearConversation(scope)
                fixture.store.clearConversation(scope)
                assertNull(fixture.store.get(scope, other.id))
                assertEquals(entry, fixture.store.get(owner, entry.id))
            }
        }
    }

    @Test
    fun `docker knowledge paths remain POSIX and state-rooted`() = runTest {
        val hostRoot = createTempDirectory("souz-docker-knowledge-store-")
        try {
            val layout = DockerSandboxLayout(hostRoot)
            layout.ensureHostDirectories()
            val resolvedPaths = mutableListOf<String>()
            val dockerFileSystem = DockerSandboxFileSystem(layout)
            val recordingFileSystem = object : SandboxFileSystem by dockerFileSystem {
                override fun resolvePath(rawPath: String): SandboxPathInfo {
                    resolvedPaths += rawPath
                    return dockerFileSystem.resolvePath(rawPath)
                }
            }
            val sandbox = mockk<RuntimeSandbox> {
                every { runtimePaths } returns layout.runtimePaths
                every { fileSystem } returns recordingFileSystem
            }
            val store = SandboxConversationKnowledgeStore(
                sandboxResolver = ToolInvocationRuntimeSandboxResolver { sandbox },
            )
            val meta = ToolInvocationMeta(userId = "user-1", conversationId = "conversation-1")

            val entry = store.put(meta, "Tool", "content").storedEntry()

            assertEquals(entry, store.get(meta, entry.id))
            assertTrue(Files.exists(layout.hostStateRoot.resolve("knowledge")))

            store.clearConversation(meta)

            assertNull(store.get(meta, entry.id))
            assertTrue(
                resolvedPaths.all { rawPath ->
                    rawPath.startsWith("${layout.runtimePaths.stateRootPath}/knowledge/") && '\\' !in rawPath
                }
            )
        } finally {
            hostRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt and oversized records are rejected`() = runTest {
        var readCount = 0
        withFixture(
            fileSystemTransform = { delegate ->
                object : SandboxFileSystem by delegate {
                    override fun readText(path: SandboxPathInfo): String {
                        readCount += 1
                        return delegate.readText(path)
                    }
                }
            }
        ) { fixture ->
            val meta = fixture.meta()
            val corrupt = fixture.store.put(meta, "Tool", "content").storedEntry()
            Files.writeString(fixture.recordPath(meta, corrupt.id), "{}")
            assertFailsWith<KnowledgeStoreCorruptionException> { fixture.store.get(meta, corrupt.id) }

            val oversized = fixture.store.put(meta, "Tool", "other").storedEntry()
            Files.write(
                fixture.recordPath(meta, oversized.id),
                ByteArray(KnowledgeRecordCodec.MAX_SERIALIZED_RECORD_BYTES.toInt() + 1),
            )
            val readsBeforeOversizedRecord = readCount
            assertFailsWith<KnowledgeStoreCorruptionException> { fixture.store.get(meta, oversized.id) }
            assertEquals(readsBeforeOversizedRecord, readCount)
        }
    }

    @Test
    fun `filesystem failures are typed and cancellation propagates`() = runTest {
        for (failure in listOf(IOException("write failed"), CancellationException("cancelled"))) {
            withFixture(fileSystemTransform = { delegate ->
                object : SandboxFileSystem by delegate {
                    override fun writeTextAtomically(path: SandboxPathInfo, content: String, logger: Logger) {
                        throw failure
                    }
                }
            }) { fixture ->
                if (failure is CancellationException) {
                    assertFailsWith<CancellationException> { fixture.store.put(fixture.meta(), "Tool", "content") }
                } else {
                    val error = assertFailsWith<KnowledgeStorePersistenceException> {
                        fixture.store.put(fixture.meta(), "Tool", "content")
                    }
                    assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === failure })
                }
            }
        }
    }

    private suspend fun withFixture(
        fileSystemTransform: (SandboxFileSystem) -> SandboxFileSystem = { it },
        block: suspend (Fixture) -> Unit,
    ) = Fixture(fileSystemTransform).use { block(it) }

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

    private companion object {
        const val VALID_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
