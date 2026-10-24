package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtDeclarationWithReturnType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

class LayeredMutability(config: Config) : Rule(
    config,
    "Mutable collections containing mutable records or collections require ownership review.",
), RequiresAnalysisApi {
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (!property.isLocal) inspect(property)
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        if (parameter.hasValOrVar()) inspect(parameter)
    }

    private fun inspect(declaration: KtDeclarationWithReturnType) {
        val layered = analyze(declaration) {
            val stored = declaration !is KtProperty ||
                (declaration.symbol as? KaPropertySymbol)?.let { it.hasBackingField && !it.isDelegatedProperty } == true
            stored && mutableCollectionType(declaration.returnType)?.collectionElement()?.let { hasMutableContents(it) } == true
        }
        if (layered) report(declaration.reviewFinding(
            "Stored mutable collection contains mutable records or nested collections; review immutable entry replacement while preserving coordination."
        ))
    }
}

private fun KaSession.hasMutableRecord(type: KaClassType): Boolean {
    val symbol = type.symbol as? KaNamedClassSymbol ?: return false
    return symbol.isData && symbol.declaredMemberScope.callables.filterIsInstance<KaPropertySymbol>().any { !it.isVal }
}

private fun KaSession.hasMutableContents(value: KaType): Boolean {
    if (mutableCollectionType(value) != null) return true
    val type = value as? KaClassType ?: return false
    return hasMutableRecord(type) ||
        (type.classId.asFqNameString() in READ_ONLY_COLLECTIONS && type.collectionElement()?.let { hasMutableContents(it) } == true)
}
