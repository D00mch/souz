package ru.souz.backend.plugins

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import ru.souz.backend.attachments.AttachmentFixture
import ru.souz.backend.attachments.attachmentTest
import ru.souz.backend.channels.ChannelProvider
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.testutil.createSymbolicLinkOrSkip
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.plugins.api.PluginContext
import ru.souz.plugins.api.PluginException
import ru.souz.plugins.api.PluginFailureKind
import ru.souz.plugins.api.PluginHttpRequest
import ru.souz.plugins.api.PluginInvocationMeta
import ru.souz.plugins.api.PluginOAuthRequest
import ru.souz.plugins.api.PluginOAuthResponse
import ru.souz.plugins.api.PluginReconnectRequired
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.skilloauth.ApiCallOutcome
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.SkillOAuthException
import ru.souz.skilloauth.SkillOAuthGateway
import ru.souz.skilloauth.SkillOAuthValidationException

class PluginServicesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `S3 files preserve owner bounds and retention without resolving a workspace`() = attachmentTest {
        val repository = pluginRepository()
        val binding = PluginBinding("mail", "v1", "mail".sha256Hex())
        repository.install(binding, 0)
        PluginServices(repository, null, ChannelProviderRegistry(emptyList()),
            ToolInvocationRuntimeSandboxResolver { error("S3 operations must not resolve a sandbox") }, storage).use { services ->
            val aliceLease = assertNotNull(repository.claim("alice", binding, PluginRuntimeConfig()))
            val bobLease = assertNotNull(repository.claim("bob", binding, PluginRuntimeConfig()))
            val alice = services.context(aliceLease, PluginInvocationMeta("alice")) {}
            val bob = services.context(bobLease, PluginInvocationMeta("bob")) {}
            assertFailsWith<IllegalArgumentException> { services.context(aliceLease, PluginInvocationMeta("bob")) {} }
            val bytes = "Plugin file ✓".toByteArray()
            val saved = alice.files.saveStored("note.txt", bytes)
            assertEquals(saved, alice.files.saveStored("note.txt", bytes))
            assertEquals(bytes.size.toLong(), saved.sizeBytes)
            assertEquals("note.txt", saved.name)
            assertContentEquals(bytes, alice.files.loadStored(saved.id).bytes)
            assertFailsWith<PluginException> { bob.files.loadStored(saved.id) }
            assertFailsWith<PluginException> { alice.files.saveStored("large.bin", ByteArray(1025)) }
            assertNotEquals(saved.id, bob.files.saveStored("note.txt", bytes).id)
            alice.state.put("private", "{\"owner\":\"alice\"}")
            assertNull(bob.state.get("private"))
            assertEquals("{\"owner\":\"alice\"}", alice.state.list().records.single().json)
            clock.now = clock.now.plus(Duration.ofDays(31))
            assertFailsWith<PluginException> { alice.files.loadStored(saved.id) }
            assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
            repository.release(aliceLease)
            repository.release(bobLease)
        }
    }

    @Test
    fun `workspace reads are bounded UTF8 and writes use unique host paths and invocation identity`() = attachmentTest {
        val repository = pluginRepository()
        val lease = lease(repository)
        val settings = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        val home = Files.createDirectories(directory.resolve("owner"))
        val sandbox = LocalRuntimeSandbox(SandboxScope("alice"), settings, home, home.resolve("state"))
        val observed = mutableListOf<ToolInvocationMeta>()
        PluginServices(repository, null, ChannelProviderRegistry(emptyList()),
            ToolInvocationRuntimeSandboxResolver { observed += it; sandbox }, storage).use { services ->
            val identity = PluginInvocationMeta("alice", "conversation", "request", "ru-RU", "Europe/Moscow")
            val context = services.context(lease, identity) {}
            val text = home.resolve("input.txt")
            Files.writeString(text, "Привет ✓")
            assertEquals("Привет ✓", context.files.readWorkspaceText(text.toString()))
            assertNull(context.files.readWorkspaceText(home.resolve("missing.txt").toString()))
            val first = context.files.saveWorkspace("txt", "first".toByteArray())
            val second = context.files.saveWorkspace("txt", "second".toByteArray())
            assertNotEquals(first, second)
            assertTrue(Path.of(first).startsWith(home.resolve("Documents/souz/plugin-files")))
            assertEquals("first", Files.readString(Path.of(first)))
            assertEquals("second", context.files.readWorkspaceText(second))
            assertTrue(observed.all { it == ToolInvocationMeta("alice", "conversation", "request", "ru-RU", "Europe/Moscow") })
            val oversized = Files.write(home.resolve("large.txt"), ByteArray(1_048_577))
            val outside = Files.writeString(directory.resolve("outside.txt"), "outside owner")
            for (path in listOf(oversized, outside)) {
                assertEquals(PluginFailureKind.BUSINESS, assertFailsWith<PluginException> { context.files.readWorkspaceText(path.toString()) }.kind)
            }
            assertEquals(PluginFailureKind.BUSINESS, assertFailsWith<PluginException> {
                context.files.saveWorkspace("../../escape", byteArrayOf())
            }.kind)
            val malformed = Files.write(home.resolve("invalid.txt"), byteArrayOf(0xc3.toByte(), 0x28))
            assertEquals(PluginFailureKind.BUSINESS, assertFailsWith<PluginException> {
                context.files.readWorkspaceText(malformed.toString())
            }.kind)
            repository.release(lease)
        }
    }

    @Test
    fun `workspace rejects symbolic links when supported by the filesystem`() = attachmentTest {
        val repository = pluginRepository()
        val lease = lease(repository)
        val home = Files.createDirectories(directory.resolve("symlink-owner"))
        val target = Files.writeString(home.resolve("target.txt"), "private file")
        val link = createSymbolicLinkOrSkip(home.resolve("link.txt"), target)
        val settings = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        val sandbox = LocalRuntimeSandbox(SandboxScope("alice"), settings, home, home.resolve("state"))
        PluginServices(repository, null, ChannelProviderRegistry(emptyList()),
            ToolInvocationRuntimeSandboxResolver.fixed(sandbox), storage).use { services ->
            val context = services.context(lease, PluginInvocationMeta("alice")) {}
            assertEquals(PluginFailureKind.BUSINESS, assertFailsWith<PluginException> {
                context.files.readWorkspaceText(link.toString())
            }.kind)
        }
    }

    @Test
    fun `OAuth and channels bind the owner and revoked or stale contexts dispatch no services`() = attachmentTest {
        val repository = pluginRepository()
        val lease = lease(repository)
        val oauth = mockk<SkillOAuthGateway>()
        val provider = mockk<ChannelProvider> { every { channelType } returns "chat" }
        var oauthResult: ApiCallOutcome = ApiCallResponse(200, "{\"ok\":true}", mapOf("Content-Type" to "application/json"))
        val calls = mutableListOf<ApiCallRequest>()
        coEvery { oauth.call(any(), any(), any(), any()) } answers {
            assertEquals("alice", firstArg<String>())
            assertEquals("google", secondArg<String>())
            assertEquals(setOf("read"), thirdArg<Set<String>>())
            calls += arg<ApiCallRequest>(3)
            oauthResult
        }
        coEvery { provider.sendAttachments("alice", "owned", "notification", listOf("file")) } returns ChannelSendResult.Delivered("ok")
        var active = true
        PluginServices(repository, oauth, ChannelProviderRegistry(listOf(provider)),
            ToolInvocationRuntimeSandboxResolver { error("This test dispatches no workspace operations") }, storage).use { services ->
            val context = services.context(lease, PluginInvocationMeta("alice", requestId = "correlated-request")) {
                if (!active) throw CancellationException("Invocation revoked")
            }
            val request = PluginOAuthRequest("GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages", maxResponseBytes = 1024)
            assertEquals(PluginOAuthResponse(200, "{\"ok\":true}", mapOf("Content-Type" to "application/json")), context.oauth.call("google", setOf("read"), request))
            assertEquals(1024, calls.last().maxResponseBytes)
            context.oauth.call("google", setOf("read"), request.copy(maxResponseBytes = Int.MAX_VALUE))
            assertEquals(36 * 1_048_576, calls.last().maxResponseBytes)
            oauthResult = ApiCallReconnectRequired("https://consent.example/authorize", "Reconnect")
            assertEquals(PluginReconnectRequired("https://consent.example/authorize", "Reconnect"), context.oauth.call("google", setOf("read"), request))
            coEvery { oauth.call("alice", "missing", any(), any()) } throws SkillOAuthValidationException("Unknown provider")
            assertEquals(PluginFailureKind.BUSINESS, assertFailsWith<PluginException> {
                context.oauth.call("missing", emptySet(), request)
            }.kind)
            coEvery { oauth.call("alice", "broken", any(), any()) } throws SkillOAuthException("Provider unavailable")
            assertFailsWith<SkillOAuthException> { context.oauth.call("broken", emptySet(), request) }
            assertTrue(context.channels.send("chat", "owned", "notification", listOf("file")))
            coEvery { provider.sendAttachments("alice", "owned", "notification", listOf("file")) } returns ChannelSendResult.Failed("uncertain")
            assertFalse(context.channels.send("chat", "owned", "notification", listOf("file")))
            coEvery { provider.sendAttachments("alice", "owned", "notification", listOf("file")) } throws CancellationException("disconnected")
            assertFailsWith<CancellationException> { context.channels.send("chat", "owned", "notification", listOf("file")) }
            assertEquals(PluginFailureKind.BUSINESS, assertFailsWith<PluginException> {
                context.http.execute(PluginHttpRequest("file:///secret"))
            }.kind)
            assertEquals(PluginFailureKind.BUSINESS, assertFailsWith<PluginException> { context.state.put("json", "invalid JSON") }.kind)
            assertEquals(PluginFailureKind.BUSINESS, assertFailsWith<PluginException> { context.state.list(limit = 101) }.kind)

            val logger = LoggerFactory.getLogger(PluginServices::class.java) as Logger
            val logs = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
            try {
                context.log("line\n".repeat(400))
                assertEquals(1, logs.list.size)
                assertFalse(logs.list.single().formattedMessage.contains('\n'))
                assertTrue(logs.list.single().formattedMessage.length <= 1008)
                val fields = logs.list.single().keyValuePairs.associate { it.key to it.value }
                assertEquals("correlated-request", fields["request_id"])
                assertEquals(lease.token.toString(), fields["invocation"].toString())
                active = false
                for (service in serviceCalls(context, request)) assertFailsWith<CancellationException> { service() }
                active = true
                repository.release(lease)
                for (service in serviceCalls(context, request)) {
                    assertEquals("plugin_lease_lost", assertFailsWith<PluginUnavailableException> { service() }.code)
                }
                assertEquals(1, logs.list.size)
            } finally { logger.detachAppender(logs); logs.stop() }
            assertEquals(3, calls.size)
            coVerify(exactly = 3) { provider.sendAttachments(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `workspace write cleans its actual file when cancellation prevents returning the path`() = attachmentTest {
        val repository = pluginRepository()
        val lease = lease(repository)
        val settings = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() }
        val home = Files.createDirectories(directory.resolve("cancelled-owner"))
        val actual = LocalRuntimeSandbox(SandboxScope("alice"), settings, home, home.resolve("state"))
        lateinit var writer: Deferred<String>
        var written: Path? = null
        val sandbox = object : RuntimeSandbox by actual {
            override val fileSystem = object : SandboxFileSystem by actual.fileSystem {
                override fun writeBytes(path: SandboxPathInfo, content: ByteArray) {
                    actual.fileSystem.writeBytes(path, content)
                    written = Path.of(path.path)
                    assertContentEquals(content, Files.readAllBytes(written))
                    // Cancel after real IO completes, before runInterruptible can return its result.
                    writer.cancel(CancellationException("Caller no longer accepts the result"))
                }
            }
        }
        PluginServices(repository, null, ChannelProviderRegistry(emptyList()),
            ToolInvocationRuntimeSandboxResolver.fixed(sandbox), storage).use { services ->
            val context = services.context(lease, PluginInvocationMeta("alice")) {}
            coroutineScope {
                writer = async(start = CoroutineStart.LAZY) { context.files.saveWorkspace("txt", "saved bytes".toByteArray()) }
                writer.start()
                assertFailsWith<CancellationException> { writer.await() }
            }
            assertFalse(Files.exists(assertNotNull(written)))
            assertFalse(Files.exists(assertNotNull(written).parent))
            repository.release(lease)
        }
    }

    private fun serviceCalls(context: PluginContext, oauth: PluginOAuthRequest): List<suspend () -> Unit> = listOf(
        { context.state.get("key") }, { context.state.put("key", "true") }, { context.state.delete("key") },
        { context.state.list() }, { context.http.execute(PluginHttpRequest("http://127.0.0.1:1")) },
        { context.oauth.call("google", setOf("read"), oauth) }, { context.channels.send("chat", "owned", "notification", listOf("file")) },
        { context.files.readWorkspaceText("input.txt") }, { context.files.saveWorkspace("txt", byteArrayOf()) },
        { context.files.loadStored("invalid") }, { context.files.saveStored("text.txt", byteArrayOf()) }, { context.log("late log") },
    )

    private suspend fun AttachmentFixture.lease(repository: PostgresPluginRepository): PluginCallLease {
        val binding = PluginBinding("mail", "v1", "mail".sha256Hex())
        repository.install(binding, 0)
        return assertNotNull(repository.claim("alice", binding, PluginRuntimeConfig()))
    }

    private fun AttachmentFixture.pluginRepository(): PostgresPluginRepository {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("insert into users(id) values ('alice'), ('bob')") }
        }
        return PostgresPluginRepository(dataSource)
    }
}
