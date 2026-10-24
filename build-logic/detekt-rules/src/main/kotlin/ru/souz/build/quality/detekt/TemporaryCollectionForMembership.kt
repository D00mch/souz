@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

class TemporaryCollectionForMembership(config: Config) : Rule(
    config,
    "Constant collections created only for membership should be replaced with comparisons or stored collections.",
), RequiresAnalysisApi {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.callableName() !in COLLECTION_FACTORIES || !expression.inExecutableBody()) return
        val arguments = expression.valueArguments
        if (arguments.isEmpty() || arguments.any { it.isSpread || it.getArgumentExpression()?.isConstantMember() != true }) return
        if (!expression.membershipUse()) return
        report(expression.reviewFinding(
            "A constant collection is created only for membership; use direct comparisons, when, or a stored immutable collection."
        ))
    }
}

private fun KtCallExpression.inExecutableBody(): Boolean = generateSequence(parent) { it.parent }
    .takeWhile { it !is KtClassOrObject && it !is KtFile }
    .any { it is KtNamedFunction || it is KtPropertyAccessor || it is KtLambdaExpression }

private fun KtExpression.isConstantMember(): Boolean = when (val value = unwrapped()) {
    is KtConstantExpression -> true
    is KtStringTemplateExpression -> value.entries.all { it is KtLiteralStringTemplateEntry || it is KtEscapeStringTemplateEntry }
    is KtPrefixExpression -> (value.operationToken == KtTokens.MINUS || value.operationToken == KtTokens.PLUS) &&
        value.baseExpression?.unwrapped() is KtConstantExpression
    is KtNameReferenceExpression, is KtDotQualifiedExpression -> analyze(value) {
        val symbol = when (value) {
            is KtNameReferenceExpression -> value.resolveSymbol()
            is KtDotQualifiedExpression -> value.resolveSymbol()
            else -> null
        }
        symbol is KaEnumEntrySymbol || (symbol is KaKotlinPropertySymbol && symbol.isConst)
    }
    else -> false
}

private fun KtCallExpression.membershipUse(): Boolean {
    var collection: KtExpression = this
    while (true) {
        val outer = collection.parent
        collection = when {
            outer is KtParenthesizedExpression -> outer
            outer is KtDotQualifiedExpression && outer.selectorExpression == collection -> outer
            else -> break
        }
    }
    val use = collection.parent
    val callable = when {
        use is KtBinaryExpression && use.right == collection &&
            (use.operationToken == KtTokens.IN_KEYWORD || use.operationToken == KtTokens.NOT_IN) -> use.callableName()
        use is KtWhenConditionInRange && use.rangeExpression == collection -> use.callableName()
        use is KtQualifiedExpression && use.receiverExpression == collection -> use.selectorExpression?.callableName()
        else -> null
    }
    return callable in STANDARD_CONTAINS
}

private val STANDARD_CONTAINS = setOf(
    "kotlin.collections.contains", "kotlin.collections.Collection.contains", "kotlin.collections.List.contains",
    "kotlin.collections.Set.contains",
)
