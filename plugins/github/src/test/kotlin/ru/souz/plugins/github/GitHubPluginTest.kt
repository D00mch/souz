package ru.souz.plugins.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import ru.souz.plugins.*
import ru.souz.plugins.runtime.PluginLease
import ru.souz.plugins.runtime.PluginRegistry

class GitHubPluginTest {
    @TempDir lateinit var directory: Path
    private val json = ObjectMapper()
    private lateinit var registry: PluginRegistry
    private lateinit var lease: PluginLease

    @BeforeTest fun loadJar() = runTest {
        Files.copy(Path.of(System.getProperty("github.plugin.jar")), directory.resolve("github.jar"))
        registry = PluginRegistry(directory, reportFailure = { _, error -> throw error })
        lease = registry.acquire(registry.installed().map { it.binding })
    }

    @AfterTest fun closeRegistry() = runTest {
        if (::lease.isInitialized) lease.close()
        if (::registry.isInitialized) registry.close()
    }

    @Test fun `standalone jar loads schemas and has no bundled SDK or runtime libraries`() = runTest {
        assertFailsWith<ClassNotFoundException> { Class.forName("ru.souz.plugins.github.GitHubPlugin") }
        val installed = registry.installed().single()
        assertEquals("github", installed.binding.id)
        assertEquals("1.0.0", installed.binding.version)
        assertEquals(13, installed.tools.size)
        assertTrue(installed.tools.all { it.startsWith("github__") })
        assertTrue(lease.tools.all { it.definition.category == "APPLICATIONS" })
        JarFile(directory.resolve("github.jar").toFile()).use { jar ->
            val classes = jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList()
            assertTrue(classes.isNotEmpty())
            assertTrue(classes.all { it.startsWith("ru/souz/plugins/github/") })
        }
    }

    @Test fun `read tools use fixed GET endpoints and preserve structured GitHub data`() = runTest {
        val routes = listOf(
            Triple("get_repository", "", ""),
            Triple("list_issues", "/issues?page=1&per_page=20&state=open", ""),
            Triple("get_issue", "/issues/42", "\"number\":42"),
            Triple("list_issue_comments", "/issues/42/comments?page=1&per_page=20", "\"number\":42"),
            Triple("list_pull_requests", "/pulls?page=1&per_page=20&state=open", ""),
            Triple("get_pull_request", "/pulls/42", "\"number\":42"),
            Triple("list_pull_files", "/pulls/42/files?page=1&per_page=20", "\"number\":42"),
            Triple("list_runs", "/actions/runs?page=1&per_page=20", ""),
            Triple("get_run", "/actions/runs/9876543210", "\"runId\":9876543210"),
            Triple("list_run_jobs", "/actions/runs/9876543210/jobs?page=1&per_page=20", "\"runId\":9876543210"),
            Triple("list_releases", "/releases?page=1&per_page=20", ""),
            Triple("get_latest_release", "/releases/latest", ""),
        )
        for ((name, suffix, arguments) in routes) {
            val body = when (name) {
                "list_runs" -> """{"total_count":1,"workflow_runs":[{"id":99,"conclusion":"failure"}]}"""
                "list_run_jobs" -> """{"total_count":1,"jobs":[{"id":7,"steps":[{"conclusion":"failure"}]}]}"""
                else -> if (name.startsWith("list_")) """[{"id":42,"title":"Example"}]""" else """{"id":42,"body":"Example"}"""
            }
            var calls = 0
            val result = call(name, arguments) { request ->
                calls++
                assertEquals("https://api.github.com/repos/octo/demo$suffix", request.url, name)
                assertEquals("GET", request.method)
                assertTrue(request.body.isEmpty())
                assertEquals(setOf("Accept", "X-GitHub-Api-Version", "User-Agent"), request.headers.keys)
                assertEquals(listOf("application/vnd.github+json"), request.headers["Accept"])
                response(body)
            }
            assertEquals(1, calls)
            assertEquals("ok", result.path("status").asText())
            assertEquals(json.readTree(body), result.path("data"), name)
            if (name.startsWith("list_")) assertTrue(result.path("nextPage").isNull)
        }
    }

