package ru.souz.backend.toolcall.repository

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import ru.souz.backend.toolcall.model.ToolCall

class ToolCallRepositoryApiTest {
    @Test
    fun `tool call repository api does not expose uuid types`() {
        val repositoryTypes = ToolCallRepository::class.java.declaredMethods
            .flatMap { method -> method.parameterTypes.asList() + method.returnType }
        val contextTypes = ToolCallContext::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.type }
        val modelTypes = ToolCall::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.type }

        assertFalse((repositoryTypes + contextTypes + modelTypes).any { it == UUID::class.java })
    }
}
