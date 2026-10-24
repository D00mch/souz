package ru.souz.tool

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.giga.toGiga
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.FactoryBackedToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.ToolInvocationSandboxScopeResolver
import ru.souz.skilloauth.SkillOAuthGateway
import ru.souz.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.dataAnalytics.excel.ExcelReport
import ru.souz.tool.files.ToolDeleteFile
import ru.souz.tool.files.ToolExtractText
import ru.souz.tool.files.ToolFindFilesByName
import ru.souz.tool.files.ToolFindFolders
import ru.souz.tool.files.ToolFindInFiles
import ru.souz.tool.files.ToolListFiles
import ru.souz.tool.files.ToolModifyFile
import ru.souz.tool.files.ToolMoveFile
import ru.souz.tool.files.ToolNewFile
import ru.souz.tool.files.ToolReadPdfPages
import ru.souz.tool.math.ToolCalculator
import ru.souz.tool.skills.ToolConnectOAuthProvider
import ru.souz.tool.skills.ToolSafeApiCall
import ru.souz.tool.web.internal.WebResearchClient

fun runtimeToolsDiModule(
    scopeResolver: ToolInvocationSandboxScopeResolver = ToolInvocationSandboxScopeResolver {
        SandboxScope(userId = it.userId.trim(), conversationId = it.conversationId)
    },
): DI.Module = DI.Module("runtimeTools") {
    bindSingleton<RuntimeSandboxFactory> { DefaultRuntimeSandboxFactory(settingsProvider = instance()) }
    bindSingleton<ToolInvocationRuntimeSandboxResolver> {
        FactoryBackedToolInvocationRuntimeSandboxResolver(instance(), scopeResolver)
    }
    bindSingleton { FilesToolUtil(instance<ToolInvocationRuntimeSandboxResolver>()) }
    bindSingleton { WebResearchClient() }
    bindSingleton {
        val gateway = instanceOrNull<SkillOAuthGateway>()
        RuntimeToolsFactory(
            filesToolUtil = instance(),
            gateway = gateway,
            skillRegistry = gateway?.let { instance<SkillRegistryRepository>() },
            approvalGate = instanceOrNull(),
        )
    }
    bindSingleton<AgentToolCatalog> { instance<RuntimeToolsFactory>().catalog }
}

class RuntimeToolsFactory(
    filesToolUtil: FilesToolUtil,
    gateway: SkillOAuthGateway? = null,
    skillRegistry: SkillRegistryRepository? = null,
    approvalGate: SkillApprovalGate? = null,
) {
    val catalog = AgentToolCatalog(
        mapOf(
            ToolCategory.FILES to listOf(
                ToolListFiles(filesToolUtil).toGiga(),
                ToolFindInFiles(filesToolUtil).toGiga(),
                ToolNewFile(filesToolUtil).toGiga(),
                ToolDeleteFile(filesToolUtil).toGiga(),
                ToolModifyFile(filesToolUtil).toGiga(),
                ToolMoveFile(filesToolUtil).toGiga(),
                ToolFindFilesByName(filesToolUtil).toGiga(),
                ToolFindFolders(filesToolUtil).toGiga(),
                ToolExtractText(filesToolUtil).toGiga(),
                ToolReadPdfPages(filesToolUtil).toGiga(),
            ),
            ToolCategory.CALCULATOR to listOf(ToolCalculator().toGiga()),
            ToolCategory.DATA_ANALYTICS to listOf(
                ToolCreatePlotFromCsv(filesToolUtil).toGiga(),
                ExcelRead(filesToolUtil).toGiga(),
                ExcelReport(filesToolUtil).toGiga(),
            ),
            ToolCategory.OAUTH to if (gateway == null) emptyList() else listOf(
                ToolConnectOAuthProvider(requireNotNull(skillRegistry), gateway, approvalGate).toGiga(),
                ToolSafeApiCall(requireNotNull(skillRegistry), gateway, approvalGate).toGiga(),
            ),
        ),
    )
}
