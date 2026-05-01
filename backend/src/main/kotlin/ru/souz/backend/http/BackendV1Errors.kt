package ru.souz.backend.http

import io.ktor.http.HttpStatusCode

data class BackendV1ErrorEnvelope(
    val error: BackendV1Error,
)

data class BackendV1Error(
    val code: String,
    val message: String,
)

class BackendV1Exception(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

fun invalidV1Request(message: String): BackendV1Exception =
    BackendV1Exception(
        status = HttpStatusCode.BadRequest,
        code = "invalid_request",
        message = message,
    )

fun featureDisabledV1(message: String): BackendV1Exception =
    BackendV1Exception(
        status = HttpStatusCode.NotFound,
        code = "feature_disabled",
        message = message,
    )
