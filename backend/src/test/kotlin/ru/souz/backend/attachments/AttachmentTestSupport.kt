package ru.souz.backend.attachments

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import ru.souz.backend.app.backendDiModule
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.backend.storage.postgres.write

internal object AttachmentMinio {
    // Public source build; pin the multi-platform index for both CI (amd64) and local arm64.
    // Build recipe: https://github.com/coollabsio/minio
    private const val IMAGE = "ghcr.io/coollabsio/minio:RELEASE.2025-10-15T17-29-55Z" +
        "@sha256:69b55a1c1c5dc285ce04db96689f5b2102317fc77a50680a1874ca6efd1c87f9"

    val server = GenericContainer<Nothing>(IMAGE).apply {
        withEnv("MINIO_ROOT_USER", "attachment-test")
        withEnv("MINIO_ROOT_PASSWORD", "attachment-test-secret")
        withCommand("server", "/data")
        withExposedPorts(9000)
        waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
        start()
        check(execInContainer("mc", "alias", "set", "root", "http://localhost:9000", "attachment-test", "attachment-test-secret").exitCode == 0)
    }

    fun provision(config: AttachmentStorageConfig) {
        val policy = """{"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],
            "Resource":["arn:aws:s3:::${config.bucket}/*"]}]}"""
        val path = "/tmp/${config.bucket}.json"
        server.copyFileToContainer(Transferable.of(policy), path)
        for (args in listOf(
            listOf("mb", "root/${config.bucket}"),
            listOf("admin", "user", "add", "root", config.accessKey, config.secretKey),
            listOf("admin", "policy", "create", "root", config.bucket, path),
            listOf("admin", "policy", "attach", "root", config.bucket, "--user", config.accessKey),
            listOf("alias", "set", config.accessKey, "http://localhost:9000", config.accessKey, config.secretKey),
        )) {
            val result = server.execInContainer("mc", *args.toTypedArray())
            check(result.exitCode == 0) { "MinIO test provisioning failed: ${result.stderr}" }
        }
    }
}

internal class AttachmentClock(var now: Instant = Instant.parse("2026-10-13T00:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

internal fun attachmentTest(maxConcurrentUploads: Int = 16, block: suspend AttachmentFixture.() -> Unit) = runBlocking {
    AttachmentFixture(maxConcurrentUploads).use { fixture ->
        fixture.block()
    }
}

internal class AttachmentFixture(maxConcurrentUploads: Int) : AutoCloseable {
    private val appConfig = postgresAppConfig(newPostgresSchema("attachments"))
    val dataSource = PostgresDataSourceFactory.create(appConfig.postgres)
    val clock = AttachmentClock()
    private val storageId = UUID.randomUUID().toString()
    val config = AttachmentStorageConfig(
        endpoint = "http://${AttachmentMinio.server.host}:${AttachmentMinio.server.firstMappedPort}",
        bucket = "attachments-$storageId",
        accessKey = "user-$storageId", secretKey = "attachment-user-secret", maxFileBytes = 1024,
        maxConcurrentUploads = maxConcurrentUploads,
    )
    init { AttachmentMinio.provision(config) }

    val di = DI {
        import(backendDiModule("Attachment test", appConfig.copy(attachments = config), dataSourceFactory = { dataSource }))
        bindSingleton<Clock>(overrides = true) { clock }
    }.direct
    val storage: AttachmentStorage = di.instance()
    val objects: S3AttachmentObjects = di.instance()
    val repository: PostgresAttachmentRepository = di.instance()
    suspend fun save(user: String, key: String, name: String, text: String) = storage.save(user, key, name, text.byteInputStream())
    suspend fun execute(sql: String) = dataSource.write { it.createStatement().use { statement -> statement.execute(sql) } }
    override fun close() { objects.close(); dataSource.close(); di.instance<BackendMetrics>().close() }
}
