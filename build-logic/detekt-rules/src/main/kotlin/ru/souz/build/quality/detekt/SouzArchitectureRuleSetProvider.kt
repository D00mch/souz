package ru.souz.build.quality.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/** Registers Souz's source-architecture rules with Detekt. */
class SouzArchitectureRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("souz-architecture")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf { config -> SourceSetBoundaries(config) },
    )
}
