package ru.souz.android.assistant

import android.service.voice.VoiceInteractionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.android.awaitSouzAgentRuntime

/**
 * Keeps the Souz runtime resident so an assist key press does not pay cold-start cost.
 */
class SouzVoiceInteractionService : VoiceInteractionService() {
    private val l = LoggerFactory.getLogger(SouzVoiceInteractionService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onReady() {
        super.onReady()
        scope.launch {
            val startedAt = System.currentTimeMillis()
            runCatching { awaitSouzAgentRuntime() }
                .onSuccess { l.info("Agent runtime warmed up in {} ms", System.currentTimeMillis() - startedAt) }
                .onFailure { l.error("Agent runtime warm-up failed", it) }
        }
    }

    override fun onShutdown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onShutdown()
    }
}
