package ru.souz.tool.textReplace

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
}
