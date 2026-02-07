package agent

import giga.getHttpClient
import giga.getSessionTokenUsage
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.gigadesk.agent.DEFAULT_SYSTEM_PROMPT
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.giga.objectMapper
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.db.SettingsProviderImpl
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.giga.GigaRestChatAPI
import ru.gigadesk.tool.files.FilesToolUtil
import java.util.concurrent.atomic.AtomicLong

abstract class GraphAgentTestBase {

    protected val spySettings: SettingsProviderImpl by lazy {
        spyk(SettingsProviderImpl(ConfigStore)) {
            every { forbiddenFolders } returns emptyList()
            every { useGrpc } returns false
            every { gigaModel } returns GigaModel.Lite
            every { temperature } returns 0.1f
            every { systemPrompt } returns DEFAULT_SYSTEM_PROMPT
        }
    }

    protected val filesUtil: FilesToolUtil by lazy { FilesToolUtil(spySettings) }

    protected val testOverrideModule: DI.Module = DI.Module("TestOverrideModule") {
        bindSingleton<SettingsProvider>(overrides = true) { spySettings }
        bindSingleton<FilesToolUtil>(overrides = true) { filesUtil }
        bindSingleton(overrides = true) {
            if (gigaRestChatAPI == null) {
                gigaRestChatAPI = GigaRestChatAPI(instance(), instance()).apply {
                    getHttpClient().plugin(HttpSend).intercept { request ->
                        val startNanos = System.nanoTime()
                        try {
                            execute(request)
                        } finally {
                            httpRequestCount.incrementAndGet()
                            httpRequestTotalNanos.addAndGet(System.nanoTime() - startNanos)
                        }
                    }
                }
            }
            gigaRestChatAPI!!
        }
    }

    @BeforeEach
    fun checkEnvironment() {
        val apiKey = System.getenv("GIGA_KEY") ?: System.getProperty("GIGA_KEY")
        Assumptions.assumeTrue(!apiKey.isNullOrBlank(), "Skipping integration tests: GIGA_KEY is not set")
    }

    protected suspend fun runScenarioWithMocks(
        userPrompt: String,
        overrides: DI.MainBuilder.() -> Unit,
    ) {
        val mockDesktopInfoRepository: DesktopInfoRepository = mockk(relaxed = true)
        coEvery { mockDesktopInfoRepository.search(any()) } returns emptyList()

        val di = DI.invoke(allowSilentOverride = true) {
            import(mainDiModule)
            import(testOverrideModule, allowOverride = true)
            bindProvider<DI> { this.di }

            bindSingleton<DesktopInfoRepository> { mockDesktopInfoRepository }
            overrides()
        }
        val agent = GraphBasedAgent(di, objectMapper)
        agent.execute(userPrompt)
    }

    companion object {
        private var gigaRestChatAPI: GigaRestChatAPI? = null
        private val httpRequestCount = AtomicLong(0)
        private val httpRequestTotalNanos = AtomicLong(0)

        @JvmStatic
        @AfterAll
        fun finish() {
            val gigaRestChatAPI = gigaRestChatAPI ?: return
            println("Spent: ${gigaRestChatAPI.getSessionTokenUsage()}")
            val requestCount = httpRequestCount.get()
            if (requestCount == 0L) {
                println("HTTP requests: 0")
                return
            }
            val avgMs = httpRequestTotalNanos.get().toDouble() / requestCount / 1_000_000.0
            println("HTTP requests: $requestCount, avg/request: ${"%.2f".format(avgMs)} ms")
        }
    }
}
