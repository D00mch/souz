package ru.souz.llms.giga

import kotlinx.coroutines.CancellationException
import ru.souz.llms.GigaMessageRole
import ru.souz.llms.GigaRequest
import ru.souz.llms.GigaResponse
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ShellException
import ru.souz.tool.ToolSetup
import ru.souz.tool.ToolSetupWithAttachments
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

inline fun <reified Input : Any> ToolSetup<Input>.toGiga(): GigaToolSetup {
    val toolSetup = this
    return object : GigaToolSetup {
        override val fn: GigaRequest.Function = GigaRequest.Function(
            name = toolSetup.name,
            description = toolSetup.description,
            parameters = GigaRequest.Parameters(
                type = "object",
                properties = HashMap<String, GigaRequest.Property>().apply {
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
                        val type = when (classifier) {
                            String::class -> "string"
                            Boolean::class -> "boolean"
                            Int::class, Long::class, Double::class -> "number"
                            List::class, Set::class, Array::class -> "array"
                            Map::class -> "object"
                            else -> when (classifier) {
                                is KClass<*> if classifier.isSubclassOf(Collection::class) -> "array"
                                is KClass<*> if classifier.isSubclassOf(Enum::class) -> "string"
                                else -> "object"
                            }
                        }
                        put(kProperty.name, GigaRequest.Property(type, description, enumValues))
                    }
                },
                required = Input::class.primaryConstructor?.parameters
                    ?.filter { !it.isOptional && !it.type.isMarkedNullable }
                    ?.mapNotNull { it.name }
                    ?: emptyList(),
            ),
            fewShotExamples = toolSetup.fewShotExamples.map { GigaRequest.FewShotExample(it.request, it.params) },
            returnParameters = GigaRequest.Parameters(
                type = toolSetup.returnParameters.type,
                properties = toolSetup.returnParameters.properties.mapValues {
                    GigaRequest.Property(it.value.type, it.value.description)
                },
            ),
        )

        override suspend fun invoke(functionCall: GigaResponse.FunctionCall): GigaRequest.Message {
            return try {
                val input: Input = gigaJsonMapper.convertValue(functionCall.arguments, Input::class.java)
                val toolResult = toolSetup.suspendInvoke(input)
                GigaRequest.Message(
                    role = GigaMessageRole.function,
                    content = gigaJsonMapper.writeValueAsString(toolResult),
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

inline fun <reified Input : Any> ToolSetupWithAttachments<Input>.toGiga(): GigaToolSetup {
    val toolSetup = this
    val gigaToolSetup = (toolSetup as ToolSetup<Input>).toGiga()
    return object : GigaToolSetup by gigaToolSetup {
        override suspend fun invoke(functionCall: GigaResponse.FunctionCall): GigaRequest.Message {
            return try {
                val input: Input = gigaJsonMapper.convertValue(functionCall.arguments, Input::class.java)
                val toolResult = toolSetup.suspendInvoke(input)
                GigaRequest.Message(
                    role = GigaMessageRole.function,
                    content = gigaJsonMapper.writeValueAsString(toolResult),
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

fun Throwable.toGigaToolMessage(name: String?): GigaRequest.Message {
    val msg = when (this) {
        is ShellException -> "The function was executed with shell, the exit code: $exitCode, output: $message"
        else -> "Can't invoke function: ${message ?: toString()}"
    }
    return GigaRequest.Message(
        role = GigaMessageRole.function,
        content = gigaJsonMapper.writeValueAsString(mapOf("result" to msg)),
        name = name,
    )
}
