package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.test.runTest
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationLevel
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.http.routeTestContext
import ru.souz.backend.skills.BackendSkillBundleProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.codex.CodexOAuthCredentials
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresDisposableBackendStateIntegrationTest {
    @Test
    fun `independent backend stores survive complete scratch loss through PostgreSQL`() = runTest {
        val schema = newPostgresSchema("postgres_disposable_backend_state")
        val firstScratch = createTempDirectory("souz-first-pod-scratch-")
        val secondScratch = createTempDirectory("souz-second-pod-scratch-")
        val chatId = UUID.randomUUID()
        val meta = ToolInvocationMeta(userId = USER_ID, conversationId = chatId.toString())
        val userBundle = durableUserSkillBundle()
        lateinit var storedKnowledge: KnowledgeEntry
        lateinit var validation: SkillValidationRecord

        try {
            assertNotEquals(firstScratch, secondScratch)
            firstScratch.resolve("ephemeral-marker.txt").writeText("first pod scratch")
            secondScratch.resolve("stale-marker.txt").writeText("stale second pod scratch")

            durableStores(schema, firstScratch).use { firstPod ->
                assertEquals(firstScratch, firstPod.scratchRoot)
                firstPod.users.ensureUser(USER_ID)
                firstPod.chats.create(chat(chatId))

                val storedSkill = firstPod.userSkills.saveSkillBundle(USER_ID, userBundle)
                validation = skillValidation(userBundle.skillId, storedSkill.bundleHash)
                firstPod.userSkills.saveValidation(validation)
                storedKnowledge = firstPod.knowledge
                    .put(meta, "DurabilityTool", KNOWLEDGE_CONTENT)
                    .storedEntry()
                assertTrue(firstPod.credentials.compareAndSet(null, CREDENTIALS))
            }

            replaceWithEmptyDirectory(firstScratch)
            replaceWithEmptyDirectory(secondScratch)

            durableStores(schema, secondScratch).use { secondPod ->
                assertEquals(secondScratch, secondPod.scratchRoot)
                val loadedUserBundle = assertNotNull(
                    secondPod.userSkills.loadSkillBundle(USER_ID, userBundle.skillId)
                )
                assertEquals(userBundle.manifest, loadedUserBundle.manifest)
                assertContentEquals(
                    userBundle.files.single { it.normalizedPath == BINARY_PATH }.content,
                    loadedUserBundle.files.single { it.normalizedPath == BINARY_PATH }.content,
                )
                assertEquals(
                    validation,
                    secondPod.userSkills.getValidation(
                        userId = validation.userId,
                        skillId = validation.skillId,
                        bundleHash = validation.bundleHash,
                        policyVersion = validation.policyVersion,
                    ),
                )
                assertEquals(storedKnowledge, secondPod.knowledge.get(meta, storedKnowledge.id))
                assertEquals(CREDENTIALS, secondPod.credentials.load())

                val builtInSkillId = SkillId(BUILT_IN_SKILL_ID)
                assertNull(secondPod.userSkills.loadSkillBundle(USER_ID, builtInSkillId))
                val builtIn = assertNotNull(
                    secondPod.composedSkills.loadSkillBundle(USER_ID, builtInSkillId)
                )
                assertEquals(builtInSkillId, builtIn.skillId)
                assertEquals(0, postgresRegistrationCount(secondPod.dataSource, builtInSkillId))

                val encryptedPayload = rawCredentialPayload(secondPod.dataSource)
                assertTrue(encryptedPayload.startsWith("enc:v1:"))
                assertFalse(encryptedPayload.contains(CREDENTIALS.accessToken))
                assertFalse(encryptedPayload.contains(requireNotNull(CREDENTIALS.refreshToken)))
                assertFalse(encryptedPayload.contains(requireNotNull(CREDENTIALS.accountId)))
                assertDirectoryIsEmpty(firstScratch)
                assertDirectoryIsEmpty(secondScratch)
            }
        } finally {
            firstScratch.toFile().deleteRecursively()
            secondScratch.toFile().deleteRecursively()
        }
    }

    private fun durableStores(schema: String, scratchRoot: Path): DurableStoreSet {
        require(Files.isDirectory(scratchRoot)) { "Scratch root must exist for a backend store set." }
        return DurableStoreSet(
            dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres),
            scratchRoot = scratchRoot,
        )
    }

    private fun replaceWithEmptyDirectory(directory: Path) {
        assertTrue(directory.toFile().deleteRecursively())
        Files.createDirectories(directory)
        assertDirectoryIsEmpty(directory)
    }

    private fun assertDirectoryIsEmpty(directory: Path) {
        assertTrue(Files.isDirectory(directory))
        Files.list(directory).use { entries ->
            assertFalse(entries.findAny().isPresent)
        }
    }

    private suspend fun postgresRegistrationCount(
        dataSource: HikariDataSource,
        skillId: SkillId,
    ): Int = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select count(*)
            from user_skill_registrations
            where user_id = ? and skill_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, USER_ID)
            statement.setString(2, skillId.value)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }
    }

    private suspend fun rawCredentialPayload(dataSource: HikariDataSource): String =
        dataSource.read { connection ->
            connection.prepareStatement(
                """
                select encrypted_payload
                from backend_codex_oauth_credentials
                where singleton = true
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getBytes(1).toString(Charsets.UTF_8)
                }
            }
        }

    private fun chat(chatId: UUID): Chat = Chat(
        id = chatId,
        userId = USER_ID,
        title = "Disposable backend durability",
        archived = false,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun durableUserSkillBundle(): SkillBundle = SkillBundle.fromFiles(
        skillId = SkillId(USER_SKILL_ID),
        files = listOf(
            SkillFile(
                normalizedPath = "SKILL.md",
                content = """
                    ---
                    name: $USER_SKILL_ID
                    description: Verify PostgreSQL durability.
                    ---
                    Read this user Skill after all local scratch is deleted.
                """.trimIndent().toByteArray(),
            ),
            SkillFile(BINARY_PATH, byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())),
        ),
    )

    private fun skillValidation(skillId: SkillId, bundleHash: String): SkillValidationRecord =
        SkillValidationRecord(
            userId = USER_ID,
            skillId = skillId,
            bundleHash = bundleHash,
            policyVersion = POLICY_VERSION,
            approved = true,
            findings = listOf(
                SkillValidationFinding(
                    code = "durable",
                    message = "Stored in PostgreSQL",
                    level = SkillValidationLevel.INFO,
                    filePath = "SKILL.md",
                )
            ),
            createdAt = NOW,
        )

    private fun KnowledgeWriteResult.storedEntry(): KnowledgeEntry =
        assertIs<KnowledgeWriteResult.Stored>(this).entry

    private class DurableStoreSet(
        val dataSource: HikariDataSource,
        val scratchRoot: Path,
    ) : AutoCloseable {
        private val fixedClock = Clock.fixed(NOW, ZoneOffset.UTC)
        private val clientContext = routeTestContext()
        private val clientSkills = BackendClientSkills(
            registry = clientContext.clientThreadRegistry,
            toolCallRepository = clientContext.toolCallRepository,
            eventService = clientContext.eventService,
        )
        val users = PostgresUserRepository(dataSource)
        val chats = PostgresChatRepository(dataSource)
        val userSkills = PostgresSkillRegistryRepository(
            dataSource = dataSource,
            builtInSkillBundleHashes = clientSkills.bundleHashesBySkillId,
            clock = fixedClock,
        )
        val knowledge = PostgresConversationKnowledgeStore(dataSource)
        val credentials = PostgresCodexOAuthCredentialStore(
            dataSource = dataSource,
            masterKey = MASTER_KEY,
        )
        val composedSkills = BackendSkillBundleProvider(
            resourceSkills = clientSkills,
            userSkills = userSkills,
        )

        init {
            require(Files.isDirectory(scratchRoot)) { "Scratch root must exist." }
        }

        override fun close() {
            dataSource.close()
        }
    }

    private companion object {
        const val USER_ID = "disposable-state-user"
        const val USER_SKILL_ID = "durable-user-skill"
        const val BUILT_IN_SKILL_ID = "user.ask"
        const val BINARY_PATH = "assets/payload.bin"
        const val POLICY_VERSION = "skills-policy/disposable-v1"
        const val MASTER_KEY = "disposable-state-test-master-key"
        const val KNOWLEDGE_CONTENT = "Knowledge remains after both pod scratch directories disappear."
        val NOW: Instant = Instant.parse("2026-08-12T15:00:00Z")
        val CREDENTIALS = CodexOAuthCredentials(
            accessToken = "durable-access-token",
            refreshToken = "durable-refresh-token",
            accountId = "durable-account-id",
            expiresAtEpochSeconds = 1_900_000_000L,
            version = 0L,
        )
    }
}
