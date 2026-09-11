package ru.souz.backend.common

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext

/** Fresh context for a connection, subscription, or independently launched execution. */
internal fun backendLogContext(vararg fields: Pair<String, Any?>): MDCContext = MDCContext(
    fields.mapNotNull { (key, value) ->
        value?.let {
            key to it.toString().take(128).replace(logControlCharacters, "_")
        }
    }.toMap(),
)

/** Enrich the current scope; null removes a field that no longer applies. */
internal suspend fun <T> withBackendLogContext(
    vararg fields: Pair<String, Any?>,
    block: suspend () -> T,
): T {
    val inherited = currentCoroutineContext()[MDCContext]?.contextMap.orEmpty()
    return withContext(backendLogContext(*(inherited + fields).toList().toTypedArray())) { block() }
}

private val logControlCharacters = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
