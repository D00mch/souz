package ru.souz.tool.skills

import kotlinx.coroutines.runBlocking
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.SkillOAuthApi
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

/**
 * Note there is no `provider` field in [Input] anywhere: the target provider comes only from the
 * active Skill's own manifest, loaded (and approval-gated, see [loadApprovedOAuthSkillBundle])
 * fresh on every call. The backend injects the Authorization header itself (mirroring
 * `McpHttpSession`'s pattern) — the raw access token never enters this tool's input/output, the
 * LLM's context, or the skill's sandboxed process. `url` is still a model-supplied full URL, so
 * the provider layer (`SkillOAuthApiImpl.requireAllowedApiUrl`) enforces HTTPS + a per-provider
 * host allowlist before attaching the token — this tool must not be trusted to police that itself.
 */
class ToolSafeApiCall(
    private val skillRegistryRepository: SkillRegistryRepository,
    private val skillOAuthApi: SkillOAuthApi?,
    private val approvalGate: SkillApprovalGate? = null,
) : ToolSetup<ToolSafeApiCall.Input> {
    data class Input(
        @InputParamDescription("Skill ID whose approved manifest declares the OAuth provider and scopes for this operation.")
        val skillId: String,
        @InputParamDescription("HTTP method, e.g. GET, POST, PUT, DELETE.")
        val method: String,
        @InputParamDescription("Full request URL, as documented by the target provider's API.")
        val url: String,
        @InputParamDescription("Optional request body.")
        val body: String? = null,
        @InputParamDescription("Optional extra request headers, e.g. Content-Type overrides. Cannot be used to set Authorization — that header is always injected by the backend.")
        val headers: Map<String, String> = emptyMap(),
    )

    /**
     * Exactly one of ([statusCode], [body]) or ([reconnectRequired] with [authorizationUrl]) is
     * populated. Needing to (re)connect — no token yet, an expired/unrefreshable one, a revoked
     * refresh token, or a scope the shared credential doesn't cover yet — is a normal outcome of
     * calling this tool, not a failure: [authorizationUrl] is already generated, ready to relay to
     * the user verbatim, without a separate `ConnectOAuthProvider` call first.
     */
    data class Output(
        val statusCode: Int? = null,
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val reconnectRequired: Boolean = false,
        val authorizationUrl: String? = null,
        val message: String? = null,
    )

    override val name: String = "SafeApiCall"
    override val description: String =
        "Calls a third-party API on behalf of the user using the OAuth connection a Skill declared " +
            "in its manifest. The access token is injected by the backend and never exposed here — " +
            "there is no way to target a provider other than the one the active Skill declared. " +
            "If the response has reconnectRequired=true, relay authorizationUrl to the user verbatim " +
            "and ask them to retry once they're done — there is no need to call ConnectOAuthProvider " +
            "separately first."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Получи данные пользователя через API провайдера",
            params = mapOf(
                "skillId" to "skill-id",
                "method" to "GET",
                "url" to "https://login.yandex.ru/info",
            ),
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "statusCode" to ReturnProperty("number", "HTTP status code returned by the provider."),
            "body" to ReturnProperty("string", "Response body returned by the provider."),
            "headers" to ReturnProperty("object", "Response headers returned by the provider."),
            "reconnectRequired" to ReturnProperty("boolean", "True if a (re)connect is needed before this call can succeed."),
            "authorizationUrl" to ReturnProperty("string", "URL to relay to the user when reconnectRequired is true."),
            "message" to ReturnProperty("string", "Human-readable explanation of why reconnecting is needed."),
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val api = skillOAuthApi
            ?: throw BadInputException("OAuth connections are not available in this runtime.")
        val skillId = input.skillId.trim()
        val bundle = loadApprovedOAuthSkillBundle(skillRegistryRepository, approvalGate, meta.userId, skillId)
        val provider = bundle.manifest.oauthProvider
            ?: throw BadInputException("Skill '$skillId' does not declare an oauthProvider in its manifest.")

        val outcome = api.callAuthorizedApi(
            userId = meta.userId,
            provider = provider,
            skillId = skillId,
            requiredScopes = bundle.manifest.oauthScopes,
            request = ApiCallRequest(method = input.method, url = input.url, body = input.body, headers = input.headers),
        )
        val output = when (outcome) {
            is ApiCallResponse -> Output(statusCode = outcome.statusCode, body = outcome.body, headers = outcome.headers)
            is ApiCallReconnectRequired -> Output(
                reconnectRequired = true,
                authorizationUrl = outcome.authorizationUrl,
                message = outcome.message,
            )
        }
        return restJsonMapper.writeValueAsString(output)
    }
}
