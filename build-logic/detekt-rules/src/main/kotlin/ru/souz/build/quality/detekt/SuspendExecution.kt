package ru.souz.build.quality.detekt

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Returns whether this element is nested in a suspend function or suspend-typed lambda. */
internal fun KtElement.isInsideSuspendExecution(): Boolean {
    var current: PsiElement? = parent
    while (current != null && current !is KtFile) {
        when (current) {
            is KtNamedFunction -> if (current.hasModifier(KtTokens.SUSPEND_KEYWORD)) return true
            is KtLambdaExpression -> if (current.isSuspendLambda()) return true
        }
        current = current.parent
    }
    return false
}

private fun KtLambdaExpression.isSuspendLambda(): Boolean =
    analyze(this) { (expectedType as? KaFunctionType)?.isSuspend == true }
