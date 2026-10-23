package ru.souz.build.quality.detekt

import dev.detekt.api.Entity
import dev.detekt.api.Finding
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiUtil

internal val COLLECTION_FACTORIES = setOf("kotlin.collections.setOf", "kotlin.collections.listOf", "kotlin.arrayOf")

internal fun KtElement.callableName(): String? = analyze(this) {
    val symbol = resolveToCall()?.successfulFunctionCallOrNull()?.symbol
    if (symbol is KaConstructorSymbol) symbol.containingClassId?.asFqNameString()
    else symbol?.callableId?.asSingleFqName()?.asString()
}

internal fun KtExpression.unwrapped(): KtExpression = KtPsiUtil.deparenthesize(this) ?: this

internal fun KtElement.reviewFinding(message: String): Finding = Finding(Entity.from(this), message)

internal fun KaSession.mutableCollectionType(type: KaType): KaClassType? =
    (sequenceOf(type) + type.allSupertypes).filterIsInstance<KaClassType>()
        .firstOrNull { it.classId.asFqNameString() in MUTABLE_COLLECTIONS }

internal fun KaClassType.collectionElement(): KaType? = typeArguments.lastOrNull()?.type

internal val READ_ONLY_COLLECTIONS = setOf(
    "kotlin.collections.Collection", "kotlin.collections.List", "kotlin.collections.Set", "kotlin.collections.Map",
    "kotlin.collections.Iterable",
)

private val MUTABLE_COLLECTIONS = setOf(
    "kotlin.collections.MutableCollection", "kotlin.collections.MutableList", "kotlin.collections.MutableSet",
    "kotlin.collections.MutableMap", "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet",
    "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
    "java.util.concurrent.ConcurrentHashMap", "java.util.concurrent.ConcurrentSkipListMap",
    "java.util.concurrent.CopyOnWriteArrayList", "java.util.concurrent.CopyOnWriteArraySet",
)
