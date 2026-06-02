package ru.souz.ui.main.usecases

import ru.souz.ambient.AmbientSuggestionStore
import ru.souz.ambient.AmbientTaskCandidate
import kotlin.coroutines.cancellation.CancellationException

class AmbientSuggestionActionHandler(
    private val store: AmbientSuggestionStore,
    private val executor: suspend (AmbientTaskCandidate) -> Unit,
) {
    suspend fun accept(id: String): Boolean {
        val suggestion = store.accept(id) ?: return false
        store.markExecuting(id)
        return try {
            executor(suggestion.candidate)
            store.markCompleted(id)
            true
        } catch (error: Throwable) {
            store.markFailed(id, error.message ?: error::class.simpleName)
            if (error is CancellationException) throw error
            false
        }
    }

    fun reject(id: String) {
        store.reject(id)
    }

    fun dismiss(id: String) {
        store.reject(id)
    }
}
