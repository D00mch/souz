package ru.souz.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import ru.souz.backend.common.BackendConfigurationException
import ru.souz.backend.storage.StorageMode

class BackendFeatureFlagsTest {
    @Test
    fun `feature flags default to false`() {
        val flags = BackendFeatureFlags.load(MapBackendConfigSource())

        assertFalse(flags.wsEvents)
        assertFalse(flags.streamingMessages)
        assertFalse(flags.toolEvents)
        assertFalse(flags.choices)
        assertFalse(flags.durableEventReplay)
    }

    @Test
    fun `feature flags read env and property keys`() {
        val flags = BackendFeatureFlags.load(
            MapBackendConfigSource(
                env = mapOf(
                    "SOUZ_FEATURE_WS_EVENTS" to "true",
                    "SOUZ_FEATURE_STREAMING_MESSAGES" to "TRUE",
                ),
                properties = mapOf(
                    "souz.backend.feature.toolEvents" to "true",
                    "souz.backend.feature.choices" to "true",
                    "souz.backend.feature.durableEventReplay" to "true",
                ),
            )
        )

        assertTrue(flags.wsEvents)
        assertTrue(flags.streamingMessages)
        assertTrue(flags.toolEvents)
        assertTrue(flags.choices)
        assertTrue(flags.durableEventReplay)
    }
}

class StorageModeTest {
    @Test
    fun `storage mode defaults to memory`() {
        assertEquals(StorageMode.MEMORY, StorageMode.load(MapBackendConfigSource()))
    }

    @Test
    fun `storage mode reads config and rejects unimplemented modes`() {
        val filesystem = StorageMode.load(
            MapBackendConfigSource(env = mapOf("SOUZ_STORAGE_MODE" to "filesystem"))
        )
        val postgres = StorageMode.load(
            MapBackendConfigSource(properties = mapOf("souz.backend.storageMode" to "postgres"))
        )

        assertEquals(StorageMode.FILESYSTEM, filesystem)
        assertEquals(StorageMode.POSTGRES, postgres)
        assertFailsWith<BackendConfigurationException> { filesystem.requireSupported() }
        assertFailsWith<BackendConfigurationException> { postgres.requireSupported() }
    }

    @Test
    fun `storage mode rejects unknown values`() {
        val error = assertFailsWith<BackendConfigurationException> {
            StorageMode.load(
                MapBackendConfigSource(env = mapOf("SOUZ_STORAGE_MODE" to "mariadb"))
            )
        }

        assertTrue(error.message.orEmpty().contains("mariadb"))
    }
}

private class MapBackendConfigSource(
    private val env: Map<String, String> = emptyMap(),
    private val properties: Map<String, String> = emptyMap(),
) : BackendConfigSource {
    override fun env(key: String): String? = env[key]

    override fun property(key: String): String? = properties[key]
}
