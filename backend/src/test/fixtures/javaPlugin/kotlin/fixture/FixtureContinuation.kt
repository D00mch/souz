@file:JvmName("FixtureContinuation")

package fixture

import kotlin.coroutines.Continuation

fun <T, R> mapResult(continuation: Continuation<R>, transform: (T) -> R): Continuation<T> =
    Continuation(continuation.context) { continuation.resumeWith(it.mapCatching(transform)) }
