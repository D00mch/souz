package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

/**
 * Replaces `ToolCategory.OAUTH` with a request-scoped set of tools built with a real
 * [ru.souz.agent.skills.validation.SkillApprovalGate] (see
 * `BackendSkillCoreToolsFactory.createOAuthTools`). Without this override, whatever the
 * DI-singleton [delegate] catalog provides for `ToolCategory.OAUTH` would be used instead — those
 * instances always resolve `approvalGate` to null, since `SkillApprovalGate` is never bound in
 * Kodein, which would let a stored-but-unapproved skill bundle drive a real OAuth connection or
 * authorized API call. [oauthTools] fully replaces the category (including when empty, which
 * omits it entirely) rather than merging with it.
 */
internal class BackendOAuthToolCatalogOverride(
    private val delegate: AgentToolCatalog,
    private val oauthTools: Map<String, LLMToolSetup>,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        delegate.toolsByCategory + (ToolCategory.OAUTH to oauthTools)
}
