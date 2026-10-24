package ru.souz.runtime

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
    private var result: Result<Unit>? = null

    suspend fun shutdown() {
        val shutdownResult = mutex.withLock {
            result ?: withContext(NonCancellable) {
                closeInOrder().also { result = it }
            }
        }
        shutdownResult.getOrThrow()
    }

    private suspend fun closeInOrder(): Result<Unit> {
        val failures = buildList {
            runCatching { beforeShutdown() }.exceptionOrNull()?.let(::add)
            steps.forEach { step ->
                runCatching { step.action() }.exceptionOrNull()?.let { stepFailure ->
                    add(stepFailure)
                    runCatching { onStepFailure(step, stepFailure) }.exceptionOrNull()?.let(::add)
                }
            }
        }
        return failures.toShutdownResult()
    }

    private fun List<Throwable>.toShutdownResult(): Result<Unit> {
        val first = firstOrNull() ?: return Result.success(Unit)
        drop(1).forEach { failure ->
            if (failure !== first) {
                first.addSuppressed(failure)
            }
        }
        return Result.failure(first)
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
