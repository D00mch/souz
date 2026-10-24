package ru.souz.build.quality.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class SouzReviewRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("souz-review")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(::TemporaryCollectionForMembership, ::RepeatedComputationInGetter, ::LayeredMutability),
    )
}
