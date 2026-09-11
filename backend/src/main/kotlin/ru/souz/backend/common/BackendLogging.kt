package ru.souz.backend.common

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext

/** Fresh context for a connection, subscription, or independently launched execution. */
internal fun backendLogContext(vararg fields: Pair<String, Any?>): MDCContext =
    MDCContext(sanitizedMdcMap(fields))

/** Enrich the current scope; null removes a field that no longer applies. */
internal suspend fun <T> withBackendLogContext(
    vararg fields: Pair<String, Any?>,
    block: suspend () -> T,
): T {
    val inherited = currentCoroutineContext()[MDCContext]?.contextMap
    val map = if (inherited.isNullOrEmpty()) {
        sanitizedMdcMap(fields)
    } else {
        buildMap(inherited.size + fields.size) {
            putAll(inherited)
            putSanitized(fields)
        }
    }
    return withContext(MDCContext(map)) { block() }
}

private fun sanitizedMdcMap(fields: Array<out Pair<String, Any?>>): Map<String, String> =
    buildMap(fields.size) { putSanitized(fields) }

private fun MutableMap<String, String>.putSanitized(fields: Array<out Pair<String, Any?>>) {
    for ((key, value) in fields) {
        if (value == null) {
            remove(key)
        } else {
            put(key, value.toString().take(128).replace(logControlCharacters, "_"))
        }
    }
}

private val logControlCharacters = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
