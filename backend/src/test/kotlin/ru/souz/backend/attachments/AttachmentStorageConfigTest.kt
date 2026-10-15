package ru.souz.backend.attachments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import ru.souz.backend.config.BackendConfigSource

class AttachmentStorageConfigTest {
    @Test
    fun `storage is absent without endpoint and configured through environment or properties`() {
        assertNull(AttachmentStorageConfig.load(source(emptyMap())))
        for (properties in listOf(false, true)) {
            val config = assertNotNull(AttachmentStorageConfig.load(source(settings, properties)))
            assertEquals("http://localhost:9000", config.endpoint)
            assertEquals(20_000_000, config.maxFileBytes)
            assertEquals(true, config.pathStyleAccess)
            assertEquals(16, config.maxConcurrentUploads)
            assertFalse(config.toString().contains("test-secret"))
        }
    }

    @Test
    fun `invalid settings fail without leaking credentials or invalid values`() {
        for ((key, value) in listOf(
            "S3_ENDPOINT" to "http://user:test-secret@localhost:9000",
            "S3_ENDPOINT" to "http://localhost/test-secret with spaces",
            "S3_SECRET_KEY" to "",
            "MAX_FILE_BYTES" to "0",
            "MAX_FILE_BYTES" to "100000001",
            "MAX_FILE_BYTES" to "invalid",
            "MAX_CONCURRENT_UPLOADS" to "0",
            "MAX_CONCURRENT_UPLOADS" to "1001",
            "MAX_CONCURRENT_UPLOADS" to "invalid",
            "S3_PATH_STYLE" to "invalid",
            "BUCKET_PREFIX" to "INVALID",
        )) {
            val error = assertFailsWith<IllegalArgumentException> {
                AttachmentStorageConfig.load(source(settings + (key to value)))
            }
            assertFalse(error.stackTraceToString().contains("test-secret"))
        }
    }

    private fun source(values: Map<String, String>, properties: Boolean = false) = object : BackendConfigSource {
        override fun env(key: String): String? = if (properties) null else values[key.removePrefix("SOUZ_ATTACHMENTS_")]
        override fun property(key: String): String? =
            if (properties) values[key.removePrefix("souz.attachments.").uppercase()] else null
    }

    private val settings = mapOf(
        "S3_ENDPOINT" to "http://localhost:9000",
        "S3_ACCESS_KEY" to "test-access",
        "S3_SECRET_KEY" to "test-secret",
    )
}
