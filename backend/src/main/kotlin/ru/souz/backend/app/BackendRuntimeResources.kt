package ru.souz.backend.app

import java.util.concurrent.atomic.AtomicBoolean

class BackendRuntimeResources(
    private val closeables: List<AutoCloseable> = emptyList(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        closeables.forEach { closeable ->
            try {
                closeable.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) {
                    failure = closeFailure
                } else {
                    failure.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let { throw it }
    }
}
