package ru.souz.backend.storage.postgres

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlywayMigrationNamingTest {
    @Test
    fun `migration resources use unique monotonic flyway versions`() {
        val resourceDir = requireNotNull(javaClass.classLoader.getResource("db/migration")) {
            "Missing db/migration test resource directory."
        }
        val fileNames = Files.list(Paths.get(resourceDir.toURI())).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .map { it.name }
                .sorted()
                .toList()
        }

        assertEquals(
            listOf(
                "V1__stage10_postgres_storage.sql",
                "V2__stage11_llm_client_isolation.sql",
                "V3__tool_calls.sql",
                "V4__stage12_option_rename.sql",
            ),
            fileNames,
        )
        assertEquals(
            listOf("1", "2", "3", "4"),
            fileNames.map { it.substringAfter('V').substringBefore("__") },
        )
        assertTrue(fileNames.distinct().size == fileNames.size)
    }
}
