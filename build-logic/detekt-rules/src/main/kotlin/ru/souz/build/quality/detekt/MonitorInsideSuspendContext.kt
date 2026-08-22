package ru.souz.build.quality.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

class MonitorInsideSuspendContext(config: Config) : Rule(
    config,
    "JVM monitor coordination inside suspend or coroutine-builder code should be reviewed.",
) {
    override fun visitKtFile(file: KtFile) {
        val ranges = CoroutineRanges(file).also(file::accept).ranges
        val visitor = MonitorUses(ranges) { element, name ->
            report(
                Finding(
                    Entity.from(element),
                    "$name runs inside suspend or coroutine-builder code; prefer Mutex or an explicit JVM boundary.",
                )
            )
        }
        file.accept(visitor)
    }
}

private class CoroutineRanges(file: KtFile) : KtTreeVisitorVoid() {
    private val coroutineSyntax = CoroutineSyntax(file)
    val ranges = mutableListOf<IntRange>()

    override fun visitNamedFunction(function: KtNamedFunction) {
        if (function.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
            function.bodyExpression?.textRange?.let { ranges += it.startOffset until it.endOffset }
        }
        super.visitNamedFunction(function)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        if (coroutineSyntax.isCoroutineCall(expression)) {
            expression.lambdaArguments.forEach { argument ->
                argument.getLambdaExpression()?.bodyExpression?.textRange
                    ?.let { ranges += it.startOffset until it.endOffset }
            }
            expression.valueArguments.forEach { argument ->
                (argument.getArgumentExpression() as? KtLambdaExpression)?.bodyExpression?.textRange
                    ?.let { ranges += it.startOffset until it.endOffset }
            }
        }
        super.visitCallExpression(expression)
    }
}

private class MonitorUses(
    private val coroutineRanges: List<IntRange>,
    private val report: (PsiElement, String) -> Unit,
) : KtTreeVisitorVoid() {
    private val reportedOffsets = mutableSetOf<Int>()

    override fun visitCallExpression(expression: KtCallExpression) {
        if (expression.inCoroutineRange()) {
            val callee = expression.calleeExpression?.text.orEmpty()
            when {
                callee == "synchronized" && expression.hasKotlinOrNoReceiver() ->
                    reportOnce(expression.calleeExpression ?: expression, "synchronized")

                callee.startsWith("synchronized") && expression.hasCollectionsReceiver() ->
                    reportOnce(expression.calleeExpression ?: expression, "Collections.$callee")
            }
        }
        super.visitCallExpression(expression)
    }

    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        if (annotationEntry.shortName?.asString() == "Synchronized") {
            val function = annotationEntry.enclosingFunction()
            if (function?.hasModifier(KtTokens.SUSPEND_KEYWORD) == true) {
                reportOnce(annotationEntry, "@Synchronized")
            }
        }
        super.visitAnnotationEntry(annotationEntry)
    }

    private fun PsiElement.inCoroutineRange(): Boolean =
        coroutineRanges.any { range -> textRange.startOffset in range }

    private fun reportOnce(element: PsiElement, name: String) {
        if (reportedOffsets.add(element.textRange.startOffset)) report(element, name)
    }
}

private fun KtCallExpression.hasKotlinOrNoReceiver(): Boolean {
    val qualified = parent as? KtDotQualifiedExpression ?: return true
    if (qualified.selectorExpression !== this) return true
    return qualified.receiverExpression.text == "kotlin"
}

private fun KtCallExpression.hasCollectionsReceiver(): Boolean {
    val qualified = parent as? KtDotQualifiedExpression ?: return false
    if (qualified.selectorExpression !== this) return false
    return qualified.receiverExpression.text.removePrefix("java.util.") == "Collections"
}

private fun PsiElement.enclosingFunction(): KtNamedFunction? {
    var current = parent
    while (current != null && current !is KtFile) {
        if (current is KtNamedFunction) return current
        current = current.parent
    }
    return null
}
