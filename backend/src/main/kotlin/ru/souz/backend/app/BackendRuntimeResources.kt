package ru.souz.backend.app

class BackendRuntimeResources(
    private val closeables: List<AutoCloseable> = emptyList(),
) : AutoCloseable {
    override fun close() {
        closeables.forEach { closeable ->
            runCatching { closeable.close() }
        }
    }
}
