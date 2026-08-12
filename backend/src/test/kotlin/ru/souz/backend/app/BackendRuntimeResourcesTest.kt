package ru.souz.backend.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BackendRuntimeResourcesTest {
    @Test
    fun `closes resources once in declaration order`() {
        val closed = mutableListOf<String>()
        val resources = BackendRuntimeResources(
            listOf(
                AutoCloseable { closed += "application-scope" },
                AutoCloseable { closed += "provider-http" },
                AutoCloseable { closed += "local-runtime" },
                AutoCloseable { closed += "database" },
            )
        )

        resources.close()
        resources.close()

        assertEquals(
            listOf("application-scope", "provider-http", "local-runtime", "database"),
            closed,
        )
    }

    @Test
    fun `continues closing resources and reports every failure`() {
        val closed = mutableListOf<String>()
        val firstFailure = IllegalStateException("provider close failed")
        val secondFailure = IllegalArgumentException("database close failed")
        val resources = BackendRuntimeResources(
            listOf(
                AutoCloseable {
                    closed += "provider-http"
                    throw firstFailure
                },
                AutoCloseable { closed += "local-runtime" },
                AutoCloseable {
                    closed += "database"
                    throw secondFailure
                },
            )
        )

        val thrown = assertFailsWith<IllegalStateException> { resources.close() }
        resources.close()

        assertSame(firstFailure, thrown)
        assertEquals(listOf(secondFailure), thrown.suppressed.toList())
        assertEquals(listOf("provider-http", "local-runtime", "database"), closed)
    }
}
