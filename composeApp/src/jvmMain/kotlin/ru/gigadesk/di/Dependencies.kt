package ru.gigadesk.di

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.agent.nodes.NodesErrorHandling
import ru.gigadesk.agent.nodes.NodesCommon
import ru.gigadesk.agent.nodes.NodesLLM
import ru.gigadesk.agent.nodes.NodesSummarization
import ru.gigadesk.agent.nodes.NodesClassification
import ru.gigadesk.agent.session.GraphSessionRepository
import ru.gigadesk.agent.session.GraphSessionService
import ru.gigadesk.audio.Say
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.db.VectorDB
import ru.gigadesk.giga.ApiClassifier
import ru.gigadesk.giga.GigaAuth
import ru.gigadesk.giga.GigaChatAPI
import ru.gigadesk.giga.GigaGRPCChatApi
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.giga.GigaVoiceAPI
import ru.gigadesk.keys.Keys
import ru.gigadesk.tool.LocalRegexClassifier
import ru.gigadesk.tool.ToolsFactory
import ru.gigadesk.tool.ToolsSettings

private object DiTags {
    const val MODULE_MAIN = "main"

    const val TAG_LOG = "log"
    const val TAG_API = "api"
    const val TAG_LOCAL = "local"
}

val mainDiModule = DI.Module(DiTags.MODULE_MAIN) {
    // utils
    bindSingleton(tag = DiTags.TAG_LOG) {
        jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
    }
    bindSingleton { Say() }

    // Native
    bindSingleton { Keys() }

    // DB
    bindSingleton { ConfigStore }
    bindSingleton { VectorDB }
    bindSingleton { SettingsProvider(instance()) }
    bindSingleton { DesktopInfoRepository(instance(), instance()) }
    bindSingleton { ToolsSettings(instance(), instance()) }
    bindSingleton { GraphSessionRepository() }
    bindSingleton { GraphSessionService(instance(), instance(DiTags.TAG_LOG)) }

    // API
    bindSingleton { GigaAuth(instance()) }
    bindSingleton<GigaGRPCChatApi> {
        GigaGRPCChatApi(instance(), instance())
    }
    bindSingleton<GigaRestChatAPI> {
        GigaRestChatAPI(instance(), instance(), instance(DiTags.TAG_LOG))
    }
    bindSingleton<GigaChatAPI> { instance<GigaRestChatAPI>() }
    bindSingleton { GigaVoiceAPI(instance(), instance()) }
    bindSingleton(tag = DiTags.TAG_API) { ApiClassifier(instance()) }
    bindSingleton(tag = DiTags.TAG_LOCAL) { LocalRegexClassifier }

    // LLM
    bindSingleton { NodesErrorHandling() }
    bindSingleton { NodesCommon(instance(), instance()) }
    bindSingleton { NodesLLM(instance(), instance(), instance()) }
    bindSingleton { NodesSummarization(instance(), instance()) }
    bindSingleton {
        NodesClassification(
            instance(),
            instance(DiTags.TAG_LOG),
            apiClassifier = instance(DiTags.TAG_API),
            localClassifier = instance(DiTags.TAG_LOCAL),
            instance(),
            instance(),
        )
    }
    bindSingleton { ToolsFactory(instance(), instance(), instance()) }
    bindSingleton { GraphBasedAgent(di, instance(DiTags.TAG_LOG)) }
}
