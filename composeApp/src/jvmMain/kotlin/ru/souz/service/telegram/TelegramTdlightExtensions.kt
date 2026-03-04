package ru.souz.service.telegram

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T> CompletableFuture<T>.awaitResult(): T {
    return suspendCancellableCoroutine { continuation ->
        whenComplete { value, throwable ->
            if (throwable != null) {
                continuation.resumeWithException(throwable)
            } else {
                continuation.resume(value)
            }
        }

        continuation.invokeOnCancellation {
            cancel(true)
        }
    }
}
