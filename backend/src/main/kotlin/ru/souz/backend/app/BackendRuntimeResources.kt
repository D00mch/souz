package ru.souz.backend.app

class BackendRuntimeResources(
    private val closeables: List<AutoCloseable> = emptyList(),
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true

        var firstFailure: Throwable? = null
        closeables.forEach { closeable ->
            try {
                closeable.close()
            } catch (closeFailure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = closeFailure
                } else if (closeFailure !== firstFailure) {
                    firstFailure.addSuppressed(closeFailure)
                }
            }
        }
        firstFailure?.let { throw it }
    }
}
