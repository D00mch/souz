package ru.souz.llms.tunnel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal const val AI_TUNNEL_MAX_ATTEMPTS = 3

internal suspend fun <T> retryAiTunnelRequest(
    isError: (T) -> Boolean = { false },
    request: suspend () -> T,
): T {
    repeat(AI_TUNNEL_MAX_ATTEMPTS) { attempt ->
        try {
            val result = request()
            if (!isError(result) || attempt == AI_TUNNEL_MAX_ATTEMPTS - 1) return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (attempt == AI_TUNNEL_MAX_ATTEMPTS - 1) throw failure
        }
        delayAiTunnelRetry(attempt)
    }
    error("Unreachable")
}

internal suspend fun delayAiTunnelRetry(attempt: Int) {
    delay(250L * (attempt + 1))
}
