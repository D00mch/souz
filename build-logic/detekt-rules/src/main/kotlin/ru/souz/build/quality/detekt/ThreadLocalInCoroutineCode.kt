package ru.souz.build.quality.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

class ThreadLocalInCoroutineCode(config: Config) : Rule(
    config,
    "ThreadLocal state in coroutine code must be propagated explicitly through coroutine context.",
), RequiresAnalysisApi {
    override fun visitKtFile(file: KtFile) {
        reportScope(file, null)
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                reportScope(classOrObject, classOrObject)
                super.visitClassOrObject(classOrObject)
            }
        })
    }

    private fun reportScope(scope: PsiElement, classBoundary: KtClassOrObject?) {
        val facts = ThreadLocalFacts(scope.containingFile as KtFile, classBoundary)
        scope.accept(facts)
        if (!facts.usesCoroutines) return

        facts.uses.forEach { element ->
            report(
                Finding(
                    Entity.from(element),
                    "ThreadLocal state in coroutine code requires reviewed asContextElement propagation.",
                )
            )
        }
    }
}

private class ThreadLocalFacts(
    file: KtFile,
    private val classBoundary: KtClassOrObject?,
) : KtTreeVisitorVoid() {
    private val coroutineSyntax = CoroutineSyntax(file)
    val uses = mutableListOf<PsiElement>()

    var usesCoroutines = false
        private set

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        if (classOrObject !== classBoundary) return
        super.visitClassOrObject(classOrObject)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        if (function.hasModifier(KtTokens.SUSPEND_KEYWORD)) usesCoroutines = true
        super.visitNamedFunction(function)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        if (coroutineSyntax.isCoroutineCall(expression) || expression.calleeExpression?.text == "asContextElement") {
            usesCoroutines = true
        }
        super.visitCallExpression(expression)
    }

    override fun visitProperty(property: KtProperty) {
        forbidIfThreadLocal(property)
        super.visitProperty(property)
    }

    override fun visitParameter(parameter: KtParameter) {
        if (parameter.hasValOrVar()) forbidIfThreadLocal(parameter)
        super.visitParameter(parameter)
    }

    private fun forbidIfThreadLocal(declaration: KtCallableDeclaration) {
        if (!analyze(declaration) { declaration.returnType.isSubtypeOf(THREAD_LOCAL_CLASS_ID) }) return
        uses += declaration.nameIdentifier ?: declaration
    }
}

private val THREAD_LOCAL_CLASS_ID = ClassId.topLevel(FqName("java.lang.ThreadLocal"))
