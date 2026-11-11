package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.JsonNode
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** A private bucket and scoped credentials on the shared real S3-compatible test service. */
internal fun pluginArtifactConfig(): S3PluginArtifactConfig = AttachmentMinio.config().let {
    S3PluginArtifactConfig(it.endpoint, it.bucket, it.accessKey, it.secretKey)
}

internal fun runtimeTest(block: suspend PluginFixture.() -> Unit): Unit = runBlocking {
    val fixture = PluginFixture()
    try { fixture.block() } finally { fixture.close() }
}

internal class PluginFixture {
    private val database = PluginDatabaseFixture()
    val dataSource get() = database.dataSource
    val repository get() = database.repository
    val peer get() = database.peer
    val artifactConfig by lazy { pluginArtifactConfig() }
    private val artifactStore = lazy { S3PluginArtifacts(artifactConfig) }
    val artifacts get() = artifactStore.value
    val config = PluginRuntimeConfig(queueMillis = 5_000, callMillis = 5_000, prepareMillis = 15_000, shutdownMillis = 5_000)
    private val hostServices = lazy { runtimeServices(repository) }
    private val services get() = hostServices.value
    private val instances = mutableListOf<BackendPluginRuntime>()
    private val bindings = mutableListOf<PluginExecutionBinding>()

    fun runtime(
        timing: PluginRuntimeConfig = config,
        random: Random = Random.Default,
        workDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(64),
    ): BackendPluginRuntime =
        BackendPluginRuntime(repository, artifacts, services, timing, random = random, workDispatcher = workDispatcher).also(instances::add)

    suspend fun install(name: String, revision: Long): PluginBinding = inspectPlugin(pluginFixture(name)).also {
        artifacts.store(pluginFixture(name), it)
        repository.install(it, revision)
    }

    suspend fun bind(runtime: BackendPluginRuntime, binding: PluginBinding, owner: String = "alice"): PluginExecutionBinding =
        runtime.acquire(owner, listOf(binding), PLUGIN_FIXTURE_TOOLS).also(bindings::add)

    suspend fun close() {
        bindings.forEach { it.release() }
        instances.forEach { check(it.shutdown()) { "Plugin test failed to drain." } }
        if (hostServices.isInitialized()) services.close()
        if (artifactStore.isInitialized()) artifacts.close()
        database.close()
    }
}

internal val PLUGIN_FIXTURE_TOOLS = setOf("report", "state", "fetch", "fail", "block").map { "plugin.reference.$it" }.toSet()
internal fun runtimeServices(repository: PostgresPluginRepository): PluginServices =
    PluginServices(repository, null, ChannelProviderRegistry(emptyList()), null)
internal suspend fun PluginExecutionBinding.call(name: String, arguments: Map<String, Any> = emptyMap(), owner: String = "alice"): JsonNode {
    val tool = catalog.toolsByCategory.values.flatMap { it.values }.single { it.fn.name == "plugin.reference.$name" }
    return restJsonMapper.readTree(tool.invoke(LLMResponse.FunctionCall(tool.fn.name, arguments), ToolInvocationMeta(owner)).content)
}
internal fun JsonNode.errorCode(): String = path("error").path("code").asText()
internal suspend fun awaitPlugin(predicate: () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(10) }
