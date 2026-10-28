package ru.souz.backend.app

import ru.souz.backend.plugins.PluginRegistry
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import kotlinx.coroutines.runBlocking
import ru.souz.backend.attachments.AttachmentCleanupWorker
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.telegram.TelegramBotPollingService
import ru.souz.backend.vk.VkBotPollingService
import ru.souz.backend.client.ClientThreadRecoveryService
import ru.souz.backend.memory.hindsight.HistoryMemoryWorker
import ru.souz.backend.scheduler.ScheduledTaskRunner
import ru.souz.jobs.impl.PostgresJobService

/** Process-wide backend runtime container with shared services and LLM resources. */
class BackendRuntime private constructor(
    private val di: DI,
) : AutoCloseable {
    internal val httpDependencies: BackendHttpDependencies by lazy { di.direct.instance() }
    private val telegramBotPollingService: TelegramBotPollingService? by lazy {
        if (httpDependencies.featureFlags.telegramBot) di.direct.instance() else null
    }
    private val vkBotPollingService: VkBotPollingService? by lazy {
        if (httpDependencies.featureFlags.vkBot) di.direct.instance() else null
    }
    private val resources: BackendRuntimeResources by lazy { di.direct.instance() }
    private val applicationScope: BackendApplicationScope by lazy { di.direct.instance() }
    private val clientThreadRecoveryService: ClientThreadRecoveryService by lazy { di.direct.instance() }

    fun startBackgroundServices() {
        runBlocking { di.direct.instance<PluginRegistry>().start(applicationScope) }
        runBlocking { httpDependencies.hookService.start(applicationScope) }
        // The worker keeps its first handler, so this is the only job dispatch path.
        runBlocking {
            di.direct.instance<PostgresJobService>().startWorker(applicationScope, di.direct.instance<ScheduledTaskRunner>()::run)
        }
        if (httpDependencies.featureFlags.wsEvents) {
            runBlocking { clientThreadRecoveryService.recover() }
            clientThreadRecoveryService.start(applicationScope)
        }
        telegramBotPollingService?.start()
        vkBotPollingService?.start()
        di.direct.instanceOrNull<HistoryMemoryWorker>()?.start(applicationScope)
        di.direct.instanceOrNull<AttachmentCleanupWorker>()?.start(applicationScope)
    }

    override fun close() {
        runBlocking { shutdown() }
    }

    suspend fun shutdown() {
        resources.shutdown()
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
