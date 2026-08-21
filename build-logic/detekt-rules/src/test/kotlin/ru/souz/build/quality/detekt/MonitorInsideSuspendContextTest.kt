package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonitorInsideSuspendContextTest {
    private val rule = MonitorInsideSuspendContext(Config.empty)

    @Test
    fun `reports synchronized inside a suspend function`() {
        val findings = rule.lint(
            """
            class Mailbox {
                suspend fun receive() = synchronized(this) { Unit }
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports synchronized collection inside a coroutine builder`() {
        val findings = rule.lint(
            """
            import java.util.Collections
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            fun start(scope: CoroutineScope) {
                scope.launch {
                    Collections.synchronizedList(mutableListOf<String>())
                }
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `allows monitor state in a separate non-suspending method`() {
        val findings = rule.lint(
            """
            class Mailbox {
                suspend fun receive() = Unit
                fun close() = synchronized(this) { Unit }
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `allows a domain method named synchronized`() {
        val findings = rule.lint(
            """
            class Mailbox(private val monitor: Monitor) {
                suspend fun receive() = monitor.synchronized()
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `allows volatile state outside a monitor`() {
        val findings = rule.lint(
            """
            class Mailbox {
                @Volatile private var open = true
                suspend fun receive() = Unit
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }
}
