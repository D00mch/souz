package ru.souz.backend.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import ru.souz.backend.config.BackendConfigSource

class PluginRuntimeConfigTest {
    @Test
    fun `defaults and overrides preserve explicit polling and lease timing`() {
        val config = PluginRuntimeConfig.load(source())
        assertEquals(60_000L, config.pollMillis)
        assertEquals(15_000L, config.jitterMillis)
        assertEquals(90_000L, config.leaseMillis)
        assertEquals(10_000L, config.renewMillis)
        assertEquals(5, config.userFailureThreshold)
        assertEquals(10, config.versionFailureThreshold)
        assertEquals(3, config.versionMinimumUsers)
        assertEquals(3, config.severeIncidentThreshold)
        val override = PluginRuntimeConfig.load(source(mapOf("POLL_MILLIS" to "90000", "JITTER_MILLIS" to "0",
            "USER_FAILURE_THRESHOLD" to "8", "UPLOAD_MILLIS" to "180000")))
        assertEquals(90_000L, override.pollMillis)
        assertEquals(0L, override.jitterMillis)
        assertEquals(8, override.userFailureThreshold)
        assertEquals(180_000L, override.uploadMillis)
        assertEquals(override, PluginRuntimeConfig.load(source(mapOf("POLL_MILLIS" to "90000", "JITTER_MILLIS" to "0",
            "USER_FAILURE_THRESHOLD" to "8", "UPLOAD_MILLIS" to "180000"), properties = true)))
    }

    @Test
    fun `invalid timing and breaker configurations fail at startup`() {
        for ((key, value) in listOf("CALL_MILLIS" to "0", "POLL_MILLIS" to "-1", "RENEW_MILLIS" to "90000",
            "JITTER_MILLIS" to "-1", "JITTER_MILLIS" to Long.MAX_VALUE.toString(), "UPLOAD_MILLIS" to "invalid",
            "VERSION_FAILURE_THRESHOLD" to "2", "VERSION_MINIMUM_USERS" to "0", "SEVERE_INCIDENT_THRESHOLD" to "0")) {
            assertFailsWith<IllegalArgumentException> { PluginRuntimeConfig.load(source(mapOf(key to value))) }
        }
    }

    @Test
    fun `artifact credentials are separately configured and never rendered by config or validation errors`() {
        assertNull(S3PluginArtifactConfig.load(source()))
        val config = assertNotNull(S3PluginArtifactConfig.load(source(s3)))
        assertEquals("plugin-artifacts", config.bucket)
        assertEquals("us-east-1", config.region)
        assertEquals(true, config.pathStyleAccess)
        assertFalse(config.toString().contains("secret-sentinel"))
        assertNotNull(S3PluginArtifactConfig.load(source(s3, properties = true)))
        for (replacement in listOf(
            "S3_ENDPOINT" to "http://secret-sentinel@localhost", "S3_ENDPOINT" to "http://localhost/path",
            "S3_ENDPOINT" to "http://localhost/?secret-sentinel", "S3_ENDPOINT" to "http://secret-sentinel spaces",
            "S3_BUCKET" to "bad/bucket", "S3_SECRET_KEY" to "", "S3_PATH_STYLE" to "maybe",
        )) {
            val error = assertFailsWith<IllegalArgumentException> { S3PluginArtifactConfig.load(source(s3 + replacement)) }
            assertFalse(error.stackTraceToString().contains("secret-sentinel"))
        }
    }

    private val s3 = mapOf("S3_ENDPOINT" to "http://localhost:9000", "S3_BUCKET" to "plugin-artifacts",
        "S3_ACCESS_KEY" to "plugin-admin", "S3_SECRET_KEY" to "secret-sentinel")

    private fun source(values: Map<String, String> = emptyMap(), properties: Boolean = false) = object : BackendConfigSource {
        override fun env(key: String): String? = if (properties) null else values[key.removePrefix("SOUZ_PLUGINS_")]
        override fun property(key: String): String? =
            if (properties) values[key.removePrefix("souz.plugins.").uppercase()] else null
    }
}
