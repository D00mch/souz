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
    fun `reports resolved JVM thread-local state regardless of construction syntax`() {
        val findings = lint(
            """
            import java.lang.ThreadLocal as JvmThreadLocal

            class Runtime {
                private val constructed = JvmThreadLocal<String>()
                private val factory = java.lang.ThreadLocal.withInitial { "request" }
                private val inherited = InheritableThreadLocal<String>()
            }
            """.trimIndent()
        )

        assertEquals(3, findings.size)
    }

    @Test
    fun `reports thread-local parameters locals and subclasses`() {
        val findings = lint(
            """
            class Runtime(private val current: ThreadLocal<String>) {
                fun run(temporary: ThreadLocal<String>) {
                    val local = ThreadLocal<String>()
                }
            }

            class RuntimeThreadLocal : ThreadLocal<String>()
            """.trimIndent()
        )

        assertEquals(4, findings.size)
    }

    @Test
    fun `ignores an unrelated class named ThreadLocal`() {
        val findings = lint(
            """
            class ThreadLocal<T>(private val value: T)

            class Runtime {
                private val current = ThreadLocal("request")
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `allows an explicitly reviewed thread-local declaration`() {
        val findings = lint(
            """
            class NativeBoundary {
                @Suppress("ThreadLocalInCoroutineCode")
                private val current = ThreadLocal<String>()
            }
            """.trimIndent()
        )

        assertEquals(0, findings.size)
    }

    private fun lint(code: String) = rule.lintWithContext(environment, code)
}
