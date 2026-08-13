package ru.souz.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OrderedShutdown(
    private val steps: List<ShutdownStep>,
    private val beforeShutdown: suspend () -> Unit = {},
    private val onStepFailure: (ShutdownStep, Throwable) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private var completion: CompletableDeferred<Result<Unit>>? = null

    suspend fun shutdown() {
        val (currentCompletion, ownsShutdown) = mutex.withLock {
            completion?.let { it to false }
                ?: CompletableDeferred<Result<Unit>>()
                    .also { completion = it } to true
        }
        if (!ownsShutdown) {
            currentCompletion.await().getOrThrow()
            return
        }

        val result = withContext(NonCancellable) {
            beforeShutdown()
            closeInOrder().also(currentCompletion::complete)
        }
        result.getOrThrow()
    }

    private suspend fun closeInOrder(): Result<Unit> {
        var failure: Throwable? = null
        steps.forEach { step ->
            try {
                step.action()
            } catch (stepFailure: Throwable) {
                onStepFailure(step, stepFailure)
                failure = failure?.also { it.addSuppressed(stepFailure) } ?: stepFailure
            }
        }
        return failure?.let(Result.Companion::failure) ?: Result.success(Unit)
    }
}

class ShutdownStep(
    val name: String,
    val action: suspend () -> Unit,
)

fun shutdownStep(
    name: String,
    action: suspend () -> Unit,
): ShutdownStep = ShutdownStep(name, action)
