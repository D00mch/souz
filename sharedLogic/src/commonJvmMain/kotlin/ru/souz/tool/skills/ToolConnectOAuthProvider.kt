package ru.souz.tool.skills

import kotlinx.coroutines.runBlocking
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.skilloauth.SkillOAuthApi
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

/**
 * The `provider` is never a caller-supplied argument: it is read fresh from the active Skill's own
 * manifest ([SkillRegistryRepository.loadSkillBundle]) on every call, the same way
 * [ToolInvokeSkill] re-derives `runsOnDevice` rather than trusting anything the caller supplied.
 * This is what makes the tool resistant to indirect prompt injection — there is no field in [Input]
 * a hijacked model turn could use to redirect the call to a different provider's connection. The
 * bundle lookup goes through the same [SkillApprovalGate] as [ToolGetSkillByName]/[ToolInvokeSkill]
 * so a stored-but-unapproved bundle can't drive a real OAuth connection either.
 */
class ToolConnectOAuthProvider(
    private val skillRegistryRepository: SkillRegistryRepository,
    private val skillOAuthApi: SkillOAuthApi?,
    private val approvalGate: SkillApprovalGate? = null,
) : ToolSetup<ToolConnectOAuthProvider.Input> {
    data class Input(
        @InputParamDescription("Activated Skill ID that declares an oauthProvider in its manifest.")
        val skillId: String,
    )

    data class Output(
        val connected: Boolean,
        val authorizationUrl: String? = null,
        val message: String,
    )

    override val name: String = "ConnectOAuthProvider"
    override val description: String =
        "Starts (or confirms) the OAuth connection a Skill declared in its manifest. " +
            "If already connected, returns connected=true. Otherwise returns a URL the user must " +
            "open in a browser to grant access; relay it to the user verbatim and ask them to retry " +
            "the action once they're done."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Подключи доступ к провайдеру для этого скилла",
            params = mapOf("skillId" to "skill-id"),
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "connected" to ReturnProperty("boolean", "Whether the provider is already connected."),
            "authorizationUrl" to ReturnProperty("string", "URL to open when not yet connected."),
            "message" to ReturnProperty("string", "Human-readable status message."),
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
        val requiredScopes = bundle.manifest.oauthScopes

        val status = api.status(meta.userId, provider, requiredScopes)
        val output = if (status.connected) {
            Output(connected = true, message = "Already connected to $provider.")
        } else {
            // Request the union of what's already granted plus what this skill declares, so
            // connecting a second, broader-scoped skill doesn't shrink access for skills already
            // relying on the shared (userId, provider) credential.
            val scopesToRequest = (status.grantedScopes + requiredScopes).distinct()
            val authorization = api.startAuthorization(meta.userId, provider, skillId, scopesToRequest)
            Output(
                connected = false,
                authorizationUrl = authorization.url,
                message = "Open this link to connect $provider, then retry: ${authorization.url}",
            )
        }
        return restJsonMapper.writeValueAsString(output)
    }
}
