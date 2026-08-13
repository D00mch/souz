package ru.souz.backend.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BackendRuntimeResourcesTest {
    @Test
    fun `close attempts every resource and aggregates failures`() {
        val closeOrder = mutableListOf<String>()
        val firstFailure = IllegalStateException("first close failed")
        val secondFailure = IllegalArgumentException("second close failed")
        val resources = BackendRuntimeResources(
            listOf(
                recordingCloseable("first", closeOrder, firstFailure),
                recordingCloseable("middle", closeOrder),
                recordingCloseable("last", closeOrder, secondFailure),
            )
        )

        val thrown = assertFailsWith<IllegalStateException> {
            resources.close()
        }

        assertSame(firstFailure, thrown)
        assertEquals(listOf("first", "middle", "last"), closeOrder)
        assertEquals(listOf(secondFailure), thrown.suppressed.toList())
    }

    @Test
    fun `close is idempotent after success`() {
        var closeCalls = 0
        val resources = BackendRuntimeResources(
            listOf(AutoCloseable { closeCalls += 1 })
        )

        resources.close()
        resources.close()

        assertEquals(1, closeCalls)
    }

    @Test
    fun `close is idempotent after failure`() {
        var closeCalls = 0
        val resources = BackendRuntimeResources(
            listOf(
                AutoCloseable {
                    closeCalls += 1
                    error("close failed")
                }
            )
        )

        assertFailsWith<IllegalStateException> { resources.close() }
        resources.close()

        assertEquals(1, closeCalls)
    }

    private fun recordingCloseable(
        name: String,
        closeOrder: MutableList<String>,
        failure: Throwable? = null,
    ): AutoCloseable = AutoCloseable {
        closeOrder += name
        failure?.let { throw it }
    }
}
