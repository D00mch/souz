package ru.souz.build.quality.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtDeclarationWithReturnType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

class ThreadLocalInCoroutineCode(config: Config) : Rule(
    config,
    "JVM ThreadLocal state requires explicit review before use.",
), RequiresAnalysisApi {
    override fun visitKtFile(file: KtFile) {
        file.accept(ThreadLocalStateVisitor(::reportState))
    }

    private fun reportState(state: PsiElement) {
        report(
            Finding(
                Entity.from(state),
                "JVM ThreadLocal state requires reviewed @Suppress(\"ThreadLocalInCoroutineCode\"); " +
                    "coroutine access must also use asContextElement.",
            )
        )
    }
}

private class ThreadLocalStateVisitor(
    private val report: (PsiElement) -> Unit,
) : KtTreeVisitorVoid() {
    override fun visitProperty(property: KtProperty) {
        if (property.isJvmThreadLocal()) report(property.nameIdentifier ?: property)
        super.visitProperty(property)
    }

    override fun visitParameter(parameter: KtParameter) {
        if (parameter.isJvmThreadLocal()) report(parameter.nameIdentifier ?: parameter)
        super.visitParameter(parameter)
    }

    override fun visitSuperTypeListEntry(specifier: KtSuperTypeListEntry) {
        if (specifier.isJvmThreadLocal()) report(specifier)
        super.visitSuperTypeListEntry(specifier)
    }
}

private fun KtDeclarationWithReturnType.isJvmThreadLocal(): Boolean =
    analyze(this) { returnType.isSubtypeOf(THREAD_LOCAL_CLASS_ID) }

private fun KtSuperTypeListEntry.isJvmThreadLocal(): Boolean =
    typeReference?.let { reference ->
        analyze(reference) { reference.type.isSubtypeOf(THREAD_LOCAL_CLASS_ID) }
    } == true

private val THREAD_LOCAL_CLASS_ID = ClassId.topLevel(FqName("java.lang.ThreadLocal"))
