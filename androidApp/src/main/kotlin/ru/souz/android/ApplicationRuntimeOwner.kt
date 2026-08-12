package ru.souz.android

/** Keeps one application-lifetime runtime across Activity recreation. */
internal class ApplicationRuntimeOwner<T : AutoCloseable>(
    createRuntime: () -> T,
) : AutoCloseable {
    private val lazyRuntime = lazy(createRuntime)
    private var closed = false

    val runtime: T
        get() {
            check(!closed) { "Application runtime is closed." }
            return lazyRuntime.value
        }

    override fun close() {
        if (closed) return
        closed = true
        if (lazyRuntime.isInitialized()) {
            lazyRuntime.value.close()
        }
    }
}
