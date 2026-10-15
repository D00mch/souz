package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.SharedPostgresContainer
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper

class BackendEnabledToolsE2eTest {
    @Test
    fun `default tool selection follows advertised tools after a restart while explicit selections stay`() {
        val schema = newPostgresSchema("e2e_enabled_tools")
        val defaultUser = UUID.randomUUID().toString()
        val allUser = UUID.randomUUID().toString()
        val explicitUser = UUID.randomUUID().toString()
        val noneUser = UUID.randomUUID().toString()
        val explicit = setOf("ListFiles", "ViewImage")
        lateinit var advertised: Set<String>

        backendE2eTest("e2e_enabled_tools_first", schema = schema) {
            advertised = advertisedTools(defaultUser)
            assertEquals(advertised, enabledTools(defaultUser))
            assertEquals(advertised, patchEnabledTools(allUser, enabledTools(allUser)))
            assertEquals(explicit, patchEnabledTools(explicitUser, explicit))
            assertEquals(emptySet(), patchEnabledTools(noneUser, emptySet()))
        }

        // Configuring Skill OAuth adds advertised tools the way a release with new tools does.
        backendE2eTest("e2e_enabled_tools_next", schema = schema, skillOAuth = true) {
            val next = advertised + setOf("ConnectOAuthProvider", "SafeApiCall")
            assertEquals(next, advertisedTools(defaultUser))
            assertEquals(next, enabledTools(defaultUser))
            assertEquals(next, enabledTools(allUser))
            assertEquals(explicit, enabledTools(explicitUser))
            assertEquals(emptySet(), enabledTools(noneUser))
            assertEquals(listOf("- OAUTH: ConnectOAuthProvider, SafeApiCall"), turnInventory(defaultUser, "OAUTH"))
            assertEquals(listOf("- FILES: ListFiles"), turnInventory(explicitUser, "FILES", "OAUTH"))
        }
    }

    @Test
    fun `migration turns legacy default snapshots into the default and keeps explicit selections`() {
        val schema = newPostgresSchema("e2e_legacy_tools")
        val container = SharedPostgresContainer.instance
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .defaultSchema(schema)
            .schemas(schema)
            .target("17")
            .load()
            .migrate()
        val legacy = mapOf(
            "legacy-may" to FIRST_RELEASE + setOf("InternetSearch", "InternetResearch", "WebPageText"),
            // Also what a user who deselected the then-newest ReadFile stored; the two are indistinguishable.
            "legacy-august" to FIRST_RELEASE + IMAGES + CHANNELS + setOf("ConnectOAuthProvider", "SafeApiCall"),
            "legacy-october" to FIRST_RELEASE + IMAGES + CHANNELS + "ReadFile" + SCHEDULED_TASKS,
            "deselected-older" to FIRST_RELEASE + "ViewImage" + CHANNELS + "ReadFile",
            "deselected-newest" to FIRST_RELEASE + IMAGES + CHANNELS + "ReadFile" + "scheduled-task.create",
            "selected-few" to setOf("ListFiles", "ViewImage"),
            "selected-none" to emptySet(),
            "selected-removed" to setOf("InternetSearch"),
        )
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            legacy.forEach { (userId, tools) ->
                connection.prepareStatement("insert into $schema.users(id) values (?)").use { statement ->
                    statement.setString(1, userId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "insert into $schema.user_settings(user_id, settings_json) values (?, ?::jsonb)"
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.setString(2, restJsonMapper.writeValueAsString(mapOf("enabledTools" to tools.sorted())))
                    statement.executeUpdate()
                }
            }
        }

        // OAuth tools are advertised after the migration, so only rows that follow the default gain them.
        backendE2eTest("e2e_legacy_tools", schema = schema, skillOAuth = true) {
            val advertised = advertisedTools("legacy-may")
            val kept = { userId: String -> legacy.getValue(userId) intersect advertised }
            assertEquals(
                mapOf(
                    "legacy-may" to advertised,
                    "legacy-august" to advertised,
                    "legacy-october" to advertised,
                    "deselected-older" to kept("deselected-older"),
                    "deselected-newest" to kept("deselected-newest"),
                    "selected-few" to kept("selected-few"),
                    "selected-none" to emptySet(),
                    "selected-removed" to emptySet(),
                ),
                legacy.keys.associateWith { enabledTools(it) },
            )
        }
    }

    private suspend fun BackendE2eScope.advertisedTools(userId: String): Set<String> =
        client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(userId) }
            .jsonBody()["capabilities"]["tools"].mapTo(mutableSetOf()) { it["name"].asText() }

    private suspend fun BackendE2eScope.enabledTools(userId: String): Set<String> =
        client.get(BackendHttpRoutes.SETTINGS) { trusted(userId) }
            .jsonBody()["settings"]["enabledTools"].mapTo(mutableSetOf()) { it.asText() }

    private suspend fun BackendE2eScope.patchEnabledTools(userId: String, tools: Set<String>): Set<String> {
        val patched = client.patch(BackendHttpRoutes.SETTINGS) {
            trusted(userId)
            jsonBody("""{"enabledTools":${restJsonMapper.writeValueAsString(tools)}}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        return patched.jsonBody()["settings"]["enabledTools"].mapTo(mutableSetOf()) { it.asText() }
    }

    /** Runs one turn and returns its Skill inventory lines for the given tool categories. */
    private suspend fun BackendE2eScope.turnInventory(userId: String, vararg categories: String): List<String> {
        val chatId = createPublicChat(userId)
        val prompt = "inventory for $userId"
        val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
            trusted(userId)
            jsonBody("""{"content":"$prompt","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
        }
        assertEquals(HttpStatusCode.OK, sent.status)
        val request = eventually("LLM request for $userId") {
            llm.requests.firstOrNull { request -> request.messages.any { it.content == prompt } }
        }
        return request.messages.first { it.role == LLMMessageRole.system }.content.lines()
            .filter { line -> categories.any { line.startsWith("- $it:") } }
    }

    private companion object {
        val FIRST_RELEASE = setOf(
            "Calculator", "CreatePlot", "DeleteFile", "EditFile", "ExcelRead", "ExcelReport", "ExtractTextFromFile",
            "FindFilesByName", "FindFolders", "ListFiles", "MoveFile", "NewFile", "ReadPdfPages", "SearchFileContent",
        )
        val IMAGES = setOf("GenerateImage", "ViewImage")
        val CHANNELS = setOf("ListActiveChannels", "SendMessageToChannel")
        val SCHEDULED_TASKS = setOf("scheduled-task.create", "scheduled-task.delete", "scheduled-task.list")
    }
}
