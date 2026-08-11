package ru.souz.tool

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.SkillToolBindingTags
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.knowledge.SandboxConversationKnowledgeStore
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.skilloauth.SkillOAuthApi
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.FactoryBackedToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.ToolInvocationSandboxScopeResolver
import ru.souz.skills.registry.SkillStorageScope
import ru.souz.tool.files.DeferredToolModifyPermissionBroker
import ru.souz.tool.files.ToolDeleteFile
import ru.souz.tool.files.ToolFindFilesByName
import ru.souz.tool.files.ToolFindFolders
import ru.souz.tool.files.ToolFindInFiles
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolListFiles
import ru.souz.tool.files.ToolModifyFile
import ru.souz.tool.files.ToolMoveFile
import ru.souz.tool.files.ToolNewFile
import ru.souz.tool.files.ToolViewImage
import ru.souz.tool.math.ToolCalculator
import ru.souz.tool.knowledge.KnowledgeRetriever
import ru.souz.tool.knowledge.ToolGetKnowledge
import ru.souz.tool.knowledge.ToolSearchKnowledge
import ru.souz.tool.memory.ToolSearchMemory
import ru.souz.tool.skills.ToolCheckOAuthStatus
import ru.souz.tool.skills.ToolConnectOAuthProvider
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.skills.ToolRunSkillCommand
import ru.souz.tool.skills.ToolSafeApiCall
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolInternetSearch
import ru.souz.tool.web.ToolWebPageText
import ru.souz.tool.web.internal.WebResearchClient

fun portableRuntimeToolsDiModule(
    skillStorageScope: SkillStorageScope = SkillStorageScope.SINGLE_USER,
    scopeResolver: ToolInvocationSandboxScopeResolver = defaultToolInvocationSandboxScopeResolver(),
    bindAgentToolCatalog: Boolean = true,
): DI.Module = DI.Module("portableRuntimeTools") {
    bindSingleton<ToolInvocationSandboxScopeResolver> { scopeResolver }
    bindSingleton<ToolInvocationRuntimeSandboxResolver> {
        FactoryBackedToolInvocationRuntimeSandboxResolver(
            sandboxFactory = instance<RuntimeSandboxFactory>(),
            scopeResolver = instance(),
        )
    }
    bindSingleton { FilesToolUtil(instance<ToolInvocationRuntimeSandboxResolver>()) }

    bindSingleton { ToolListFiles(instance()) }
    bindSingleton { ToolFindInFiles(instance()) }
    bindSingleton { ToolNewFile(instance()) }
    bindSingleton { ToolDeleteFile(instance(), instanceOrNull<ToolPermissionBroker>()) }
    bindSingleton { ToolModifyFile(instance(), instanceOrNull<DeferredToolModifyPermissionBroker>()) }
    bindSingleton { ToolMoveFile(instance(), instanceOrNull<ToolPermissionBroker>()) }
    bindSingleton { ToolFindFilesByName(instance()) }
    bindSingleton { ToolFindFolders(instance()) }
    bindSingleton { ToolViewImage(filesToolUtil = instance(), visionGateway = instance()) }
    bindSingleton { ToolGenerateImage(filesToolUtil = instance(), imageGenerationGateway = instance()) }
    bindSingleton { ToolCalculator() }

    bindSingleton { WebResearchClient() }
    bindSingleton { ToolInternetSearch(api = instance(), settingsProvider = instance(), filesToolUtil = instance(), webResearchClient = instance()) }
    bindSingleton { ToolInternetResearch(api = instance(), settingsProvider = instance(), filesToolUtil = instance(), webResearchClient = instance()) }
    bindSingleton { ToolWebPageText(webResearchClient = instance()) }

    bindSingleton {
        ToolConnectOAuthProvider(
            skillBundleProvider = instance<SkillRegistryRepository>(),
            skillOAuthApi = instanceOrNull(),
            approvalGate = instanceOrNull(),
        )
    }
    bindSingleton {
        ToolCheckOAuthStatus(
            skillBundleProvider = instance<SkillRegistryRepository>(),
            skillOAuthApi = instanceOrNull(),
            approvalGate = instanceOrNull(),
        )
    }
    bindSingleton {
        ToolSafeApiCall(
            skillBundleProvider = instance<SkillRegistryRepository>(),
            skillOAuthApi = instanceOrNull(),
            approvalGate = instanceOrNull(),
        )
    }

    bindSingleton {
        PortableRuntimeToolsFactory(
            toolListFiles = instance(),
            toolFindInFiles = instance(),
            toolNewFile = instance(),
            toolDeleteFile = instance(),
            toolModifyFile = instance(),
            toolMoveFile = instance(),
            toolFindFilesByName = instance(),
            toolFindFolders = instance(),
            toolViewImage = instance(),
            toolGenerateImage = instance(),
            toolCalculator = instance(),
            toolInternetSearch = instance(),
            toolInternetResearch = instance(),
            toolWebPageText = instance(),
            toolConnectOAuthProvider = instance(),
            toolCheckOAuthStatus = instance(),
            toolSafeApiCall = instance(),
            // The tools above are always constructed (they degrade to a clear "not available"
            // error at call time when skillOAuthApi is null), but a host with no OAuth service
            // bound at all — a supported, valid deployment — shouldn't advertise them as callable
            // in the first place.
            hasSkillOAuthApi = instanceOrNull<SkillOAuthApi>() != null,
        )
    }
    if (bindAgentToolCatalog) {
        bindSingleton<AgentToolCatalog> { instance<PortableRuntimeToolsFactory>() }
    }
    bindSingleton<AgentToolsFilter> { RuntimePassThroughToolsFilter }
    import(portableSkillToolsDiModule(skillStorageScope = skillStorageScope))
}

