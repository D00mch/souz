package ru.souz.build.quality.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUserType

class ThreadLocalInCoroutineCode(config: Config) : Rule(
    config,
    "ThreadLocal state in coroutine code must be propagated explicitly through coroutine context.",
) {
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

        facts.unpropagatedUses().forEach { use ->
            report(
                Finding(
                    Entity.from(use.element),
                    "${use.name} state in coroutine code must use asContextElement or coroutine context.",
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
    private val uses = linkedMapOf<Int, ThreadLocalUse>()
    private val propagatedNames = mutableSetOf<String>()
    private val propagatedRanges = mutableListOf<IntRange>()

    var usesCoroutines = false
        private set

    fun unpropagatedUses(): List<ThreadLocalUse> = uses.values.filterNot { use ->
        use.declarationName in propagatedNames ||
            propagatedRanges.any { range -> use.element.textRange.startOffset in range }
    }

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
        expression.calleeExpression?.text
            ?.takeIf { it in THREAD_LOCAL_TYPES }
            ?.let { forbid(expression.calleeExpression ?: expression, it) }
        super.visitCallExpression(expression)
    }

    override fun visitUserType(type: KtUserType) {
        val name = type.referencedName
        if (coroutineSyntax.isCoroutineType(name)) usesCoroutines = true
        if (name in THREAD_LOCAL_TYPES) forbid(type.referenceExpression ?: type, name.orEmpty())
        super.visitUserType(type)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        val call = expression.selectorExpression as? KtCallExpression
        if (call?.calleeExpression?.text == "asContextElement") {
            val receiver = expression.receiverExpression
            propagatedNames += receiver.text
            propagatedRanges += receiver.textRange.startOffset until receiver.textRange.endOffset
            usesCoroutines = true
        }
        super.visitDotQualifiedExpression(expression)
    }

    private fun forbid(element: PsiElement, name: String) {
        val declaration = element.enclosingStateDeclaration()
        val anchor = declaration?.nameIdentifier ?: declaration ?: element
        uses.putIfAbsent(
            anchor.textRange.startOffset,
            ThreadLocalUse(anchor, name, declaration?.name),
        )
    }

    private fun PsiElement.enclosingStateDeclaration(): KtNamedDeclaration? {
        var current: PsiElement? = this
        while (current != null && current !== classBoundary && current !is KtFile) {
            if (current is KtProperty || current is KtParameter) return current as KtNamedDeclaration
            current = current.parent
        }
        return null
    }
}

private data class ThreadLocalUse(
    val element: PsiElement,
    val name: String,
    val declarationName: String?,
)

private val THREAD_LOCAL_TYPES = setOf("ThreadLocal", "InheritableThreadLocal")
