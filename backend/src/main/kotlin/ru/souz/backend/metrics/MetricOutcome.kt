package ru.souz.backend.metrics

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

internal fun Throwable.metricOutcome(): String = when (this) {
    is TimeoutCancellationException, is HttpRequestTimeoutException,
    is ConnectTimeoutException, is SocketTimeoutException -> "timeout"
    is CancellationException -> "cancelled"
    else -> "error"
}

internal fun responseMetricOutcome(errorStatus: Int?): String = when (errorStatus) {
    null -> "success"
    408, 504 -> "timeout"
    else -> "error"
}
