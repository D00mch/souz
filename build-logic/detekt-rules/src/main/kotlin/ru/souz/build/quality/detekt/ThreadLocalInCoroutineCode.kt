package ru.souz.build.quality.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

class ThreadLocalInCoroutineCode(config: Config) : Rule(
    config,
    "ThreadLocal state in coroutine code must be propagated explicitly through coroutine context.",
), RequiresAnalysisApi {
    override fun visitKtFile(file: KtFile) {
        file.accept(ThreadLocalAccessVisitor(::reportAccess))
    }

    private fun reportAccess(access: KtCallExpression) {
        val operation = access.calleeExpression?.text.orEmpty()
        report(
            Finding(
                Entity.from(access.calleeExpression ?: access),
                "ThreadLocal.$operation in coroutine code requires reviewed asContextElement propagation.",
            )
        )
    }
}

private class ThreadLocalAccessVisitor(
    private val report: (KtCallExpression) -> Unit,
) : KtTreeVisitorVoid() {
    override fun visitQualifiedExpression(expression: KtQualifiedExpression) {
        val access = expression.selectorExpression as? KtCallExpression
        if (access == null) {
            super.visitQualifiedExpression(expression)
            return
        }
        if (
            access.calleeExpression?.text in THREAD_LOCAL_STATE_OPERATIONS &&
            expression.receiverExpression.isJvmThreadLocal() &&
            access.isInsideSuspendExecution()
        ) {
            report(access)
        }
        super.visitQualifiedExpression(expression)
    }
}

private fun KtExpression.isJvmThreadLocal(): Boolean =
    analyze(this) { expressionType?.isSubtypeOf(THREAD_LOCAL_CLASS_ID) == true }

private fun KtElement.isInsideSuspendExecution(): Boolean {
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

private val THREAD_LOCAL_CLASS_ID = ClassId.topLevel(FqName("java.lang.ThreadLocal"))
private val THREAD_LOCAL_STATE_OPERATIONS = setOf("get", "set", "remove")
