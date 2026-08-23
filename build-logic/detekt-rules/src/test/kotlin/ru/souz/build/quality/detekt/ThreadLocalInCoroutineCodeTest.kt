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
    fun `reports thread-local state in a coroutine-owning class`() {
        val findings = lint(
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
        val findings = lint(
            """
            import kotlinx.coroutines.asContextElement

            class Runtime {
                private val current: ThreadLocal<String> = ThreadLocal()

                fun context() = current.asContextElement("request")
                suspend fun run() = Unit
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports thread-local state created through withInitial`() {
        val findings = lint(
            """
            class Runtime {
                private val current = ThreadLocal.withInitial { "request" }
                suspend fun run() = current.get()
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports thread-local constructor state but ignores function parameters`() {
        val findings = lint(
            """
            class Runtime(private val current: ThreadLocal<String>) {
                suspend fun run(temporary: ThreadLocal<String>) = temporary.get()
            }
            """.trimIndent()
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports aliased fully-qualified and inherited JVM thread-local state`() {
        val findings = lint(
            """
            import java.lang.ThreadLocal as JvmThreadLocal

            class Runtime {
                private val first = JvmThreadLocal<String>()
                private val second = java.lang.ThreadLocal.withInitial { "request" }
                private val third = InheritableThreadLocal<String>()
                suspend fun run() = first.get() + second.get() + third.get()
            }
            """.trimIndent()
        )

        assertEquals(3, findings.size)
    }

    @Test
    fun `ignores an unrelated class named ThreadLocal`() {
        val findings = lint(
            """
            class ThreadLocal<T>(private val value: T)

            class Runtime {
                private val current = ThreadLocal("request")
                suspend fun run() = Unit
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `allows thread-local state in a non-coroutine bridge`() {
        val findings = lint(
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
        val findings = lint(
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

    private fun lint(code: String) = rule.lintWithContext(environment, code)
}
