package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.test.junit.KotlinCoreEnvironmentTest
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@KotlinCoreEnvironmentTest
class MonitorInsideSuspendContextTest(
    private val environment: KotlinEnvironmentContainer,
) {
    private val rule = MonitorInsideSuspendContext(Config.empty)

    @Test
    fun `reports resolved monitor calls inside suspend execution`() {
        val findings = lint(
            """
            import java.util.Collections.synchronizedList as guardedList
            import kotlin.synchronized as withMonitor

            class Mailbox {
                suspend fun receive() {
                    withMonitor(this) { Unit }
                    guardedList(mutableListOf<String>())
                }
            }
            """.trimIndent()
        )

        assertEquals(2, findings.size)
    }

    @Test
    fun `reports monitor calls inside suspend-function-typed lambdas`() {
        val findings = lint(
            """
            val action: suspend () -> Unit = {
                synchronized(Unit) { Unit }
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports aliased synchronized annotation on suspend functions`() {
        val findings = lint(
            """
            import kotlin.jvm.Synchronized as Monitor

            class Mailbox {
                @Monitor
                suspend fun receive() = Unit
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `allows monitor coordination behind a non-suspending boundary`() {
        val findings = lint(
            """
            class Mailbox {
                fun close() = synchronized(this) { Unit }
                suspend fun receive() = Unit
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `allows an unrelated method named synchronized`() {
        val findings = lint(
            """
            class Monitor {
                fun synchronized() = Unit
            }

            class Mailbox(private val monitor: Monitor) {
                suspend fun receive() = monitor.synchronized()
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    private fun lint(code: String) = rule.lintWithContext(environment, code)
}
