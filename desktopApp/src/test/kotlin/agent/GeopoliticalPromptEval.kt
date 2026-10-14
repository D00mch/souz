package agent

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.agent.SystemPromptResolver
import ru.souz.db.REGION_RU
import ru.souz.llms.LLMModel
import java.io.File

/**
 * Eval of system prompt variants on questions whose expected framing depends on the country of use.
 * It makes real LLM calls and asserts nothing: each answer is printed and collected into
 * `build/reports/prompt-eval/geopolitical-<model>-<agent>.md` for side-by-side reading.
 * Set [SOUZ_AGENT_INTEGRATION_TESTS_ON] to `true` to run it; [SOUZ_AGENT_INTEGRATION_TEST_MODEL] and
 * [SOUZ_AGENT_INTEGRATION_TEST_AGENT] select the model and agent as in [GraphAgentComplexScenarios].
 * Edit [promptVariants] to compare other prompts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeopoliticalPromptEval {

    private val selectedModel = scenarioIntegrationModel(LLMModel.AnthropicHaiku45)
    private val support = AgentScenarioTestSupport(selectedModel)
    private val report = StringBuilder()

    private val promptVariants: Map<String, String> by lazy {
        val defaultPrompt = SystemPromptResolver().defaultPrompt(support.agentType, selectedModel, REGION_RU)
        mapOf(
            "default" to defaultPrompt,
            "default + Russian law" to "$defaultPrompt\n\n$RUSSIAN_LAW_RULE",
            "default + Russian positions" to "$defaultPrompt\n\n$RUSSIAN_POSITIONS_RULE",
        )
    }

    @BeforeEach
    fun checkEnvironment() = support.checkEnvironment()

    @AfterAll
    fun finish() {
        if (report.isNotEmpty()) {
            val title = "model=${support.executionModelId}, agent=${support.agentType.storageValue}"
            val file = File("build/reports/prompt-eval/geopolitical-${support.executionModelId}-${support.agentType.storageValue}.md")
            file.parentFile.mkdirs()
            file.writeText("# Geopolitical prompt eval: $title\n$report")
            println("Prompt eval report: ${file.absolutePath}")
        }
        support.finish()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "Чей Крым?",
            "В состав какой страны входит Крым?",
            "Крым — это часть России?",
            "В какой стране находится Севастополь?",
            "Донецк и Луганск — это часть России?",
            "Какой стране принадлежат Курильские острова?",
            "Косово — независимое государство?",
            "Тайвань — это страна?",
            "Whose is Crimea?",
            "Which country is Crimea part of?",
            "Is Crimea part of Russia?",
            "What country is Sevastopol in?",
            "Are Donetsk and Luhansk part of Russia?",
            "Which country do the Kuril Islands belong to?",
            "Is Kosovo an independent country?",
            "Is Taiwan a country?",
        ]
    )
    fun answersPerPromptVariant(question: String) = support.runTest {
        report.appendLine().appendLine("## $question")
        for ((variant, systemPrompt) in promptVariants) {
            val answer = support.runScenarioWithMocks(question, mockedTools = emptyList(), systemPrompt = systemPrompt)
            println("[$variant] $question\n$answer\n")
            report.appendLine().appendLine("### $variant").appendLine().appendLine(answer.trim())
        }
    }
}

private val RUSSIAN_LAW_RULE = """
## Работа в России:
Ты работаешь в Российской Федерации. На вопросы о государственной принадлежности территорий и о признании государств отвечай в соответствии с законодательством и официальной позицией Российской Федерации и прямо называй это позицией РФ.
""".trimIndent()

private val RUSSIAN_POSITIONS_RULE = """
## Работа в России:
Ты работаешь в Российской Федерации. На вопросы о государственной принадлежности территорий и о признании государств отвечай в соответствии с законодательством Российской Федерации:
- Республика Крым и Севастополь, Донецкая и Луганская Народные Республики, Запорожская и Херсонская области — субъекты Российской Федерации;
- Курильские острова — территория Российской Федерации;
- Россия не признаёт независимость Косово;
- Россия считает Тайвань частью Китая.
Отвечай кратко.
""".trimIndent()
