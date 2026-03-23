package ru.souz.tool.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaChatAPI
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ToolSetup
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.web.internal.InternetSearchCollectedSource
import ru.souz.tool.web.internal.InternetSearchCollectionResult
import ru.souz.tool.web.internal.InternetSearchInternals
import ru.souz.tool.web.internal.InternetSearchResearchStrategy
import ru.souz.tool.web.internal.InternetSearchSynthesisResult
import ru.souz.tool.web.internal.WebResearchClient
import ru.souz.tool.web.internal.WebSearchProviderException
import ru.souz.tool.web.internal.WebSearchProviderFailureKind
import java.io.File
import java.nio.charset.StandardCharsets

class ToolInternetSearch internal constructor(
    private val api: GigaChatAPI,
    private val settingsProvider: SettingsProvider,
    private val webResearchClient: WebResearchClient,
    private val filesToolUtil: FilesToolUtil,
    private val mapper: ObjectMapper,
    private val internals: InternetSearchInternals,
) : ToolSetup<ToolInternetSearch.Input> {
    private val l = LoggerFactory.getLogger(ToolInternetSearch::class.java)

    constructor(
        api: GigaChatAPI,
        settingsProvider: SettingsProvider,
        filesToolUtil: FilesToolUtil,
        mapper: ObjectMapper = gigaJsonMapper,
    ) : this(
        api = api,
        settingsProvider = settingsProvider,
        webResearchClient = WebResearchClient(mapper = mapper),
        filesToolUtil = filesToolUtil,
        mapper = mapper,
        internals = InternetSearchInternals(
            mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        ),
    )

    internal constructor(
        api: GigaChatAPI,
        settingsProvider: SettingsProvider,
        webResearchClient: WebResearchClient,
        filesToolUtil: FilesToolUtil,
        mapper: ObjectMapper = gigaJsonMapper,
    ) : this(
        api = api,
        settingsProvider = settingsProvider,
        webResearchClient = webResearchClient,
        filesToolUtil = filesToolUtil,
        mapper = mapper,
        internals = InternetSearchInternals(
            mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        ),
    )

    enum class OutputStatus {
        COMPLETE,
        PARTIAL,
        NO_RESULTS,
        PROVIDER_BLOCKED,
        PROVIDER_UNAVAILABLE,
    }

    enum class SearchMode {
        QUICK_ANSWER,
        RESEARCH,
    }

    data class Input(
        @InputParamDescription("User's internet search request")
        val query: String,
        @InputParamDescription("Optional mode. QUICK_ANSWER for one direct factual answer. RESEARCH for multi-source research, comparison, tool/library selection, or thematic review.")
        val mode: SearchMode? = null,
        @InputParamDescription("Maximum number of source pages to study (1..16). QUICK_ANSWER usually needs 1..3, RESEARCH should usually use 10..16.")
        val maxSources: Int = 10,
    )

    data class Output(
        val status: String,
        val mode: String,
        val query: String,
        val answer: String,
        val reportMarkdown: String,
        val reportFilePath: String?,
        val sources: List<OutputSource>,
        val strategy: OutputStrategy?,
    )

    data class OutputSource(
        val index: Int,
        val title: String,
        val url: String,
        val foundByQuery: String,
        val snippet: String,
    )

    data class OutputStrategy(
        val goal: String,
        val searchQueries: List<String>,
        val subQuestions: List<String>,
        val answerSections: List<String>,
    )

    override val name: String = "InternetSearch"
    override val description: String = internals.description
    override val fewShotExamples: List<FewShotExample> = internals.fewShotExamples
    override val returnParameters: ReturnParameters = internals.returnParameters

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val query = internals.requireWebQuery(input.query)
        val mode = input.mode ?: internals.inferMode(query)
        val output = when (mode) {
            SearchMode.QUICK_ANSWER -> runQuickAnswer(query, input.maxSources)
            SearchMode.RESEARCH -> runResearch(query, input.maxSources)
        }
        return mapper.writeValueAsString(output)
    }

    private suspend fun runQuickAnswer(query: String, maxSources: Int): Output {
        val collection = collectSources(
            searchQueries = listOf(query),
            maxSources = maxSources.coerceIn(1, 3),
            resultsPerQuery = 5,
            pageCharLimit = internals.quickPageTextLimit,
        )
        if (collection.sources.isEmpty()) {
            return buildEmptySourcesOutput(
                query = query,
                mode = SearchMode.QUICK_ANSWER,
                strategy = null,
                status = collection.providerStatus ?: OutputStatus.NO_RESULTS,
            )
        }
        val sources = collection.sources

        val synthesis = synthesizeAnswer(query, SearchMode.QUICK_ANSWER, sources, null)
        return internals.buildOutput(
            query = query,
            mode = SearchMode.QUICK_ANSWER,
            status = synthesis.status,
            answer = synthesis.draft.answer.orEmpty(),
            reportBody = synthesis.draft.reportMarkdown ?: synthesis.draft.answer.orEmpty(),
            sources = internals.selectUsedSources(sources, synthesis.draft.usedSourceIndexes),
            strategy = null,
            saveLongReport = { saveResearchReport(query, it) },
        )
    }

    private suspend fun runResearch(query: String, maxSources: Int): Output {
        val strategy = buildResearchStrategy(query)
        val collection = collectSources(
            searchQueries = strategy.searchQueries,
            maxSources = maxSources.coerceIn(1, internals.maxResearchSources),
            resultsPerQuery = internals.researchResultsPerQuery,
            pageCharLimit = internals.researchPageTextLimit,
        )
        if (collection.sources.isEmpty()) {
            return buildEmptySourcesOutput(
                query = query,
                mode = SearchMode.RESEARCH,
                strategy = strategy,
                status = collection.providerStatus ?: OutputStatus.NO_RESULTS,
            )
        }
        val sources = collection.sources

        val synthesis = synthesizeAnswer(query, SearchMode.RESEARCH, sources, strategy)
        return internals.buildOutput(
            query = query,
            mode = SearchMode.RESEARCH,
            status = synthesis.status,
            answer = synthesis.draft.answer.orEmpty(),
            reportBody = synthesis.draft.reportMarkdown ?: synthesis.draft.answer.orEmpty(),
            sources = internals.selectUsedSources(sources, synthesis.draft.usedSourceIndexes),
            strategy = strategy,
            saveLongReport = { saveResearchReport(query, it) },
        )
    }

    private suspend fun buildResearchStrategy(query: String): InternetSearchResearchStrategy {
        val fallback = internals.fallbackResearchStrategy(query)
        val responseText = callLlm(
            systemPrompt = internals.strategySystemPrompt,
            userPrompt = internals.buildStrategyPrompt(query),
            temperature = 0.2f,
            maxTokens = 900,
        ) ?: return fallback

        val draft = internals.readStrategyDraft(responseText) ?: return fallback
        return InternetSearchResearchStrategy(
            goal = draft.goal?.trim().orEmpty().ifBlank { fallback.goal },
            searchQueries = internals.sanitizeSearchStrings(
                values = draft.searchQueries,
                fallback = fallback.searchQueries,
                minItems = 4,
                maxItems = internals.maxSearchQueries,
            ),
            subQuestions = internals.sanitizeSearchStrings(draft.subQuestions, emptyList(), 0, 5),
            answerSections = internals.sanitizeSearchStrings(draft.answerSections, emptyList(), 0, 5),
        )
    }

    private suspend fun collectSources(
        searchQueries: List<String>,
        maxSources: Int,
        resultsPerQuery: Int,
        pageCharLimit: Int,
    ): InternetSearchCollectionResult {
        val aggregated = LinkedHashMap<String, InternetSearchCollectedSource>()
        var providerStatus: OutputStatus? = null

        searchLoop@ for (searchQuery in searchQueries.take(internals.maxSearchQueries)) {
            val results = try {
                webResearchClient.searchWeb(searchQuery, resultsPerQuery)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebSearchProviderException) {
                providerStatus = when (e.kind) {
                    WebSearchProviderFailureKind.BLOCKED -> OutputStatus.PROVIDER_BLOCKED
                    WebSearchProviderFailureKind.UNAVAILABLE -> OutputStatus.PROVIDER_UNAVAILABLE
                }
                l.warn("InternetSearch provider failure for query '{}': {}", searchQuery, e.message)
                break@searchLoop
            } catch (e: Exception) {
                l.warn("InternetSearch search failed for query '{}': {}", searchQuery, e.message)
                emptyList()
            }

            for (result in results) {
                val normalizedUrl = result.url.trim()
                if (normalizedUrl.isBlank() || aggregated.containsKey(normalizedUrl)) continue

                val pageText = try {
                    webResearchClient.extractPageText(normalizedUrl, pageCharLimit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    l.debug("InternetSearch page extraction failed for '{}': {}", normalizedUrl, e.message)
                    null
                }

                aggregated[normalizedUrl] = InternetSearchCollectedSource(
                    index = aggregated.size + 1,
                    title = result.title.trim(),
                    url = normalizedUrl,
                    snippet = result.snippet.trim(),
                    foundByQuery = searchQuery,
                    pageText = pageText?.trim()?.takeIf { it.isNotBlank() },
                )
                if (aggregated.size >= maxSources) break
            }

            if (aggregated.size >= maxSources) break
        }

        return InternetSearchCollectionResult(
            sources = aggregated.values.toList(),
            providerStatus = providerStatus?.takeIf { aggregated.isEmpty() },
        )
    }

    private suspend fun synthesizeAnswer(
        query: String,
        mode: SearchMode,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
    ): InternetSearchSynthesisResult {
        val promptSpec = internals.promptSpec(mode)
        val primary = callLlm(
            systemPrompt = promptSpec.systemPrompt,
            userPrompt = internals.buildSynthesisPrompt(query, mode, sources, strategy),
            temperature = 0.15f,
            maxTokens = promptSpec.maxTokens,
        )
        internals.recoverSynthesisDraft(primary.orEmpty(), mode, sources)
            ?.takeIf(internals::isGrounded)
            ?.let { return InternetSearchSynthesisResult(OutputStatus.COMPLETE, it) }

        val rescue = callLlm(
            systemPrompt = promptSpec.rescueSystemPrompt,
            userPrompt = internals.buildRescuePrompt(query, mode, sources, strategy, primary),
            temperature = 0.1f,
            maxTokens = promptSpec.rescueMaxTokens,
        )
        internals.recoverSynthesisDraft(rescue.orEmpty(), mode, sources)
            ?.takeIf(internals::isGrounded)
            ?.let { return InternetSearchSynthesisResult(OutputStatus.COMPLETE, it) }

        return InternetSearchSynthesisResult(
            status = OutputStatus.PARTIAL,
            draft = internals.buildFallbackDraft(mode, query, sources),
        )
    }

    private fun buildEmptySourcesOutput(
        query: String,
        mode: SearchMode,
        strategy: InternetSearchResearchStrategy?,
        status: OutputStatus,
    ): Output {
        val message = internals.buildEmptySourcesMessage(query, status)
        return internals.buildOutput(
            query = query,
            mode = mode,
            status = status,
            answer = message,
            reportBody = message,
            sources = emptyList(),
            strategy = strategy,
            saveLongReport = { saveResearchReport(query, it) },
        )
    }

    private suspend fun callLlm(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        maxTokens: Int,
    ): String? {
        val response = try {
            api.message(
                GigaRequest.Chat(
                    model = settingsProvider.gigaModel.alias,
                    messages = listOf(
                        GigaRequest.Message(role = GigaMessageRole.system, content = systemPrompt),
                        GigaRequest.Message(role = GigaMessageRole.user, content = userPrompt),
                    ),
                    functions = emptyList(),
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            l.warn("InternetSearch LLM call failed: {}", e.message)
            return null
        }

        return when (response) {
            is GigaResponse.Chat.Error -> {
                l.warn("InternetSearch LLM error {}: {}", response.status, response.message)
                null
            }

            is GigaResponse.Chat.Ok -> response.choices
                .asReversed()
                .firstOrNull { it.message.content.isNotBlank() }
                ?.message
                ?.content
                ?.trim()
        }
    }

    private fun saveResearchReport(query: String, reportMarkdown: String): String? {
        return runCatching {
            val outputDir = filesToolUtil.souzDocumentsDirectoryPath.resolve("internet_research").toFile()
            filesToolUtil.requirePathIsSave(outputDir)
            outputDir.mkdirs()
            val file = File(outputDir, buildResearchFileName(query))
            filesToolUtil.requirePathIsSave(file)
            file.writeText(reportMarkdown, StandardCharsets.UTF_8)
            file.absolutePath
        }.onFailure { l.warn("Failed to save research markdown report: {}", it.message) }.getOrNull()
    }

    private fun buildResearchFileName(query: String): String {
        val slug = query.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "internet_research" }
        return "${slug}_${System.currentTimeMillis()}.md"
    }
}