    @Test fun `issue filtering preserves pagination even when the whole page contains PRs`() = runTest {
        val result = call("list_issues", """"state":"closed","labels":"bug,help wanted","page":2,"perPage":1""") { request ->
            assertTrue(request.url.endsWith("?page=2&per_page=1&state=closed&labels=bug%2Chelp+wanted"))
            response("""[{"number":1,"pull_request":{"url":"https://api.github.com/repos/octo/demo/pulls/1"}}]""",
                headers = mapOf("lInK" to listOf("<https://api.github.com/repos/octo/demo/issues?page=3>; rel=\"next\"")))
        }
        assertEquals(0, result.path("data").size())
        assertEquals(3, result.path("nextPage").asInt())
        val last = call("list_issues", "\"page\":3") {
            response("""[{"number":2},{"number":3,"pull_request":{}}]""")
        }
        assertEquals(listOf(2), last.path("data").map { it.path("number").asInt() })
        assertTrue(last.path("nextPage").isNull)
    }

    @Test fun `branch filters are encoded and link headers never become request URLs`() = runTest {
        var calls = 0
        val result = call("list_runs", """"branch":"feature/a&per_page=100#x","perPage":100""") { request ->
            calls++
            assertTrue(request.url.endsWith("page=1&per_page=100&branch=feature%2Fa%26per_page%3D100%23x"))
            response("""{"workflow_runs":[]}""", headers = mapOf("LINK" to listOf("<http://127.0.0.1/secret>; rel=\"next\"")))
        }
        assertEquals(1, calls)
        assertEquals(2, result.path("nextPage").asInt())
        assertFalse(result.toString().contains("127.0.0.1"))
    }

    @Test fun `pull checks include checks and legacy statuses from the same head SHA`() = runTest {
        val sha = "a".repeat(40)
        val paths = mutableListOf<String>()
        val result = call("get_pull_checks", "\"number\":7,\"page\":2") { request ->
            paths += request.url
            when (paths.size) {
                1 -> response("""{"head":{"sha":"$sha"}}""")
                2 -> response("""{"total_count":41,"check_runs":[{"conclusion":"failure"}]}""",
                    headers = mapOf("Link" to listOf("<https://api.github.com/next>; rel=\"next\"")))
                else -> response("""{"state":"pending","statuses":[]}""")
            }
        }
        assertEquals(listOf(
            "https://api.github.com/repos/octo/demo/pulls/7",
            "https://api.github.com/repos/octo/demo/commits/$sha/check-runs?page=2&per_page=20",
            "https://api.github.com/repos/octo/demo/commits/$sha/status?page=2&per_page=20",
        ), paths)
        assertEquals(sha, result.path("headSha").asText())
        assertEquals(3, result.path("checks").path("nextPage").asInt())
        assertTrue(result.path("commitStatus").path("nextPage").isNull)
        assertEquals("failure", result.path("checks").path("data").path("check_runs")[0].path("conclusion").asText())
        assertEquals("pending", result.path("commitStatus").path("data").path("state").asText())
    }

