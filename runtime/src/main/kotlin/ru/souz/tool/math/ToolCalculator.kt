package ru.souz.tool.math

import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.*
import kotlin.math.pow

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
                val x = parseExpression()
                if (pos < expression.length) throw RuntimeException("Unexpected: " + ch.toChar())
                return x
            }

            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    if (eat('+'.code)) x += parseTerm() // addition
                    else if (eat('-'.code)) x -= parseTerm() // subtraction
                    else return x
                }
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    if (eat('*'.code)) x *= parseFactor() // multiplication
                    else if (eat('/'.code)) x /= parseFactor() // division
                    else return x
                }
            }

            fun parseFactor(): Double {
                if (eat('+'.code)) return parseFactor() // unary plus
                if (eat('-'.code)) return -parseFactor() // unary minus

                var x: Double
                val startPos = pos
                if (eat('('.code)) { // parentheses
                    x = parseExpression()
                    if (!eat(')'.code)) throw RuntimeException("Missing closing parenthesis")
                } else if (ch in '0'.code..'9'.code || ch == '.'.code) { // numbers
                    while (ch in '0'.code..'9'.code || ch == '.'.code) nextChar()
                    x = expression.substring(startPos, pos).toDouble()
                } else if (ch in 'a'.code..'z'.code) { // functions
                    while (ch in 'a'.code..'z'.code) nextChar()
                    val func = expression.substring(startPos, pos)
                    x = parseFactor()
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

                if (eat('^'.code)) x = x.pow(parseFactor()) // exponentiation

                return x
            }
        }.parse()
    }
}
