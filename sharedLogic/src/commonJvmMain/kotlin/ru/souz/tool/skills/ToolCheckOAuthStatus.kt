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

class ToolCheckOAuthStatus(
    private val skillRegistryRepository: SkillRegistryRepository,
    private val skillOAuthApi: SkillOAuthApi?,
    private val approvalGate: SkillApprovalGate? = null,
) : ToolSetup<ToolCheckOAuthStatus.Input> {
    data class Input(
        @InputParamDescription("Activated Skill ID that declares an oauthProvider in its manifest.")
        val skillId: String,
    )

    data class Output(
        val connected: Boolean,
        val grantedScopes: List<String>,
        val missingScopes: List<String>,
    )

    override val name: String = "CheckOAuthStatus"
    override val description: String =
        "Checks whether the OAuth provider a Skill declared in its manifest is already connected " +
            "for this user, without starting a new authorization."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Проверь, подключён ли провайдер для этого скилла",
            params = mapOf("skillId" to "skill-id"),
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "connected" to ReturnProperty("boolean", "Whether the provider is connected AND covers this skill's declared oauthScopes."),
            "grantedScopes" to ReturnProperty("array", "Scopes currently granted, if connected."),
            "missingScopes" to ReturnProperty("array", "This skill's declared scopes not yet granted, if any."),
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

        val status = api.status(meta.userId, provider, bundle.manifest.oauthScopes)
        return restJsonMapper.writeValueAsString(Output(status.connected, status.grantedScopes, status.missingScopes))
    }
}
