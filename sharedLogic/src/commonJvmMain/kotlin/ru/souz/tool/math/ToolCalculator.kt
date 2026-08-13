package ru.souz.tool.math

import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.*
import kotlin.math.pow

/** Evaluates bounded arithmetic expressions without filesystem, process, or network access. */
class ToolCalculator : ToolSetup<ToolCalculator.Input> {

    data class Input(
        @InputParamDescription("Mathematical expression to evaluate (e.g., '128 * 453', '10 + 5 / 2', '(2 + 3) ^ 2')")
        val expression: String
    )

    override val name: String = "Calculator"
    override val description: String = "Evaluates a mathematical expression. Use this for ANY math calculations to ensure accuracy."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Calculate 15 multiplied by 4",
            params = mapOf("expression" to "15 * 4")
        ),
        FewShotExample(
            request = "What is the square root of 144?",
            params = mapOf("expression" to "144 ^ 0.5")
        ),
         FewShotExample(
            request = "Count 500 / 20 + 5",
            params = mapOf("expression" to "500 / 20 + 5")
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "The result of the calculation")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        return try {
            val result = evaluate(input.expression)
            val formattedResult = if (result % 1.0 == 0.0) {
                if (result >= Long.MIN_VALUE && result <= Long.MAX_VALUE) {
                    result.toLong().toString()
                } else {
                    java.math.BigDecimal.valueOf(result).toPlainString()
                }
            } else {
                result.toString()
            }
            formattedResult
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun evaluate(expression: String): Double {
        require(expression.length <= MAX_EXPRESSION_LENGTH) {
            "Expression exceeds the $MAX_EXPRESSION_LENGTH character limit"
        }
        return object : Any() {
            var pos = -1
            var ch = 0

            fun nextChar() {
                ch = if (++pos < expression.length) expression[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) {
                    nextChar()
                    return true
                }
                return false
            }

            fun parse(): Double {
                nextChar()
                val x = parseExpression(depth = 0)
                if (pos < expression.length) throw RuntimeException("Unexpected: " + ch.toChar())
                return x
            }

            fun parseExpression(depth: Int): Double {
                var x = parseTerm(depth)
                while (true) {
                    if (eat('+'.code)) x += parseTerm(depth) // addition
                    else if (eat('-'.code)) x -= parseTerm(depth) // subtraction
                    else return x
                }
            }

            fun parseTerm(depth: Int): Double {
                var x = parseFactor(depth)
                while (true) {
                    if (eat('*'.code)) x *= parseFactor(depth) // multiplication
                    else if (eat('/'.code)) x /= parseFactor(depth) // division
                    else return x
                }
            }

            fun parseFactor(depth: Int): Double {
                require(depth <= MAX_PARSE_DEPTH) {
                    "Expression nesting exceeds the $MAX_PARSE_DEPTH level limit"
                }
                if (eat('+'.code)) return parseFactor(depth + 1) // unary plus
                if (eat('-'.code)) return -parseFactor(depth + 1) // unary minus

                var x: Double
                val startPos = pos
                if (eat('('.code)) { // parentheses
                    x = parseExpression(depth + 1)
                    if (!eat(')'.code)) throw RuntimeException("Missing closing parenthesis")
                } else if (ch in '0'.code..'9'.code || ch == '.'.code) { // numbers
                    while (ch in '0'.code..'9'.code || ch == '.'.code) nextChar()
                    x = expression.substring(startPos, pos).toDouble()
                } else if (ch in 'a'.code..'z'.code) { // functions
                    while (ch in 'a'.code..'z'.code) nextChar()
                    val func = expression.substring(startPos, pos)
                    x = parseFactor(depth + 1)
                    x = when (func) {
                        "sqrt" -> kotlin.math.sqrt(x)
                        "sin" -> kotlin.math.sin(Math.toRadians(x))
                        "cos" -> kotlin.math.cos(Math.toRadians(x))
                        "tan" -> kotlin.math.tan(Math.toRadians(x))
                        else -> throw RuntimeException("Unknown function: $func")
                    }
                } else {
                    throw RuntimeException("Unexpected: " + ch.toChar())
                }

                if (eat('^'.code)) x = x.pow(parseFactor(depth + 1)) // exponentiation

                return x
            }
        }.parse()
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)

    internal companion object {
        const val MAX_EXPRESSION_LENGTH = 4_096
        const val MAX_PARSE_DEPTH = 64
    }
}
