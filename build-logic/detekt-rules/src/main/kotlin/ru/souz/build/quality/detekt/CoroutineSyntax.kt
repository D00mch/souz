package ru.souz.build.quality.detekt

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile

internal class CoroutineSyntax(file: KtFile) {
    private val importsCoroutines = file.importDirectives.any {
        it.importPath?.pathStr?.startsWith("kotlinx.coroutines") == true
    }

    fun isCoroutineCall(expression: KtCallExpression): Boolean {
        val callee = expression.calleeExpression?.text.orEmpty()
        if (importsCoroutines && callee in COROUTINE_CALLS) return true

        val qualified = expression.parent as? KtDotQualifiedExpression ?: return false
        return qualified.selectorExpression === expression &&
            qualified.receiverExpression.text.startsWith("kotlinx.coroutines")
    }

    fun isCoroutineType(name: String?): Boolean = importsCoroutines && name in COROUTINE_TYPES
}

internal val COROUTINE_CALLS = setOf(
    "actor",
    "async",
    "callbackFlow",
    "channelFlow",
    "coroutineScope",
    "flow",
    "launch",
    "produce",
    "runBlocking",
    "supervisorScope",
    "withContext",
)

private val COROUTINE_TYPES = setOf(
    "CoroutineScope",
    "Deferred",
    "Flow",
    "Job",
    "ReceiveChannel",
    "SendChannel",
)
