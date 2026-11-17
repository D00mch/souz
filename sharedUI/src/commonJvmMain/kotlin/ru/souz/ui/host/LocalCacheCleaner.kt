package ru.souz.ui.host

/** Clears local diagnostic logs and saved graph sessions, preserving other application state. */
fun interface LocalCacheCleaner {
    suspend fun clear()
}

object NoopLocalCacheCleaner : LocalCacheCleaner {
    override suspend fun clear() {
        throw UnsupportedOperationException("Local cache cleanup is unavailable on this host.")
    }
}
