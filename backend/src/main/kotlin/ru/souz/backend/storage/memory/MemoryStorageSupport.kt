package ru.souz.backend.storage.memory

import java.util.LinkedHashMap

internal const val DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES: Int = 10_000

internal fun <K, V> boundedLruMap(
    maxEntries: Int = DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES,
    onEvict: (K, V) -> Unit = { _, _ -> },
): LinkedHashMap<K, V> {
    require(maxEntries > 0) { "maxEntries must be positive." }
    return object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            val shouldRemove = size > maxEntries
            if (shouldRemove) {
                onEvict(eldest.key, eldest.value)
            }
            return shouldRemove
        }
    }
}
