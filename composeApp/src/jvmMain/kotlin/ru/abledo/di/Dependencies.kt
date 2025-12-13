package ru.abledo.di

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.abledo.agent.GraphBasedAgent
import ru.abledo.agent.node.NodesLLM
import ru.abledo.agent.nodes.NodesClassification
import ru.abledo.db.ConfigStore
import ru.abledo.db.DesktopInfoRepository
import ru.abledo.db.SettingsProvider
import ru.abledo.db.VectorDB
import ru.abledo.giga.ApiClassifier
import ru.abledo.giga.GigaAuth
import ru.abledo.giga.GigaChatAPI
import ru.abledo.giga.GigaModel
import ru.abledo.giga.GigaRestChatAPI
import ru.abledo.giga.GigaVoiceAPI
import ru.abledo.tool.LocalRegexClassifier
import ru.abledo.tool.ToolsFactory
import ru.abledo.tool.ToolsSettings

private object DiTags {
    const val MODULE_MAIN = "main"

    const val TAG_LOG = "log"
    const val TAG_API = "api"
    const val TAG_LOCAL = "local"

    const val ENV_GIGA_MODEL = "GIGA_MODEL"
}

val mainDiModule = DI.Module(DiTags.MODULE_MAIN) {
    // utils
    bindSingleton(tag = DiTags.TAG_LOG) {
        jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
    }

    // DB
    bindSingleton { ConfigStore }
    bindSingleton { VectorDB }
    bindSingleton { SettingsProvider(instance()) }
    bindSingleton { DesktopInfoRepository(instance(), instance()) }
    bindSingleton { ToolsSettings(instance(), instance()) }

    // API
    bindSingleton<GigaModel> {
        System.getenv(DiTags.ENV_GIGA_MODEL)?.let { envModel ->
            GigaModel.entries.firstOrNull { enumModel ->
                enumModel.name.equals(envModel, ignoreCase = true) ||
                        enumModel.alias.equals(envModel, ignoreCase = true)
            }
        } ?: GigaModel.Max
    }
    bindSingleton { GigaAuth }
    bindSingleton { GigaRestChatAPI(instance(), instance()) }
    bindSingleton { GigaVoiceAPI(instance(), instance()) }
    bindSingleton(tag = DiTags.TAG_API) { ApiClassifier(instance()) }
    bindSingleton(tag = DiTags.TAG_LOCAL) { LocalRegexClassifier }

    // LLM
    bindSingleton { NodesLLM(instance()) }
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
    bindSingleton { ToolsFactory(instance()) }
    bindSingleton { GraphBasedAgent(di, instance(), instance(DiTags.TAG_LOG)) }
}
