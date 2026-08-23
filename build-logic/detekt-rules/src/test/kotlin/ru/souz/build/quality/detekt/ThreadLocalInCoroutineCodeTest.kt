package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.test.junit.KotlinCoreEnvironmentTest
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@KotlinCoreEnvironmentTest
class ThreadLocalInCoroutineCodeTest(
    private val environment: KotlinEnvironmentContainer,
) {
    private val rule = ThreadLocalInCoroutineCode(Config.empty)

    @Test
    fun `reports resolved JVM thread-local state in a coroutine-owning class`() {
        val findings = lint(
            """
            import java.lang.ThreadLocal as JvmThreadLocal

            class Runtime {
                private val constructed = JvmThreadLocal<String>()
                private val factory = java.lang.ThreadLocal.withInitial { "request" }
                private val inherited = InheritableThreadLocal<String>()

                suspend fun run() = constructed.get() + factory.get() + inherited.get()
            }
            """.trimIndent()
        )

        assertEquals(3, findings.size)
    }

    @Test
    fun `calling asContextElement does not exempt coroutine-owned thread-local state`() {
        val findings = lint(
            """
            import kotlinx.coroutines.asContextElement

            class Runtime {
                private val current: ThreadLocal<String> = ThreadLocal()

                fun context() = current.asContextElement("request")
                suspend fun run() = current.get()
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports thread-local parameters accessed by suspend functions`() {
        val findings = lint(
            """
            class Runtime(private val current: ThreadLocal<String>) {
                suspend fun run(temporary: ThreadLocal<String>) = current.get() + temporary.get()
            }
            """.trimIndent()
        )

        assertEquals(2, findings.size)
    }

    @Test
    fun `ignores an unrelated class named ThreadLocal`() {
        val findings = lint(
            """
            class ThreadLocal<T>(private val value: T) {
                fun get(): T = value
            }

            class Runtime {
                private val current = ThreadLocal("request")
                suspend fun run() = current.get()
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports outer thread-local state accessed from nested suspend code`() {
        val findings = lint(
            """
            class Runtime {
                private val current = ThreadLocal<String>()

                inner class Worker {
                    suspend fun run() = current.get()
                }
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports thread-local access from a suspend-function-typed lambda`() {
        val findings = lint(
            """
            class Runtime {
                private val current = ThreadLocal<String>()
                val action: suspend () -> Unit = { current.get() }
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports thread-local access through an aliased coroutine builder`() {
        val findings = lint(
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch as start

            class Runtime(private val scope: CoroutineScope) {
                private val current = ThreadLocal<String>()

                fun run() {
                    scope.start { current.get() }
                }
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `ignores thread-local access outside suspend execution`() {
        val findings = lint(
            """
            class Runtime {
                private val current = ThreadLocal<String>()

                fun current() = current.get()
                suspend fun unrelated() = Unit
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    private fun lint(code: String) = rule.lintWithContext(environment, code)
}
