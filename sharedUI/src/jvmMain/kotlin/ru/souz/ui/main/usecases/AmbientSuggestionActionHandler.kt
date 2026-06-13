package ru.souz.ui.main.usecases

import ru.souz.ambient.AmbientSuggestionStore
import ru.souz.ambient.AmbientTaskCandidate
import kotlin.coroutines.cancellation.CancellationException

class AmbientSuggestionActionHandler(
    private val store: AmbientSuggestionStore,
    private val executor: suspend (AmbientTaskCandidate) -> Unit,
) {
    suspend fun accept(id: String): Boolean {
        val suggestion = store.consume(id) ?: return false
        return try {
            executor(suggestion.candidate)
            true
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            false
        }
    }

    fun reject(id: String) {
        store.dismiss(id)
    }

    fun dismiss(id: String) {
        store.dismiss(id)
    }
}
