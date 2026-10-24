package ru.souz.llms.giga

import kotlinx.coroutines.CancellationException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ToolSetup
import ru.souz.tool.ToolSetupWithAttachments
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

@PublishedApi
internal inline fun <reified Input : Any> toolInputParameters(): LLMRequest.Parameters =
    LLMRequest.Parameters(
        type = "object",
        properties = HashMap<String, LLMRequest.Property>().apply {
            val clazz = Input::class
            for (kProperty: KCallable<*> in clazz.declaredMembers) {
                val annotation = kProperty.findAnnotation<InputParamDescription>() ?: continue
                val description = annotation.value
                val classifier = kProperty.returnType.classifier
                @Suppress("UNCHECKED_CAST")
                val enumValues: List<String>? =
                    if (classifier is KClass<*> && classifier.isSubclassOf(Enum::class)) {
                        (classifier.java.enumConstants as Array<out Enum<*>>).map { it.name }
                    } else {
                        null
                    }
                val type = classifier.toGigaSchemaType()
                val itemType = kProperty.returnType.arguments.firstOrNull()
                    ?.type
                    ?.classifier
                    .toGigaSchemaType()
                put(
                    kProperty.name,
                    gigaProperty(type, description, enumValues, itemType)
                )
            }
        },
        required = Input::class.primaryConstructor?.parameters
            ?.filter { !it.isOptional && !it.type.isMarkedNullable }
            ?.mapNotNull { it.name }
            ?: emptyList(),
    )

inline fun <reified Input : Any> ToolSetup<Input>.toGiga(): LLMToolSetup {
    val toolSetup = this
    return object : LLMToolSetup {
        override val fn: LLMRequest.Function = LLMRequest.Function(
            name = toolSetup.name,
            description = toolSetup.description,
            parameters = toolInputParameters<Input>(),
            fewShotExamples = toolSetup.fewShotExamples.map { LLMRequest.FewShotExample(it.request, it.params) },
            returnParameters = LLMRequest.Parameters(
                type = toolSetup.returnParameters.type,
                properties = toolSetup.returnParameters.properties.mapValues {
                    gigaProperty(it.value.type, it.value.description)
                },
            ),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            invoke(functionCall, ToolInvocationMeta.localDefault())

        override suspend fun invoke(
            functionCall: LLMResponse.FunctionCall,
            meta: ToolInvocationMeta,
        ): LLMRequest.Message {
            return try {
                val input: Input = restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
                val toolResult = toolSetup.suspendInvoke(input, meta)
                LLMRequest.Message(
                    role = LLMMessageRole.function,
                    content = toolResult.toGigaFunctionResultContent(),
                    name = functionCall.name,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toGigaToolMessage(functionCall.name)
            } catch (e: LinkageError) {
                e.toGigaToolMessage(functionCall.name)
            }
        }
    }
}

inline fun <reified Input : Any> ToolSetupWithAttachments<Input>.toGiga(): LLMToolSetup {
    val toolSetup = this
    val gigaToolSetup = (toolSetup as ToolSetup<Input>).toGiga()
    return object : LLMToolSetup by gigaToolSetup {
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            invoke(functionCall, ToolInvocationMeta.localDefault())

        override suspend fun invoke(
            functionCall: LLMResponse.FunctionCall,
            meta: ToolInvocationMeta,
        ): LLMRequest.Message {
            return try {
                val input: Input = restJsonMapper.convertValue(functionCall.arguments, Input::class.java)
                val toolResult = toolSetup.suspendInvoke(input, meta)
                LLMRequest.Message(
                    role = LLMMessageRole.function,
                    content = toolResult.toGigaFunctionResultContent(),
                    attachments = toolSetup.attachments,
                    name = functionCall.name,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toGigaToolMessage(functionCall.name)
            } catch (e: LinkageError) {
                e.toGigaToolMessage(functionCall.name)
            }
        }
    }
}

fun Throwable.toGigaToolMessage(name: String?): LLMRequest.Message {
    val msg = "Can't invoke function: ${message ?: toString()}"
    return LLMRequest.Message(
        role = LLMMessageRole.function,
        content = restJsonMapper.writeValueAsString(mapOf("result" to msg)),
        name = name,
    )
}

@PublishedApi
internal fun String.toGigaFunctionResultContent(): String {
    val result = trim()
    if (result.isNotEmpty()) {
        val parsed = runCatching { restJsonMapper.readTree(result) }.getOrNull()
        if (parsed?.isObject == true) return result
        if (parsed != null && !parsed.isNull) {
            return restJsonMapper.writeValueAsString(mapOf("result" to parsed))
        }
    }
    return restJsonMapper.writeValueAsString(mapOf("result" to this))
}

@PublishedApi
internal fun Any?.toGigaSchemaType(): String = when (this) {
    String::class -> "string"
    Boolean::class -> "boolean"
    Int::class, Long::class -> "integer"
    Float::class, Double::class -> "number"
    List::class, Set::class, Array::class -> "array"
    Map::class -> "object"
    is KClass<*> if isSubclassOf(Collection::class) -> "array"
    is KClass<*> if isSubclassOf(Enum::class) -> "string"
    else -> "object"
}

@PublishedApi
internal fun gigaProperty(
    type: String,
    description: String? = null,
    enumValues: List<String>? = null,
    itemType: String? = null,
): LLMRequest.Property = LLMRequest.Property(
    type = type,
    description = description,
    enum = enumValues,
    items = if (type == "array") gigaProperty(itemType ?: "object") else null,
    properties = if (type == "object") emptyMap() else null,
)
