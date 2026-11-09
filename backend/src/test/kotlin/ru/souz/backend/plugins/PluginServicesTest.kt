package ru.souz.backend.plugins

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.slf4j.LoggerFactory
import ru.souz.backend.attachments.AttachmentFixture
import ru.souz.backend.attachments.attachmentTest
import ru.souz.backend.channels.ChannelProvider
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.backend.crypto.sha256Hex
import ru.souz.plugins.api.PluginContext
import ru.souz.plugins.api.PluginException
import ru.souz.plugins.api.PluginFailureKind
import ru.souz.plugins.api.PluginHttpRequest
import ru.souz.plugins.api.PluginInvocationMeta
import ru.souz.plugins.api.PluginOAuthRequest
import ru.souz.plugins.api.PluginOAuthResponse
import ru.souz.plugins.api.PluginReconnectRequired
import ru.souz.skilloauth.ApiCallOutcome
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.SkillOAuthException
import ru.souz.skilloauth.SkillOAuthGateway
import ru.souz.skilloauth.SkillOAuthValidationException

class PluginServicesTest {
    @Test
    fun `S3 files preserve owner bounds and retention without resolving a workspace`() = attachmentTest {
        val repository = pluginRepository()
        val binding = PluginBinding("mail", "v1", "mail".sha256Hex())
        repository.install(binding, 0)
        PluginServices(repository, null, ChannelProviderRegistry(emptyList()), storage).use { services ->
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
        PluginServices(repository, oauth, ChannelProviderRegistry(listOf(provider)), storage).use { services ->
            val context = services.context(lease, PluginInvocationMeta("alice", requestId = "correlated-request")) {
                if (!active) throw CancellationException("Invocation revoked")
            }
            val request = PluginOAuthRequest("GET", "https://api.example.com/items", maxResponseBytes = 1024)
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

    private fun serviceCalls(context: PluginContext, oauth: PluginOAuthRequest): List<suspend () -> Unit> = listOf(
        { context.state.get("key") }, { context.state.put("key", "true") }, { context.state.delete("key") },
        { context.state.list() }, { context.http.execute(PluginHttpRequest("http://127.0.0.1:1")) },
        { context.oauth.call("google", setOf("read"), oauth) }, { context.channels.send("chat", "owned", "notification", listOf("file")) },
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
