package ru.souz.build.quality.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

class MonitorInsideSuspendContext(config: Config) : Rule(
    config,
    "JVM monitor coordination inside suspend execution should be reviewed.",
), RequiresAnalysisApi {
    override fun visitKtFile(file: KtFile) {
        file.accept(MonitorVisitor(::reportMonitor))
    }

    private fun reportMonitor(element: PsiElement, name: String) {
        report(
            Finding(
                Entity.from(element),
                "$name runs inside suspend execution; prefer Mutex or an explicit JVM boundary.",
            )
        )
    }
}

private class MonitorVisitor(
    private val report: (PsiElement, String) -> Unit,
) : KtTreeVisitorVoid() {
    override fun visitCallExpression(expression: KtCallExpression) {
        if (expression.isInsideSuspendExecution()) {
            expression.monitorName()?.let { name ->
                report(expression.calleeExpression ?: expression, name)
            }
        }
        super.visitCallExpression(expression)
    }

    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        val function = annotationEntry.enclosingFunction()
        if (
            function?.hasModifier(KtTokens.SUSPEND_KEYWORD) == true &&
            annotationEntry.isJvmSynchronized()
        ) {
            report(annotationEntry, "@Synchronized")
        }
        super.visitAnnotationEntry(annotationEntry)
    }
}

private fun KtCallExpression.monitorName(): String? {
    val callable = analyze(this) {
        resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId?.asSingleFqName()?.asString()
    }
    return when {
        callable == "kotlin.synchronized" -> "synchronized"
        callable?.startsWith("java.util.Collections.synchronized") == true ->
            "Collections.${callable.substringAfterLast('.')}"
        else -> null
    }
}

@OptIn(KaExperimentalApi::class)
private fun KtAnnotationEntry.isJvmSynchronized(): Boolean =
    analyze(this) { resolveSymbol()?.containingClassId == SYNCHRONIZED_CLASS_ID }

private fun PsiElement.enclosingFunction(): KtNamedFunction? {
    var current = parent
    while (current != null && current !is KtFile) {
        if (current is KtNamedFunction) return current
        current = current.parent
    }
    return null
}

private val SYNCHRONIZED_CLASS_ID = ClassId.topLevel(FqName("kotlin.jvm.Synchronized"))
