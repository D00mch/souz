package ru.souz.backend

import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.db.SettingsProvider
import ru.souz.llms.local.LocalLlamaRuntime

class BackendRuntime private constructor(
    private val di: DI,
) : AutoCloseable {
    val chatService: ChatService by lazy { di.direct.instance() }
    val agentService: BackendAgentService by lazy { di.direct.instance() }
    private val settingsProvider: SettingsProvider by lazy { di.direct.instance() }
    private val localRuntime: LocalLlamaRuntime by lazy { di.direct.instance() }

    fun selectedModel(): String = settingsProvider.gigaModel.alias

    override fun close() {
        localRuntime.close()
    }

    companion object {
        fun create(): BackendRuntime {
            val di = DI {
                import(backendDiModule(systemPrompt = backendSystemPrompt()))
            }
            return BackendRuntime(di = di)
        }

        private fun backendSystemPrompt(): String =
            System.getenv("SOUZ_BACKEND_SYSTEM_PROMPT")
                ?: System.getProperty("souz.backend.systemPrompt")
                ?: "You are Souz AI backend assistant. Answer directly and concisely in the user's language."
    }
}
