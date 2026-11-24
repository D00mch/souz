package ru.souz.agent.scenarios

internal class ScenarioAssertions {
    private var total = 0
    private val failures = mutableListOf<String>()

    fun check(passed: Boolean, failure: String) {
        total++
        if (!passed) failures += failure
    }

    fun result() = ScenarioAssertionResult(total - failures.size, total, failures.toList())
}

internal data class ScenarioAssertionResult(val passed: Int, val total: Int, val failures: List<String>)