    @Test fun `HTTP failures are structured without disclosing remote bodies or following redirects`() = runTest {
        val cases = listOf(
            Triple(403, mapOf("x-ratelimit-remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("1800000000")), "rate_limited"),
            Triple(403, mapOf("Retry-After" to listOf("60")), "rate_limited"),
            Triple(429, emptyMap(), "rate_limited"),
            Triple(404, emptyMap(), "not_found"),
            Triple(403, emptyMap(), "api_error"),
            Triple(500, emptyMap(), "api_error"),
            Triple(301, mapOf("Location" to listOf("https://elsewhere.example")), "api_error"),
        )
        for ((status, headers, expected) in cases) {
            val result = call("get_repository") { response("remote sensitive detail", status, headers) }
            assertEquals(expected, result.path("status").asText())
            assertEquals(status, result.path("statusCode").asInt())
            assertFalse(result.toString().contains("sensitive"))
            headers["Retry-After"]?.let { assertEquals(60, result.path("retryAfterSeconds").asInt()) }
            headers["X-RateLimit-Reset"]?.let { assertEquals(1800000000L, result.path("rateLimitReset").asLong()) }
        }
    }

    @Test fun `invalid arguments fail before any network access`() = runTest {
        val cases = listOf(
            "list_issues" to "\"perPage\":0", "list_issues" to "\"perPage\":101",
            "list_issues" to "\"page\":2147483647", "list_issues" to "\"page\":1.5",
            "list_issues" to "\"state\":\"merged\"", "list_issues" to "\"labels\":null",
            "get_issue" to "", "get_issue" to "\"number\":\"42\"", "get_issue" to "\"number\":-1",
            "get_run" to "\"runId\":9223372036854775808", "get_repository" to "\"token\":\"secret\"",
        )
        for ((name, arguments) in cases) {
            val failure = assertFailsWith<PluginException> { call(name, arguments) { error("Unexpected request") } }
            assertEquals("github_invalid_input", failure.code, arguments)
        }
        for (repository in listOf("https://github.com/octo/demo", "octo/..", "octo/.", "octo/demo/issues", "octo/demo?x=y", "octo/%2e%2e", "octo/demo#x", "", " octo/demo")) {
            val failure = assertFailsWith<PluginException> {
                call("get_repository", repository = repository) { error("Unexpected request") }
            }
            assertEquals("github_invalid_input", failure.code, repository)
        }
    }

    @Test fun `malformed responses and unsafe head references fail without leaking bodies`() = runTest {
        for (body in listOf("not JSON", "null", "42", "{} {}", "", "{\"private\": invalid}")) {
            val failure = assertFailsWith<PluginException> { call("get_repository") { response(body) } }
            assertEquals("github_invalid_response", failure.code)
            assertFalse(failure.message.orEmpty().contains("private"))
        }
        for ((tool, arguments, body) in listOf(
            Triple("list_issues", "", "{}"),
            Triple("list_runs", "", "{}"),
            Triple("list_run_jobs", "\"runId\":1", "{}"),
            Triple("get_pull_checks", "\"number\":1", """{"head":{"sha":"../../elsewhere"}}"""),
        )) {
            var calls = 0
            assertFailsWith<PluginException> { call(tool, arguments) { calls++; response(body) } }
            assertEquals(1, calls)
        }
    }

    @Test fun `check collection stops on API failure at any stage`() = runTest {
        for (failedCall in 1..3) {
            var calls = 0
            val result = call("get_pull_checks", "\"number\":1") {
                calls++
                when {
                    calls == failedCall -> response("unavailable", 429)
                    calls == 1 -> response("""{"head":{"sha":"${"b".repeat(40)}"}}""")
                    else -> response("""{"check_runs":[]}""")
                }
            }
            assertEquals(failedCall, calls)
            assertEquals("rate_limited", result.path("status").asText())
            assertFalse(result.has("checks"))
        }
    }

    @Test fun `network failures are sanitized while cancellation propagates`() = runTest {
        val error = assertFailsWith<PluginException> { call("get_repository") { throw IllegalStateException("sensitive network detail") } }
        assertEquals("github_http_failed", error.code)
        assertFalse(error.message.orEmpty().contains("sensitive"))
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, assertFailsWith<CancellationException> { call("get_repository") { throw cancellation } })
    }

    private suspend fun call(
        name: String,
        fields: String = "",
        repository: String = "octo/demo",
        http: suspend (PluginHttpRequest) -> PluginHttpResponse,
    ): JsonNode {
        val arguments = (json.readTree("{$fields}") as ObjectNode).put("repository", repository)
        val context = object : PluginCallContext {
            override val data: PluginData get() = error("Public reads must not access user state")
            override val http = PluginHttp(http)
            override val logger = PluginLogger { error("Public reads need no user-data logging") }
        }
        return lease.tools.single { it.name == "github__$name" }.handler.invoke(arguments, context)
    }

    private fun response(body: String, status: Int = 200, headers: Map<String, List<String>> = emptyMap()) =
        PluginHttpResponse(status, headers, body.toByteArray())
}
