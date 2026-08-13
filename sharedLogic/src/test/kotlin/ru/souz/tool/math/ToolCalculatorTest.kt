package ru.souz.tool.math

import ru.souz.llms.ToolInvocationMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCalculatorTest {
    private val calculator = ToolCalculator()
    private val meta = ToolInvocationMeta(userId = "calculator-test-user")

    @Test
    fun `evaluates ordinary arithmetic`() {
        assertEquals(
            "25",
            calculator.invoke(ToolCalculator.Input("(2 + 3) ^ 2"), meta),
        )
    }

    @Test
    fun `rejects oversized and deeply recursive expressions`() {
        val oversized = "1".repeat(ToolCalculator.MAX_EXPRESSION_LENGTH + 1)
        val deeplyNested = "-".repeat(ToolCalculator.MAX_PARSE_DEPTH + 1) + "1"

        assertTrue(
            calculator.invoke(ToolCalculator.Input(oversized), meta)
                .startsWith("Error: Expression exceeds"),
        )
        assertTrue(
            calculator.invoke(ToolCalculator.Input(deeplyNested), meta)
                .startsWith("Error: Expression nesting exceeds"),
        )
    }
}
