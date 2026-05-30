package ru.souz.backend.common

/** Backend exception carrying the HTTP status code to return. */
class BackendRequestException(
    val statusCode: Int,
    override val message: String,
) : RuntimeException(message)
