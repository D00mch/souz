package ru.souz.backend.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BackendRuntimeResources(
    private val cancelAndJoinApplicationWork: suspend () -> Unit = {},
    private val closeProviderClients: () -> Unit = {},
    private val closeLocalRuntime: () -> Unit = {},
    private val closeDataSource: () -> Unit = {},
) : AutoCloseable {
    private val shutdownMutex = Mutex()
    private var shutdownCompletion: CompletableDeferred<Result<Unit>>? = null

    suspend fun shutdown() {
        val (completion, ownsShutdown) = shutdownMutex.withLock {
            val existing = shutdownCompletion
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<Result<Unit>>()
                    .also { shutdownCompletion = it } to true
            }
        }
        if (!ownsShutdown) {
            completion.await().getOrThrow()
            return
        }

        val result = withContext(NonCancellable) {
            closeInOrder().also(completion::complete)
        }
        result.getOrThrow()
    }

    override fun close() {
        runBlocking { shutdown() }
    }

    private suspend fun closeInOrder(): Result<Unit> {
        var failure: Throwable? = null

        suspend fun runStep(step: suspend () -> Unit) {
            try {
                step()
            } catch (stepFailure: Throwable) {
                if (failure == null) {
                    failure = stepFailure
                } else {
                    failure.addSuppressed(stepFailure)
                }
            }
        }

        runStep(cancelAndJoinApplicationWork)
        runStep { closeProviderClients() }
        runStep { closeLocalRuntime() }
        runStep { closeDataSource() }

        return failure?.let(Result.Companion::failure) ?: Result.success(Unit)
    }
}
