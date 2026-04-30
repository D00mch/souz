package ru.souz.backend.app

import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.backend.agent.service.BackendAgentService
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.db.SettingsProvider
import ru.souz.llms.local.LocalLlamaRuntime

/** Process-wide backend runtime container with shared services and LLM resources. */
class BackendRuntime private constructor(
    private val di: DI,
) : AutoCloseable {
    val agentService: BackendAgentService by lazy { di.direct.instance() }
    val bootstrapService: BackendBootstrapService by lazy { di.direct.instance() }
    private val settingsProvider: SettingsProvider by lazy { di.direct.instance() }
    private val localRuntime: LocalLlamaRuntime by lazy { di.direct.instance() }

    fun selectedModel(): String = settingsProvider.gigaModel.alias

    override fun close() {
        localRuntime.close()
    }

    companion object {
        fun create(
            appConfig: BackendAppConfig = BackendAppConfig.load().validate(),
        ): BackendRuntime {
            val di = DI {
                import(
                    backendDiModule(
                        systemPrompt = backendSystemPrompt(),
                        appConfig = appConfig,
                    )
                )
            }
            return BackendRuntime(di = di)
        }

        private fun backendSystemPrompt(): String =
            System.getenv("SOUZ_BACKEND_SYSTEM_PROMPT")
                ?: System.getProperty("souz.backend.systemPrompt")
                ?: "You are Souz AI backend assistant. Answer directly and concisely in the user's language."
    }
}
