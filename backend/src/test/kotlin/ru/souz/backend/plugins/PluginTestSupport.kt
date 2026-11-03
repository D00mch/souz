package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.zaxxer.hikari.HikariDataSource
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.write
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver

/** A private bucket and scoped credentials on the shared real S3-compatible test service. */
internal fun pluginArtifactConfig(): S3PluginArtifactConfig = AttachmentMinio.config().let {
    S3PluginArtifactConfig(it.endpoint, it.bucket, it.accessKey, it.secretKey)
}

internal fun runtimeTest(block: suspend PluginFixture.() -> Unit): Unit = runBlocking {
    val fixture = PluginFixture()
    try { fixture.block() } finally { fixture.close() }
}

internal class PluginFixture {
    val dataSource: HikariDataSource = PostgresDataSourceFactory.create(postgresAppConfig(newPostgresSchema("plugin_runtime")).postgres)
    val repository = PostgresPluginRepository(dataSource)
    val peer = PostgresPluginRepository(dataSource)
    val timing get() = config
    val artifactConfig by lazy { pluginArtifactConfig() }
    private val artifactStore = lazy { S3PluginArtifacts(artifactConfig) }
    val artifacts get() = artifactStore.value
    val config = PluginRuntimeConfig(queueMillis = 5_000, callMillis = 5_000, prepareMillis = 15_000, shutdownMillis = 5_000)
    private val hostServices = lazy { runtimeServices(repository) }
    private val services get() = hostServices.value
    private val instances = mutableListOf<BackendPluginRuntime>()
    private val bindings = mutableListOf<PluginExecutionBinding>()

    init {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("insert into users(id) values ('alice'), ('bob'), ('carol')") }
        }
    }

    fun runtime(timing: PluginRuntimeConfig = config, random: Random = Random.Default): BackendPluginRuntime =
        BackendPluginRuntime(repository, artifacts, services, timing, random = random).also(instances::add)

    suspend fun install(name: String, revision: Long): PluginBinding = inspectPlugin(pluginFixture(name)).also {
        artifacts.store(pluginFixture(name), it)
        repository.install(it, revision)
    }

    suspend fun bind(runtime: BackendPluginRuntime, binding: PluginBinding, owner: String = "alice"): PluginExecutionBinding =
        runtime.acquire(owner, listOf(binding), PLUGIN_FIXTURE_TOOLS).also(bindings::add)

    suspend fun expireBreaker() = dataSource.write { connection ->
        connection.createStatement().use {
            it.executeUpdate("update jvm_plugin_calls set open_until = clock_timestamp() - interval '1 second' where owner_id = 'alice'")
        }
    }

    suspend fun awaitBlockedQuery() = withTimeout(5_000) {
        while (dataSource.read { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""select count(*) from pg_stat_activity where wait_event_type = 'Lock'
                    and query like '%select 1 from jvm_plugin_calls%'""").use { it.next(); it.getInt(1) }
            }
        } == 0) delay(10)
    }

    suspend fun close() {
        bindings.forEach { it.release() }
        instances.forEach { check(it.shutdown()) { "Plugin test failed to drain." } }
        if (hostServices.isInitialized()) services.close()
        if (artifactStore.isInitialized()) artifacts.close()
        dataSource.close()
    }
}

internal val PLUGIN_FIXTURE_TOOLS = setOf("report", "state", "fetch", "fail", "block").map { "plugin.reference.$it" }.toSet()
internal fun runtimeServices(repository: PostgresPluginRepository): PluginServices =
    PluginServices(repository, null, ChannelProviderRegistry(emptyList()),
        ToolInvocationRuntimeSandboxResolver { error("The fixture must not resolve a sandbox.") }, null)
internal suspend fun PluginExecutionBinding.call(name: String, arguments: Map<String, Any> = emptyMap(), owner: String = "alice"): JsonNode {
    val tool = catalog.toolsByCategory.values.flatMap { it.values }.single { it.fn.name == "plugin.reference.$name" }
    return restJsonMapper.readTree(tool.invoke(LLMResponse.FunctionCall(tool.fn.name, arguments), ToolInvocationMeta(owner)).content)
}
internal fun JsonNode.errorCode(): String = path("error").path("code").asText()
internal suspend fun awaitPlugin(predicate: () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(10) }
