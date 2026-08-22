package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThreadLocalInCoroutineCodeTest {
    private val rule = ThreadLocalInCoroutineCode(Config.empty)

    @Test
    fun `reports thread-local state in a coroutine-owning class`() {
        val findings = rule.lint(
            """
            class Runtime {
                private val current = ThreadLocal<String>()
                suspend fun run() = Unit
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `calling asContextElement does not exempt coroutine-owned thread-local state`() {
        val findings = rule.lint(
            """
            import kotlinx.coroutines.asContextElement
            import kotlinx.coroutines.withContext

            class Runtime {
                private val current: ThreadLocal<String> = ThreadLocal()

                suspend fun run() = withContext(current.asContextElement("request")) { Unit }
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `discarding a context element does not exempt later coroutine access`() {
        val findings = rule.lint(
            """
            import kotlinx.coroutines.asContextElement

            class Runtime {
                private val current: ThreadLocal<String> = ThreadLocal()

                fun prepare() {
                    current.asContextElement("request")
                }

                suspend fun run() = current.get()
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `allows an explicitly reviewed thread-local declaration`() {
        val findings = rule.lint(
            """
            import kotlinx.coroutines.asContextElement
            import kotlinx.coroutines.withContext

            class Runtime {
                @Suppress("ThreadLocalInCoroutineCode")
                private val current: ThreadLocal<String> = ThreadLocal()

                suspend fun run() = withContext(current.asContextElement("request")) { current.get() }
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `allows thread-local state in a non-coroutine bridge`() {
        val findings = rule.lint(
            """
            class NativeBridge {
                private val current = ThreadLocal<String>()
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `does not mix state across nested declaration boundaries`() {
        val findings = rule.lint(
            """
            class NativeBridge {
                private val current = ThreadLocal<String>()

                class Worker {
                    suspend fun run() = Unit
                }
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }
}
