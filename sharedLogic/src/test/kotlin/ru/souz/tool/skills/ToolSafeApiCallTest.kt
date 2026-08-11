package ru.souz.tool.skills

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.llms.ToolInvocationMeta
import ru.souz.skilloauth.ApiCallOutcome
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.AuthorizationUrl
import ru.souz.skilloauth.OAuthStatus
import ru.souz.skilloauth.SkillOAuthApi
import ru.souz.tool.BadInputException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Confirms the core defense discussed in the design: there is no way for a tool call to name a
 * provider other than the one the active Skill's own manifest declares, because [ToolSafeApiCall.Input]
 * has no `provider` field at all — it is read fresh from [SkillRegistryRepository] on every call.
 */
class ToolSafeApiCallTest {
    private fun bundleWith(oauthProvider: String?, oauthScopes: List<String> = emptyList()): SkillBundle {
        val markdown = SkillFile(normalizedPath = "SKILL.md", content = "---\nname: test\n---\n".toByteArray())
        return SkillBundle(
            skillId = SkillId("skill-1"),
            manifest = SkillManifest(
                name = "test",
                description = "test skill",
                oauthProvider = oauthProvider,
                oauthScopes = oauthScopes,
                rawFrontmatter = "",
            ),
            files = listOf(markdown),
            skillMarkdownBody = "",
        )
    }

    private class FakeSkillOAuthApi(
        private val outcome: ApiCallOutcome = ApiCallResponse(200, "{}"),
    ) : SkillOAuthApi {
        var lastProvider: String? = null
            private set
        var lastRequiredScopes: List<String>? = null
            private set

        override suspend fun status(userId: String, provider: String, requiredScopes: List<String>): OAuthStatus =
            OAuthStatus(connected = true)

        override suspend fun startAuthorization(
            userId: String,
            provider: String,
            skillId: String,
            scopes: List<String>,
        ): AuthorizationUrl = AuthorizationUrl("https://example.com/authorize")

        override suspend fun callAuthorizedApi(
            userId: String,
            provider: String,
            skillId: String,
            requiredScopes: List<String>,
            request: ApiCallRequest,
        ): ApiCallOutcome {
            lastProvider = provider
            lastRequiredScopes = requiredScopes
            return outcome
        }
    }

    @Test
    fun `input schema has no provider field to smuggle a different connection`() {
        val fields = ToolSafeApiCall.Input::class.java.declaredFields.map { it.name }

        assertTrue("provider" !in fields, "Input must never expose a caller-controlled provider field: $fields")
    }

    @Test
    fun `forwards the call using the provider and scopes declared in the skill's own manifest`() = runTest {
        val repository = mockk<SkillRegistryRepository>()
        coEvery { repository.loadSkillBundle("user-1", SkillId("skill-1")) } returns
            bundleWith(oauthProvider = "yandex", oauthScopes = listOf("login:info"))
        val api = FakeSkillOAuthApi(outcome = ApiCallResponse(200, "ok"))
        val tool = ToolSafeApiCall(skillBundleProvider = repository, skillOAuthApi = api)

        val result = tool.suspendInvoke(
            ToolSafeApiCall.Input(skillId = "skill-1", method = "GET", url = "https://login.yandex.ru/info"),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertEquals("yandex", api.lastProvider)
        assertEquals(listOf("login:info"), api.lastRequiredScopes)
        assertTrue(result.contains("\"statusCode\":200"))
    }

    @Test
    fun `surfaces reconnectRequired as a normal output instead of throwing`() = runTest {
        // Needing to (re)connect is a routine outcome (expired token, missing scope, revoked
        // refresh token) — the tool must return it as data the model can relay to the user, not
        // as a thrown exception the model has to interpret from a "Can't invoke function: ..."
        // string.
        val repository = mockk<SkillRegistryRepository>()
        coEvery { repository.loadSkillBundle("user-1", SkillId("skill-1")) } returns
            bundleWith(oauthProvider = "yandex", oauthScopes = listOf("login:info"))
        val api = FakeSkillOAuthApi(
            outcome = ApiCallReconnectRequired(
                authorizationUrl = "https://oauth.yandex.ru/authorize?state=abc",
                message = "The OAuth connection for 'yandex' has expired. Open this link to reconnect, then retry: https://oauth.yandex.ru/authorize?state=abc",
            )
        )
        val tool = ToolSafeApiCall(skillBundleProvider = repository, skillOAuthApi = api)

        val result = tool.suspendInvoke(
            ToolSafeApiCall.Input(skillId = "skill-1", method = "GET", url = "https://login.yandex.ru/info"),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertTrue(result.contains("\"reconnectRequired\":true"))
        assertTrue(result.contains("https://oauth.yandex.ru/authorize?state=abc"))
    }

    @Test
    fun `rejects a skill that does not declare an oauthProvider`() = runTest {
        val repository = mockk<SkillRegistryRepository>()
        coEvery { repository.loadSkillBundle("user-1", SkillId("skill-1")) } returns bundleWith(oauthProvider = null)
        val tool = ToolSafeApiCall(skillBundleProvider = repository, skillOAuthApi = FakeSkillOAuthApi())

        assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolSafeApiCall.Input(skillId = "skill-1", method = "GET", url = "https://example.com"),
                ToolInvocationMeta(userId = "user-1"),
            )
        }
    }

    @Test
    fun `fails clearly when no OAuth implementation is wired for this runtime`() = runTest {
        val repository = mockk<SkillRegistryRepository>()
        val tool = ToolSafeApiCall(skillBundleProvider = repository, skillOAuthApi = null)

        assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolSafeApiCall.Input(skillId = "skill-1", method = "GET", url = "https://example.com"),
                ToolInvocationMeta(userId = "user-1"),
            )
        }
    }

    @Test
    fun `rejects a stored bundle that failed skill approval`() = runTest {
        // Regression test: a stored-but-unapproved (or rejected) bundle declaring oauthProvider
        // must not be able to drive a real OAuth API call just because it's on disk.
        val repository = mockk<SkillRegistryRepository>()
        coEvery { repository.loadSkillBundle("user-1", SkillId("skill-1")) } returns
            bundleWith(oauthProvider = "yandex")
        val approvalGate = mockk<SkillApprovalGate>()
        coEvery { approvalGate.ensureApproved(any()) } returns
            SkillApprovalGate.Result.Rejected(bundleHash = "hash", reason = "rejected in test", findings = emptyList())
        val tool = ToolSafeApiCall(
            skillBundleProvider = repository,
            skillOAuthApi = FakeSkillOAuthApi(),
            approvalGate = approvalGate,
        )

        assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolSafeApiCall.Input(skillId = "skill-1", method = "GET", url = "https://login.yandex.ru/info"),
                ToolInvocationMeta(userId = "user-1"),
            )
        }
    }
}
