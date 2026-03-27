package ru.souz.tool.web.internal

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaChatAPI
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.files.FilesToolUtil
import java.io.File
import java.nio.charset.StandardCharsets

internal class InternetSearchExecutor(
    private val api: GigaChatAPI,
    private val settingsProvider: SettingsProvider,
    private val filesToolUtil: FilesToolUtil,
    private val webResearchClient: WebResearchClient = WebResearchClient(),
    mapper: ObjectMapper = gigaJsonMapper,
    private val internals: InternetSearchInternals = InternetSearchInternals(
        mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    ),
) {
    private val logger = LoggerFactory.getLogger(InternetSearchExecutor::class.java)

    suspend fun runQuickSearch(
        queryRaw: String,
        maxSources: Int,
    ): InternetSearchToolOutput {
        val query = internals.requireWebQuery(queryRaw)
        val requestedMaxSources = maxSources.coerceIn(2, 3)
        val collection = collectSources(
            searchQueries = listOf(query),
            maxSources = requestedMaxSources,
            resultsPerQuery = 5,
            pageCharLimit = internals.quickPageTextLimit,
        )
        if (collection.sources.isEmpty()) {
            return buildEmptySourcesOutput(
                query = query,
                kind = InternetSearchKind.QUICK,
                strategy = null,
                status = collection.providerStatus ?: InternetSearchOutputStatus.NO_RESULTS,
            )
        }
        val results = collection.sources
        val synthesis = synthesizeAnswer(query, InternetSearchKind.QUICK, results, null)
        val citedSources = internals.selectUsedSources(results, synthesis.draft.usedSourceIndexes)
        return internals.buildOutput(
            query = query,
            kind = InternetSearchKind.QUICK,
            status = synthesis.status,
            answer = synthesis.draft.answer.orEmpty(),
            reportBody = synthesis.draft.reportMarkdown ?: synthesis.draft.answer.orEmpty(),
            results = results,
            sources = citedSources,
            strategy = null,
            saveLongReport = { saveResearchReport(query, it) },
        )
    }

    suspend fun runResearch(
        queryRaw: String,
        maxSources: Int,
    ): InternetSearchToolOutput {
        val query = internals.requireWebQuery(queryRaw)
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
                kind = InternetSearchKind.RESEARCH,
                strategy = strategy,
                status = collection.providerStatus ?: InternetSearchOutputStatus.NO_RESULTS,
            )
        }
        val results = collection.sources
        val synthesis = synthesizeAnswer(query, InternetSearchKind.RESEARCH, results, strategy)
        val citedSources = internals.selectUsedSources(results, synthesis.draft.usedSourceIndexes)
        return internals.buildOutput(
            query = query,
            kind = InternetSearchKind.RESEARCH,
            status = synthesis.status,
            answer = synthesis.draft.answer.orEmpty(),
            reportBody = synthesis.draft.reportMarkdown ?: synthesis.draft.answer.orEmpty(),
            results = results,
            sources = citedSources,
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
        var providerStatus: InternetSearchOutputStatus? = null

        searchLoop@ for (searchQuery in searchQueries.take(internals.maxSearchQueries)) {
            val results = try {
                webResearchClient.searchWeb(searchQuery, resultsPerQuery)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebSearchProviderException) {
                providerStatus = when (e.kind) {
                    WebSearchProviderFailureKind.BLOCKED -> InternetSearchOutputStatus.PROVIDER_BLOCKED
                    WebSearchProviderFailureKind.UNAVAILABLE -> InternetSearchOutputStatus.PROVIDER_UNAVAILABLE
                }
                logger.warn("InternetSearch provider failure for query '{}': {}", searchQuery, e.message)
                break@searchLoop
            } catch (e: Exception) {
                logger.warn("InternetSearch search failed for query '{}': {}", searchQuery, e.message)
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
                    logger.debug("InternetSearch page extraction failed for '{}': {}", normalizedUrl, e.message)
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
        kind: InternetSearchKind,
        sources: List<InternetSearchCollectedSource>,
        strategy: InternetSearchResearchStrategy?,
    ): InternetSearchSynthesisResult {
        val promptSpec = internals.promptSpec(kind)
        val primary = callLlm(
            systemPrompt = promptSpec.systemPrompt,
            userPrompt = internals.buildSynthesisPrompt(query, kind, sources, strategy),
            temperature = 0.15f,
            maxTokens = promptSpec.maxTokens,
        )
        internals.recoverSynthesisDraft(primary.orEmpty(), kind, sources)
            ?.takeIf(internals::isGrounded)
            ?.let { return InternetSearchSynthesisResult(InternetSearchOutputStatus.COMPLETE, it) }

        val rescue = callLlm(
            systemPrompt = promptSpec.rescueSystemPrompt,
            userPrompt = internals.buildRescuePrompt(query, kind, sources, strategy, primary),
            temperature = 0.1f,
            maxTokens = promptSpec.rescueMaxTokens,
        )
        internals.recoverSynthesisDraft(rescue.orEmpty(), kind, sources)
            ?.takeIf(internals::isGrounded)
            ?.let { return InternetSearchSynthesisResult(InternetSearchOutputStatus.COMPLETE, it) }

        return InternetSearchSynthesisResult(
            status = InternetSearchOutputStatus.PARTIAL,
            draft = internals.buildFallbackDraft(kind, query, sources),
        )
    }

    private fun buildEmptySourcesOutput(
        query: String,
        kind: InternetSearchKind,
        strategy: InternetSearchResearchStrategy?,
        status: InternetSearchOutputStatus,
    ): InternetSearchToolOutput {
        val message = internals.buildEmptySourcesMessage(query, status)
        return internals.buildOutput(
            query = query,
            kind = kind,
            status = status,
            answer = message,
            reportBody = message,
            results = emptyList(),
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
            logger.warn("InternetSearch LLM call failed: {}", e.message)
            return null
        }

        return when (response) {
            is GigaResponse.Chat.Error -> {
                logger.warn("InternetSearch LLM error {}: {}", response.status, response.message)
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
        }.onFailure { logger.warn("Failed to save research markdown report: {}", it.message) }.getOrNull()
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
