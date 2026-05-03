package ru.souz.backend.common

internal fun normalizePositiveLimit(limit: Int, max: Int): Int {
    require(limit > 0) { "limit must be positive." }
    return limit.coerceAtMost(max)
}
