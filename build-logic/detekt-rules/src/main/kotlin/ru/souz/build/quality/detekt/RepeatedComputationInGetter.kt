@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.*

class RepeatedComputationInGetter(config: Config) : Rule(
    config,
    "Computed getter allocations and collection traversal require review.",
), RequiresAnalysisApi {
    override fun visitPropertyAccessor(accessor: KtPropertyAccessor) {
        super.visitPropertyAccessor(accessor)
        if (!accessor.isGetter) return
        val body = accessor.bodyExpression ?: return
        val operation = analyze(accessor) {
            val work = GetterWork(this)
            body.accept(work)
            work.operation.takeUnless { work.unstable }
        } ?: return
        report(accessor.reviewFinding(
            "$operation performs work on each getter access; review the caller or evaluation strategy. " +
                "Read-only references do not prove deep immutability, and caching changes timing and identity."
        ))
    }
}

private class GetterWork(private val session: KaSession) : KtTreeVisitorVoid() {
    var operation: String? = null
    var unstable = false

    override fun visitNamedFunction(function: KtNamedFunction) = Unit

    override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
        // Returning a callback does not execute its body during getter access.
        val call = generateSequence(lambdaExpression.parent) { it.parent }
            .firstOrNull { it is KtExpression && it !is KtParenthesizedExpression } as? KtCallExpression
        if (call?.callableName() in COLLECTION_OPERATIONS) super.visitLambdaExpression(lambdaExpression)
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        with(session) {
            val symbol = (expression as? KtNameReferenceExpression)?.resolveSymbol()
            if (symbol is KaVariableSymbol && !stableVariable(symbol)) unstable = true
        }
        super.visitSimpleNameExpression(expression)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        val name = expression.callableName()
        if (name !in ALLOCATIONS && name !in COLLECTION_OPERATIONS) unstable = true
        with(session) {
            if (operation == null && name in ALLOCATIONS &&
                (expression.valueArguments.isNotEmpty() || name == "kotlin.arrayOf") && expression.valueArguments.all {
                    !it.isSpread && it.getArgumentExpression()?.let { input -> stableScalar(input) } == true
                }) operation = name?.substringAfterLast('.')
            val receiver = (expression.parent as? KtQualifiedExpression)?.receiverExpression
            if (operation == null && name in COLLECTION_OPERATIONS && receiver != null && stableInput(receiver) &&
                (name !in PREDICATE_SCANS || expression.valueArguments.isNotEmpty())) {
                operation = name?.substringAfterLast('.')
            }
        }
        super.visitCallExpression(expression)
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        if (expression.callableName() == "kotlin.to" && with(session) {
                expression.left?.let { stableScalar(it) } == true && expression.right?.let { stableScalar(it) } == true
            }) operation = operation ?: "to"
        super.visitBinaryExpression(expression)
    }
}

private fun KaSession.stableVariable(symbol: KaVariableSymbol): Boolean = symbol.isVal &&
    symbol.returnType.arrayElementType == null && mutableCollectionType(symbol.returnType) == null && when (symbol) {
        is KaPropertySymbol -> symbol.hasBackingField && !symbol.isDelegatedProperty &&
            symbol.modality == KaSymbolModality.FINAL && symbol.getter?.isNotDefault == false
        is KaValueParameterSymbol, is KaEnumEntrySymbol -> true
        is KaLocalVariableSymbol -> !symbol.isLateInit
        else -> false
    }

private fun KaSession.stableInput(input: KtExpression): Boolean = when (val value = input.unwrapped()) {
    is KtConstantExpression, is KtThisExpression -> true
    is KtStringTemplateExpression -> !value.hasInterpolation()
    is KtPrefixExpression -> value.baseExpression?.let { stableScalar(it) } == true
    is KtNameReferenceExpression -> (value.resolveSymbol() as? KaVariableSymbol)?.let { stableVariable(it) } == true
    is KtDotQualifiedExpression -> {
        val symbol = (value.selectorExpression as? KtNameReferenceExpression)?.resolveSymbol()
        symbol is KaEnumEntrySymbol || (symbol is KaKotlinPropertySymbol && symbol.isConst) ||
            (symbol is KaVariableSymbol && stableVariable(symbol) && stableInput(value.receiverExpression))
    }
    else -> false
}

private fun KaSession.stableScalar(input: KtExpression): Boolean {
    if (!stableInput(input)) return false
    val type = input.expressionType as? KaClassType ?: return false
    return type.classId.asFqNameString() in SCALARS ||
        (type.symbol as? KaNamedClassSymbol)?.classKind == KaClassKind.ENUM_CLASS
}

private val SCALARS = setOf(
    "kotlin.String", "kotlin.Boolean", "kotlin.Char", "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
    "kotlin.Float", "kotlin.Double", "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong", "kotlin.Nothing",
)
private val ALLOCATIONS = COLLECTION_FACTORIES + setOf("kotlin.Pair", "kotlin.Triple", "kotlin.to")
private val PREDICATE_SCANS = setOf("count", "any", "all", "none", "first", "firstOrNull")
    .mapTo(mutableSetOf()) { "kotlin.collections.$it" }
private val COLLECTION_OPERATIONS = PREDICATE_SCANS + setOf(
    "map", "mapNotNull", "flatMap", "filter", "filterNot", "associate", "associateBy", "associateWith", "groupBy",
).map { "kotlin.collections.$it" }
