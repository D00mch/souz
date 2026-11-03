package ru.souz.backend.plugins

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.e2e.BackendE2eScope
import ru.souz.backend.e2e.E2E_PROXY_TOKEN
import ru.souz.backend.e2e.backendE2eTest
import ru.souz.backend.http.BackendHttpRoutes

private const val ADMIN_PATH = "/admin/v1/plugins"
private const val ADMIN_TOKEN = "plugin-operator-secret"

class PluginAdministrationE2eTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `administration is disabled without its token and rejects user credentials before consuming upload bodies`() {
        backendE2eTest("plugin_admin_disabled") {
            assertEquals(HttpStatusCode.NotFound, client.get(ADMIN_PATH).status)
            assertEquals(HttpStatusCode.NotFound, stalledUpload("Bearer $ADMIN_TOKEN").status)
        }
        adminTest {
            assertEquals(HttpStatusCode.Unauthorized, stalledUpload(null).status)
            assertEquals(HttpStatusCode.Unauthorized, stalledUpload("Bearer $E2E_PROXY_TOKEN").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("$ADMIN_PATH/install") {
                header("X-Souz-Proxy-Auth", E2E_PROXY_TOKEN)
                header("X-User-Id", "operator")
                contentType(ContentType.Text.Plain)
                setBody("untrusted non-multipart data")
            }.status)
            assertEquals(HttpStatusCode.OK, client.get(ADMIN_PATH) { admin() }.status)
        }
    }

    @Test
    fun `generated Swagger presents a required binary upload and dedicated operator security for every operation`() = adminTest {
        val docs = client.get(BackendHttpRoutes.DOCS).bodyAsText()
        assertTrue(docs.contains("/docs/openapi.json"))
        val document = client.get(BackendHttpRoutes.OPENAPI_DOCUMENT).jsonBody()
        val security = document["components"]["securitySchemes"]["pluginAdminBearer"]
        assertEquals("http", security["type"].asText())
        assertEquals("bearer", security["scheme"].asText())
        val operations = document["paths"].properties().filter { it.key.startsWith(ADMIN_PATH) }
        assertEquals(7, operations.size)
        operations.forEach { (path, methods) ->
            methods.properties().forEach { (_, operation) ->
                assertEquals(setOf("pluginAdminBearer"), operation["security"][0].fieldNames().asSequence().toSet(), path)
                assertTrue(operation["responses"].has("401"), path)
            }
        }
        val upload = document["paths"]["$ADMIN_PATH/install"]["post"]["requestBody"]
        assertTrue(upload["required"].asBoolean())
        val schema = upload["content"]["multipart/form-data"]["schema"]
        assertEquals(setOf("jar", "expectedRevision"), schema["required"].map { it.asText() }.toSet())
        assertEquals("string", schema["properties"]["jar"]["type"].asText())
        assertEquals("binary", schema["properties"]["jar"]["format"].asText())
        assertEquals("integer", schema["properties"]["expectedRevision"]["type"].asText())
    }

    @Test
    fun `JAR installation acknowledges desired state before rollout and preserves it through rejected uploads`() = adminTest {
        val first = PluginArtifact.inspect(pluginFixture("kotlin-v1")).binding
        val second = PluginArtifact.inspect(pluginFixture("kotlin-v2")).binding
        val accepted = upload(pluginFixture("kotlin-v1"), 0)
        assertEquals(HttpStatusCode.Accepted, accepted.status, accepted.bodyAsText())
        val body = accepted.jsonBody()
        assertEquals(first.id, body["id"].asText())
        assertEquals(first.digest, body["digest"].asText())
        assertEquals(1L, body["revision"].asLong())
        var status = client.get("$ADMIN_PATH/${first.id}") { admin() }.jsonBody()
        assertEquals(backend.pluginRuntime.replicaId, status["replicaId"].asText())
        assertEquals(first.digest, status["plugins"][0]["desired"]["defaultDigest"].asText())
        assertTrue(status["plugins"][0]["applied"].isNull)
        backend.pluginRuntime.reconcile()
        eventually("first plugin activated") { backend.pluginRuntime.snapshot.defaultBindings.singleOrNull()?.takeIf { it == first } }
        assertEquals(HttpStatusCode.Accepted, upload(pluginFixture("kotlin-v2"), 1).status)
        status = client.get("$ADMIN_PATH/${first.id}") { admin() }.jsonBody()
        assertEquals(second.digest, status["plugins"][0]["desired"]["defaultDigest"].asText())
        assertEquals(first.digest, status["plugins"][0]["applied"]["appliedDigest"].asText())
        val immutableConflict = repackagePlugin(pluginFixture("kotlin-v1"), directory.resolve("conflict.jar"),
            resources = mapOf("extra.txt" to "changed bytes".toByteArray()))
        assertEquals(HttpStatusCode.Conflict, upload(immutableConflict, 2).status)
        assertEquals(HttpStatusCode.Conflict, upload(pluginFixture("kotlin-v1"), 0).status)
        val invalid = directory.resolve("invalid.jar").also { Files.writeString(it, "not a JAR") }
        assertEquals(HttpStatusCode.BadRequest, upload(invalid, 2).status)
        assertEquals(2L, backend.pluginRepository.registry().single().revision)
        assertEquals(second.digest, backend.pluginRepository.registry().single().defaultDigest)
        assertEquals(HttpStatusCode.NotFound, client.get("$ADMIN_PATH/missing") { admin() }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("$ADMIN_PATH/install") {
            admin()
            setBody(MultiPartFormDataContent(formData { append("expectedRevision", "2") }))
        }.status)
    }

    @Test
    fun `lifecycle mutations compare revisions and require explicit quarantine reset`() = adminTest {
        val first = PluginArtifact.inspect(pluginFixture("kotlin-v1")).binding
        val second = PluginArtifact.inspect(pluginFixture("kotlin-v2")).binding
        assertEquals(HttpStatusCode.Accepted, upload(pluginFixture("kotlin-v1"), 0).status)
        assertEquals(HttpStatusCode.Accepted, upload(pluginFixture("kotlin-v2"), 1).status)
        val retired = mutate("/${first.id}/versions/${second.version}/retire", 2)
        assertEquals(HttpStatusCode.Accepted, retired.status)
        assertTrue(retired.jsonBody()["digest"].isNull)
        assertEquals(HttpStatusCode.Conflict, mutate("/${first.id}/disable", 2).status)
        assertEquals(HttpStatusCode.Accepted, select(first, 3).status)
        assertEquals(HttpStatusCode.Accepted, mutate("/${first.id}/disable", 4).status)
        assertFalse(backend.pluginRepository.registry().single().enabled)
        assertEquals(HttpStatusCode.Accepted, select(second, 5).status)
        assertFalse(backend.pluginRepository.version(second)!!.retired)
        repeat(3) { backend.pluginRepository.recordSevereIncident(second, "admin-severe-$it", 3) }
        assertEquals(HttpStatusCode.Conflict, select(second, 9).status)
        assertEquals(HttpStatusCode.Accepted, mutate("/${first.id}/versions/${second.version}/reenable", 9).status)
        assertFalse(backend.pluginRepository.version(second)!!.quarantined)
        assertEquals(0, backend.pluginRepository.version(second)!!.severeIncidents)
        assertEquals(HttpStatusCode.Accepted, select(first, 10).status)
    }

    @Test
    fun `unavailable artifact storage returns a structured error without replacing the selected version`() {
        val config = pluginArtifactConfig()
        backendE2eTest("plugin_admin_storage", configureApp = { it.copy(pluginArtifacts = config, pluginAdminToken = ADMIN_TOKEN) }) {
            assertEquals(HttpStatusCode.Accepted, upload(pluginFixture("kotlin-v1"), 0).status)
            val before = backend.pluginRepository.registry().single()
            val disabled = AttachmentMinio.server.execInContainer("mc", "admin", "user", "disable", "root", config.accessKey)
            assertEquals(0, disabled.exitCode)
            try {
                val failure = upload(pluginFixture("kotlin-v2"), 1)
                assertEquals(HttpStatusCode.ServiceUnavailable, failure.status)
                assertEquals("plugin_storage_unavailable", failure.jsonBody()["error"]["code"].asText())
                assertEquals(before, backend.pluginRepository.registry().single())
            } finally {
                assertEquals(0, AttachmentMinio.server.execInContainer("mc", "admin", "user", "enable", "root", config.accessKey).exitCode)
            }
        }
    }

    @Test
    fun `chunked multipart uploads enforce the same archive limit and reject interrupted input`() = adminTest {
        val first = PluginArtifact.inspect(pluginFixture("kotlin-v1")).binding
        assertEquals(HttpStatusCode.Accepted, streamedUpload {
            startJar(0)
            writeFully(Files.readAllBytes(pluginFixture("kotlin-v1")))
            writeStringUtf8("\r\n--plugin-test--\r\n")
        }.status)
        val selected = backend.pluginRepository.registry().single()
        assertEquals(first.digest, selected.defaultDigest)
        assertEquals(HttpStatusCode.PayloadTooLarge, streamedUpload(contentLength = PluginArtifact.MAX_BYTES.toLong() + 65_537) {
            awaitCancellation()
        }.status)
        val large = streamedUpload {
            startJar(1)
            val buffer = ByteArray(64 * 1024)
            repeat(1024) { writeFully(buffer) }
            writeFully(byteArrayOf(0))
            writeStringUtf8("\r\n--plugin-test--\r\n")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, large.status, large.bodyAsText())
        assertEquals("plugin_upload_too_large", large.jsonBody()["error"]["code"].asText())
        val interrupted = streamedUpload {
            startJar(1)
            writeStringUtf8("incomplete artifact with no final boundary")
        }
        assertEquals(HttpStatusCode.BadRequest, interrupted.status, interrupted.bodyAsText())
        assertEquals(selected, backend.pluginRepository.registry().single())
    }

    @Test
    fun `upload deadline rejects a stalled authenticated body without registry changes`() = adminTest(PluginRuntimeConfig(uploadMillis = 150)) {
        val response = streamedUpload {
            startJar(0)
            writeStringUtf8("partial")
            awaitCancellation()
        }
        assertEquals(HttpStatusCode.RequestTimeout, response.status, response.bodyAsText())
        assertEquals("plugin_upload_timeout", response.jsonBody()["error"]["code"].asText())
        assertTrue(backend.pluginRepository.registry().isEmpty())
    }

    private fun adminTest(timing: PluginRuntimeConfig = PluginRuntimeConfig(), block: suspend BackendE2eScope.() -> Unit) {
        val artifacts = pluginArtifactConfig()
        backendE2eTest("plugin_admin", configureApp = {
            it.copy(pluginArtifacts = artifacts, pluginAdminToken = ADMIN_TOKEN, plugins = timing)
        }, block = block)
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.admin() { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }

private suspend fun BackendE2eScope.upload(path: Path, revision: Long): HttpResponse = client.post("$ADMIN_PATH/install") {
    admin()
    setBody(MultiPartFormDataContent(formData {
        append("expectedRevision", revision.toString())
        append("jar", Files.readAllBytes(path), Headers.build {
            append(HttpHeaders.ContentType, "application/java-archive")
            append(HttpHeaders.ContentDisposition, "filename=\"untrusted-upload-name.jar\"")
        })
    }))
}

private suspend fun BackendE2eScope.mutate(path: String, revision: Long): HttpResponse = client.post("$ADMIN_PATH$path") {
    admin(); contentType(ContentType.Application.Json); setBody("""{"expectedRevision":$revision}""")
}

private suspend fun BackendE2eScope.select(binding: PluginBinding, revision: Long): HttpResponse = client.put("$ADMIN_PATH/${binding.id}/selection") {
    admin(); contentType(ContentType.Application.Json); setBody("""{"version":"${binding.version}","expectedRevision":$revision}""")
}

private suspend fun BackendE2eScope.stalledUpload(authorization: String?): HttpResponse {
    val body = ByteChannel(autoFlush = true)
    return try {
        withTimeout(5_000) { client.post("$ADMIN_PATH/install") {
            authorization?.let { header(HttpHeaders.Authorization, it) }
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", "stalled")
                override fun readFrom() = body
            })
        } }
    } finally { body.cancel(null) }
}

private suspend fun BackendE2eScope.streamedUpload(
    contentLength: Long? = null,
    producer: suspend ByteWriteChannel.() -> Unit,
): HttpResponse = coroutineScope {
    val body = ByteChannel(autoFlush = true)
    val writer = launch {
        try { body.producer(); body.flushAndClose() }
        catch (_: IOException) { /* A bounded receiver may close the request before the producer finishes. */ }
    }
    try {
        withTimeout(30_000) { client.post("$ADMIN_PATH/install") {
            admin()
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType = ContentType.MultiPart.FormData.withParameter("boundary", "plugin-test")
                override val contentLength = contentLength
                override fun readFrom() = body
            })
        } }
    } finally {
        body.cancel(null)
        writer.cancelAndJoin()
    }
}

private suspend fun ByteWriteChannel.startJar(revision: Long) = writeStringUtf8(
    "--plugin-test\r\nContent-Disposition: form-data; name=\"expectedRevision\"\r\n\r\n$revision\r\n" +
        "--plugin-test\r\nContent-Disposition: form-data; name=\"jar\"; filename=\"untrusted.jar\"\r\n" +
        "Content-Type: application/java-archive\r\n\r\n",
)