fun portableSkillToolsDiModule(
    skillStorageScope: SkillStorageScope = SkillStorageScope.SINGLE_USER,
): DI.Module = DI.Module("portableSkillTools") {
    bindSingleton { SandboxConversationKnowledgeStore(instance()) }
    bindSingleton<ConversationKnowledgeStore> { instance<SandboxConversationKnowledgeStore>() }
    bindSingleton {
        ToolRunSkillCommand(
            sandboxResolver = instance(),
            skillStorageScope = skillStorageScope,
        )
    }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.COMMAND_TOOL) {
        instance<ToolRunSkillCommand>().toGiga()
    }
    bindSingleton {
        ToolGetSkillByName(
            toolCatalog = instance(),
            toolsFilter = instance(),
            skillBundleProvider = instance<SkillRegistryRepository>(),
            legacyCommandTool = instance(tag = SkillToolBindingTags.COMMAND_TOOL),
            approvalGate = instanceOrNull<SkillApprovalGate>(),
        )
    }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.GET_SKILL_BY_NAME_TOOL) {
        instance<ToolGetSkillByName>()
    }
    bindSingleton {
        ToolGetSkillsNamesByCategory(
            toolCatalog = instance(),
            toolsFilter = instance(),
        )
    }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.GET_SKILLS_NAMES_BY_CATEGORY_TOOL) {
        instance<ToolGetSkillsNamesByCategory>()
    }
    bindSingleton {
        ToolGetSkillsByCategory(
            getSkillByName = instance(),
            getSkillsNamesByCategory = instance(),
        )
    }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.GET_SKILLS_BY_CATEGORY_TOOL) {
        instance<ToolGetSkillsByCategory>()
    }
    bindSingleton { KnowledgeRetriever(instance()) }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.GET_KNOWLEDGE_TOOL) {
        ToolGetKnowledge(retriever = instance())
    }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.SEARCH_KNOWLEDGE_TOOL) {
        ToolSearchKnowledge(retriever = instance())
    }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.SEARCH_MEMORY_TOOL) {
        ToolSearchMemory(instanceOrNull<ConversationMemoryRuntime>() ?: NoopConversationMemoryRuntime)
    }
    bindSingleton {
        ToolInvokeSkill(
            toolCatalog = instance(),
            toolsFilter = instance(),
            skillBundleProvider = instance<SkillRegistryRepository>(),
            commandTool = instance(),
            approvalGate = instanceOrNull<SkillApprovalGate>(),
        )
    }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.RUNTIME_COMMAND_TOOL) {
        instance<ToolInvokeSkill>()
    }
}

