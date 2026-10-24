package ru.souz.tool

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.tool.knowledge.KnowledgeRetriever
import ru.souz.tool.knowledge.ToolGetKnowledge
import ru.souz.tool.knowledge.ToolSearchKnowledge
import ru.souz.tool.memory.ToolSearchMemory
import ru.souz.tool.skills.SkillCommandExecutor

/** Tools composed with the backend's request-scoped catalog and Knowledge storage. */
fun skillRuntimeToolsDiModule(): DI.Module = DI.Module("skillRuntimeTools") {
    bindSingleton { SkillCommandExecutor(sandboxResolver = instance()) }
    bindSingleton { KnowledgeRetriever(instance()) }
    bindSingleton { ToolGetKnowledge(retriever = instance()) }
    bindSingleton { ToolSearchKnowledge(retriever = instance()) }
    bindSingleton { ToolSearchMemory(instanceOrNull<ConversationMemoryRuntime>() ?: NoopConversationMemoryRuntime) }
}
