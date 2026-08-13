package ru.souz.backend.skills

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationLevel
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresSkillRegistryRepository
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig

class PostgresSkillRegistryRepositoryTest {
    @Test
    fun `independent repositories share binary bundles and isolate tenants`() = runTest {
        val schema = newPostgresSchema("postgres_skills_binary")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)
        dataSource.use {
            val users = PostgresUserRepository(it)
            users.ensureUser(USER_A)
            users.ensureUser(USER_B)
            val first = repository(it)
            val second = repository(it)
            val binary = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())
            val bundle = bundle("binary-skill", "first", binary)

            val stored = first.saveSkillBundle(USER_A, bundle)
            val loaded = assertNotNull(second.loadSkillBundle(USER_A, bundle.skillId))

            assertEquals(SkillBundleHasher.hash(bundle), stored.bundleHash)
            assertEquals(bundle.manifest, loaded.manifest)
            assertContentEquals(
                binary,
                loaded.files.single { file -> file.normalizedPath == "assets/payload.bin" }.content,
            )
            assertEquals(listOf(bundle.skillId), second.listSkillInventoryIds(USER_A))
            assertNull(second.loadSkillBundle(USER_B, bundle.skillId))
            assertTrue(second.listSkills(USER_B).isEmpty())
        }
    }

    @Test
    fun `validation cache uses user skill hash and policy version`() = runTest {
        val schema = newPostgresSchema("postgres_skill_validations")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)
        dataSource.use {
            val users = PostgresUserRepository(it)
            users.ensureUser(USER_A)
            users.ensureUser(USER_B)
            val repository = repository(it)
            val firstBundle = bundle("validated", "first")
            val secondBundle = bundle("validated", "second")
            val firstHash = repository.saveSkillBundle(USER_A, firstBundle).bundleHash
            val secondHash = repository.saveSkillBundle(USER_A, secondBundle).bundleHash
            repository.saveSkillBundle(USER_B, firstBundle)
            val record = validation(USER_A, firstBundle.skillId, firstHash, POLICY_A)
            repository.saveValidation(record)

            assertEquals(
                record,
                repository.getValidation(USER_A, firstBundle.skillId, firstHash, POLICY_A),
            )
            assertNull(repository.getValidation(USER_B, firstBundle.skillId, firstHash, POLICY_A))
            assertNull(repository.getValidation(USER_A, firstBundle.skillId, secondHash, POLICY_A))
            assertNull(repository.getValidation(USER_A, firstBundle.skillId, firstHash, POLICY_B))
            assertNull(repository.getValidation(USER_A, SkillId("different"), firstHash, POLICY_A))
        }
    }

    @Test
    fun `resource validation does not require a PostgreSQL bundle copy`() = runTest {
        val schema = newPostgresSchema("postgres_resource_validation")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)
        dataSource.use {
            PostgresUserRepository(it).ensureUser(USER_A)
            val repository = repository(it)
            val record = validation(
                userId = USER_A,
                skillId = SkillId("resource-skill"),
                bundleHash = "a".repeat(64),
                policyVersion = POLICY_A,
            )

            repository.saveValidation(record)

            assertEquals(
                record,
                repository.getValidation(
                    USER_A,
                    record.skillId,
                    record.bundleHash,
                    record.policyVersion,
                ),
            )
            dataSource.connection.use { connection ->
                connection.prepareStatement("select count(*) from skill_bundles").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(0, resultSet.getInt(1))
                    }
                }
            }
        }
    }

    @Test
    fun `concurrent registration replacements expose one complete immutable bundle`() = runTest {
        val schema = newPostgresSchema("postgres_skill_concurrency")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)
        dataSource.use { sharedDataSource ->
            PostgresUserRepository(sharedDataSource).ensureUser(USER_A)
            val repositories = List(4) { repository(sharedDataSource) }
            val candidates = (0 until 16).map { index ->
                bundle("concurrent", "revision-$index", byteArrayOf(index.toByte(), 0, (-index).toByte()))
            }

            coroutineScope {
                candidates.mapIndexed { index, candidate ->
                    async(Dispatchers.Default) {
                        repositories[index % repositories.size].saveSkillBundle(USER_A, candidate)
                    }
                }.awaitAll()
            }

            val loaded = assertNotNull(repositories.first().loadSkillBundle(USER_A, SkillId("concurrent")))
            assertTrue(candidates.any { candidate -> candidate == loaded })
            assertEquals(
                SkillBundleHasher.hash(loaded),
                repositories.last().listSkills(USER_A).single().bundleHash,
            )
        }
    }

    @Test
    fun `repository enforces skill path size and hash contracts before persistence`() = runTest {
        val schema = newPostgresSchema("postgres_skill_contracts")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)
        dataSource.use {
            PostgresUserRepository(it).ensureUser(USER_A)
            val repository = repository(it)
            val valid = bundle("contract-skill", "valid")
            val unsafePath = valid.copy(
                files = valid.files + SkillFile("../escape.bin", byteArrayOf(1)),
            )
            val oversized = valid.copy(
                files = valid.files.map { file ->
                    if (file.normalizedPath == "assets/payload.bin") {
                        file.copy(content = ByteArray(128 * 1024 + 1))
                    } else {
                        file
                    }
                },
            )

            assertFailsWith<SkillBundleException> {
                repository.saveSkillBundle(USER_A, unsafePath)
            }
            assertFailsWith<SkillBundleException> {
                repository.saveSkillBundle(USER_A, oversized)
            }
            assertFailsWith<IllegalArgumentException> {
                repository.saveValidation(
                    validation(USER_A, valid.skillId, "not-a-sha256", POLICY_A)
                )
            }
            assertTrue(repository.listSkills(USER_A).isEmpty())
        }
    }

    private fun repository(dataSource: javax.sql.DataSource) = PostgresSkillRegistryRepository(
        dataSource = dataSource,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun validation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ) = SkillValidationRecord(
        userId = userId,
        skillId = skillId,
        bundleHash = bundleHash,
        policyVersion = policyVersion,
        approved = true,
        findings = listOf(
            SkillValidationFinding(
                code = "ok",
                message = "Validated",
                level = SkillValidationLevel.INFO,
                filePath = "SKILL.md",
            )
        ),
        createdAt = NOW,
    )

    private companion object {
        const val USER_A = "skill-user-a"
        const val USER_B = "skill-user-b"
        const val POLICY_A = "skills-policy/v1"
        const val POLICY_B = "skills-policy/v2"
        val NOW: Instant = Instant.parse("2026-08-12T12:00:00Z")
    }
}

internal fun bundle(
    skillId: String,
    revision: String,
    binary: ByteArray = byteArrayOf(9, 8, 7),
): SkillBundle = SkillBundle.fromFiles(
    skillId = SkillId(skillId),
    files = listOf(
        SkillFile(
            normalizedPath = "SKILL.md",
            content = """
                ---
                name: $skillId
                description: $revision description
                ---
                $revision instructions.
            """.trimIndent().toByteArray(),
        ),
        SkillFile("assets/payload.bin", binary),
    ),
)