fun defaultToolInvocationSandboxScopeResolver(): ToolInvocationSandboxScopeResolver =
    ToolInvocationSandboxScopeResolver {
        SandboxScope(
            userId = it.userId.trim(),
            conversationId = it.conversationId,
        )
    }

object RuntimePassThroughToolsFilter : AgentToolsFilter {
    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
}

class PortableRuntimeToolsFactory(
    private val toolListFiles: ToolListFiles,
    private val toolFindInFiles: ToolFindInFiles,
    private val toolNewFile: ToolNewFile,
    private val toolDeleteFile: ToolDeleteFile,
    private val toolModifyFile: ToolModifyFile,
    private val toolMoveFile: ToolMoveFile,
    private val toolFindFilesByName: ToolFindFilesByName,
    private val toolFindFolders: ToolFindFolders,
    private val toolViewImage: ToolViewImage,
    private val toolGenerateImage: ToolGenerateImage,
    private val toolCalculator: ToolCalculator,
    private val toolInternetSearch: ToolInternetSearch,
    private val toolInternetResearch: ToolInternetResearch,
    private val toolWebPageText: ToolWebPageText,
    private val toolConnectOAuthProvider: ToolConnectOAuthProvider,
    private val toolCheckOAuthStatus: ToolCheckOAuthStatus,
    private val toolSafeApiCall: ToolSafeApiCall,
    private val hasSkillOAuthApi: Boolean,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> by lazy {
        ToolCategory.entries.associateWith { category ->
            category.tools().associateBy { it.fn.name }
        }
    }

    private fun ToolCategory.tools(): List<LLMToolSetup> = when (this) {
        ToolCategory.FILES -> listOf(
            toolListFiles.toGiga(),
            toolFindInFiles.toGiga(),
            toolNewFile.toGiga(),
            toolDeleteFile.toGiga(),
            toolModifyFile.toGiga(),
            toolMoveFile.toGiga(),
            toolFindFilesByName.toGiga(),
            toolFindFolders.toGiga(),
        )

        ToolCategory.IMAGE -> listOf(toolViewImage.toGiga())
        ToolCategory.IMAGE_GENERATION -> listOf(toolGenerateImage.toGiga())
        ToolCategory.WEB_SEARCH -> listOf(
            toolInternetSearch.toGiga(),
            toolInternetResearch.toGiga(),
            toolWebPageText.toGiga(),
        )
        ToolCategory.CALCULATOR -> listOf(toolCalculator.toGiga())

        ToolCategory.OAUTH -> if (hasSkillOAuthApi) {
            listOf(
                toolConnectOAuthProvider.toGiga(),
                toolCheckOAuthStatus.toGiga(),
                toolSafeApiCall.toGiga(),
            )
        } else {
            emptyList()
        }

        ToolCategory.CONFIG,
        ToolCategory.DATA_ANALYTICS,
        ToolCategory.BROWSER,
        ToolCategory.NOTES,
        ToolCategory.APPLICATIONS,
        ToolCategory.CALENDAR,
        ToolCategory.MAIL,
        ToolCategory.TEXT_REPLACE,
        ToolCategory.CHAT,
        ToolCategory.TELEGRAM,
        ToolCategory.DESKTOP,
        ToolCategory.HELP -> emptyList()
    }
}
