package ru.souz.tool.textReplace

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** The OS clipboard is shared by every desktop agent and tool instance. */
internal object ClipboardTransactions {
    // This is a blocking JVM boundary. Reentrancy lets selection reads use GetClipboard.
    private val lock = ReentrantLock(true)

    fun <T> run(block: () -> T): T {
        lock.lockInterruptibly()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Keep ownership while an already-dispatched native operation can still use the clipboard. */
    fun settle(delayMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs)
        var interrupted = false
        try {
            var remaining = deadline - System.nanoTime()
            while (remaining > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(remaining)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
                remaining = deadline - System.nanoTime()
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
