package ru.souz.build.quality.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class SouzCoroutineRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("souz-coroutines")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ::MonitorInsideSuspendContext,
            ::ThreadLocalInCoroutineCode,
        ),
    )
}
