package ru.souz.tool.skills

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.llms.ToolInvocationMeta
import ru.souz.skilloauth.ApiCallOutcome
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.AuthorizationState
import ru.souz.skilloauth.SkillOAuthGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolConnectOAuthProviderTest {
    private class FakeSkillOAuthGateway(
        private val connected: Boolean,
    ) : SkillOAuthGateway {
        var ensureAuthorizedCalls = 0
            private set

        override suspend fun ensureAuthorized(
            userId: String,
            provider: String,
            requiredScopes: Set<String>,
            force: Boolean,
        ): AuthorizationState {
            ensureAuthorizedCalls++
            return if (connected && !force) {
                AuthorizationState.Connected
            } else {
                AuthorizationState.AuthorizationRequired("https://fake.example/authorize?provider=$provider")
            }
        }

        override suspend fun call(
            userId: String,
            provider: String,
            requiredScopes: Set<String>,
            request: ApiCallRequest,
        ): ApiCallOutcome = throw UnsupportedOperationException("Not used in this test.")
    }

    private fun oauthSkillBundle(): SkillBundle = SkillBundle(
        skillId = SkillId("skill-1"),
        manifest = SkillManifest(
            name = "skill-1",
            description = "test skill",
            oauthProvider = "yandex",
            oauthScopes = listOf("login:info"),
            rawFrontmatter = "",
        ),
        files = listOf(SkillFile(normalizedPath = "SKILL.md", content = ByteArray(0))),
        skillMarkdownBody = "",
    )

    private fun newTool(gateway: SkillOAuthGateway): ToolConnectOAuthProvider {
        val skillRegistryRepository = mockk<SkillRegistryRepository>()
        coEvery { skillRegistryRepository.loadSkillBundle("user-1", SkillId("skill-1")) } returns oauthSkillBundle()
        return ToolConnectOAuthProvider(skillBundleProvider = skillRegistryRepository, gateway = gateway)
    }

    @Test
    fun `already connected without forceReconnect reports connected without minting a new link`() = runTest {
        val gateway = FakeSkillOAuthGateway(connected = true)
        val tool = newTool(gateway)

        val output = tool.suspendInvoke(
            ToolConnectOAuthProvider.Input(skillId = "skill-1"),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertTrue(output.contains("\"connected\":true"))
        assertEquals(1, gateway.ensureAuthorizedCalls)
    }

    @Test
    fun `forceReconnect issues a fresh authorize link even when already connected`() = runTest {
        // Regression test: a token can be revoked on the provider's side without this service
        // finding out until a call actually fails — ensureAuthorized alone can't detect that, so
        // the user needs a way to force a fresh link despite "connected" still being reported.
        val gateway = FakeSkillOAuthGateway(connected = true)
        val tool = newTool(gateway)

        val output = tool.suspendInvoke(
            ToolConnectOAuthProvider.Input(skillId = "skill-1", forceReconnect = true),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertFalse(output.contains("\"connected\":true"))
        assertTrue(output.contains("fake.example/authorize"))
        assertEquals(1, gateway.ensureAuthorizedCalls)
    }
}
