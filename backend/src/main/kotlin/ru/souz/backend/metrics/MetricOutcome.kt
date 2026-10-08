package ru.souz.backend.metrics

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

internal fun Throwable.metricOutcome(): String = when (this) {
    is TimeoutCancellationException, is HttpRequestTimeoutException,
    is ConnectTimeoutException, is SocketTimeoutException,
    is java.net.SocketTimeoutException, is java.net.http.HttpTimeoutException -> "timeout"
    is CancellationException -> "cancelled"
    else -> "error"
}

internal fun responseMetricOutcome(status: Int?): String = when (status) {
    null -> "success"
    408, 504 -> "timeout"
    else -> "error"
}
