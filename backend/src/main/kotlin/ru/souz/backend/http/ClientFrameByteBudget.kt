package ru.souz.backend.http

/** Non-suspending JVM ingress boundary: never queue a frame while waiting for heap capacity. */
internal class ClientFrameByteBudget(private val capacity: Long = 192L * 1024 * 1024) {
    private var retained = 0L

    @Synchronized
    fun tryReserve(frameBytes: Int): AutoCloseable? {
        require(frameBytes >= 0)
        // Account conservatively for the wire bytes, UTF-16 parsing/string copies and decoded bytes.
        // Raster allocation is separately bounded by ImageSource's shared decoder permits.
        val weight = maxOf(4096L, frameBytes.toLong() * 6)
        if (weight > capacity - retained) return null
        retained += weight
        var released = false
        return AutoCloseable {
            synchronized(this) {
                if (!released) {
                    released = true
                    retained -= weight
                }
            }
        }
    }
}

// Shared by both public socket endpoints and all application instances in this process.
internal val publicClientFrameBytes = ClientFrameByteBudget()
