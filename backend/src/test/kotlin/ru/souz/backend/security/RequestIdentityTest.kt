package ru.souz.backend.security

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.jackson.jackson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import ru.souz.backend.http.BackendV1Error
import ru.souz.backend.http.BackendV1ErrorEnvelope
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.user.model.UserRecord
import ru.souz.backend.user.repository.UserRepository

class RequestIdentityTest {
    @Test
    fun `trusted proxy request provisions new user and does not duplicate existing user`() = testApplication {
        val userRepository = RecordingUserRepository()
        application {
            installRequestIdentityTestApp(userRepository)
        }

        val first = client.get("/v1/ping") {
            header("X-User-Id", "new-user")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }
        val second = client.get("/v1/ping") {
            header("X-User-Id", "new-user")
            header("X-Souz-Proxy-Auth", "proxy-secret")
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("new-user", first.bodyAsText())
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals("new-user", second.bodyAsText())
        assertEquals(2, userRepository.ensureCalls)
        assertEquals(1, userRepository.count())
    }

    @Test
    fun `invalid proxy token does not provision user`() = testApplication {
        val userRepository = RecordingUserRepository()
        application {
            installRequestIdentityTestApp(userRepository)
        }

        val response = client.get("/v1/ping") {
            header("X-User-Id", "new-user")
            header("X-Souz-Proxy-Auth", "wrong-secret")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, userRepository.ensureCalls)
        assertEquals(0, userRepository.count())
    }

    @Test
    fun `trusted user id shape rejects blank values`() {
        val error = assertFailsWith<BackendV1Exception> {
            validateTrustedUserIdShape("   ")
        }

        assertEquals(HttpStatusCode.Unauthorized, error.status)
        assertEquals("invalid_user_identity", error.code)
    }

    @Test
    fun `trusted user id shape rejects too long values`() {
        val error = assertFailsWith<BackendV1Exception> {
            validateTrustedUserIdShape("a".repeat(257))
        }

        assertEquals(HttpStatusCode.Unauthorized, error.status)
        assertEquals("invalid_user_identity", error.code)
    }

    @Test
    fun `trusted user id shape rejects control characters`() {
        val error = assertFailsWith<BackendV1Exception> {
            validateTrustedUserIdShape("user\u0007bell")
        }

        assertEquals(HttpStatusCode.Unauthorized, error.status)
        assertEquals("invalid_user_identity", error.code)
    }
}

private fun io.ktor.server.application.Application.installRequestIdentityTestApp(
    userRepository: UserRepository,
) {
    install(ContentNegotiation) {
        jackson()
    }
    install(io.ktor.server.plugins.statuspages.StatusPages) {
        exception<BackendV1Exception> { call, cause ->
            call.respond(
                cause.status,
                BackendV1ErrorEnvelope(
                    error = BackendV1Error(
                        code = cause.code,
                        message = cause.message,
                    )
                )
            )
        }
    }
    install(RequestIdentityPlugin) {
        trustedProxyToken = { "proxy-secret" }
        ensureUser = userRepository::ensureUser
    }
    routing {
        get("/v1/ping") {
            call.respondText(call.requestIdentity().userId)
        }
    }
}

private class RecordingUserRepository : UserRepository {
    private val users = linkedMapOf<String, UserRecord>()
    var ensureCalls: Int = 0
        private set

    override suspend fun ensureUser(userId: String): UserRecord {
        ensureCalls += 1
        return users.getOrPut(userId) {
            UserRecord(
                id = userId,
                createdAt = java.time.Instant.parse("2026-05-01T10:00:00Z"),
                lastSeenAt = java.time.Instant.parse("2026-05-01T10:00:00Z"),
            )
        }
    }

    fun count(): Int = users.size
}
