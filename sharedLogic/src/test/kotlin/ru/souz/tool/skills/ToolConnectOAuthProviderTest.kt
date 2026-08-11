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
import ru.souz.skilloauth.AuthorizationUrl
import ru.souz.skilloauth.OAuthStatus
import ru.souz.skilloauth.SkillOAuthApi
import ru.souz.skilloauth.ApiCallOutcome
import ru.souz.skilloauth.ApiCallRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolConnectOAuthProviderTest {
    private class FakeSkillOAuthApi(
        private val status: OAuthStatus,
    ) : SkillOAuthApi {
        var startAuthorizationCalls = 0
            private set

        override suspend fun status(userId: String, provider: String, requiredScopes: List<String>): OAuthStatus = status

        override suspend fun startAuthorization(
            userId: String,
            provider: String,
            skillId: String,
            scopes: List<String>,
        ): AuthorizationUrl {
            startAuthorizationCalls++
            return AuthorizationUrl("https://fake.example/authorize?provider=$provider")
        }

        override suspend fun callAuthorizedApi(
            userId: String,
            provider: String,
            skillId: String,
            requiredScopes: List<String>,
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

    private fun newTool(api: SkillOAuthApi): ToolConnectOAuthProvider {
        val skillRegistryRepository = mockk<SkillRegistryRepository>()
        coEvery { skillRegistryRepository.loadSkillBundle("user-1", SkillId("skill-1")) } returns oauthSkillBundle()
        return ToolConnectOAuthProvider(skillBundleProvider = skillRegistryRepository, skillOAuthApi = api)
    }

    @Test
    fun `already connected without forceReconnect reports connected without minting a new link`() = runTest {
        val api = FakeSkillOAuthApi(status = OAuthStatus(connected = true, grantedScopes = listOf("login:info")))
        val tool = newTool(api)

        val output = tool.suspendInvoke(
            ToolConnectOAuthProvider.Input(skillId = "skill-1"),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertTrue(output.contains("\"connected\":true"))
        assertEquals(0, api.startAuthorizationCalls)
    }

    @Test
    fun `forceReconnect issues a fresh authorize link even when already connected`() = runTest {
        // Regression test: a token can be revoked on the provider's side without this service
        // finding out until a call actually fails — status() alone can't detect that, so the user
        // needs a way to force a fresh link despite "connected" still being reported.
        val api = FakeSkillOAuthApi(status = OAuthStatus(connected = true, grantedScopes = listOf("login:info")))
        val tool = newTool(api)

        val output = tool.suspendInvoke(
            ToolConnectOAuthProvider.Input(skillId = "skill-1", forceReconnect = true),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertFalse(output.contains("\"connected\":true"))
        assertTrue(output.contains("fake.example/authorize"))
        assertEquals(1, api.startAuthorizationCalls)
    }
}
