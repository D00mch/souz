package agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.kodein.di.bindSingleton
import ru.souz.agent.AgentId
import ru.souz.giga.GigaModel
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.files.ToolExtractText
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolFindFilesByName
import ru.souz.tool.mail.ToolMailSendNewMessage

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphAgentComplexScenarios {

    private val selectedModel = GigaModel.AnthropicHaiku45
    private val agentType = AgentId.GRAPH
    private val support = AgentScenarioTestSupport(selectedModel, agentType)
    private val runTest = support::runTest
    private val filesUtil: FilesToolUtil
        get() = support.filesUtil

    @BeforeEach
    fun checkEnvironment() = support.checkEnvironment()

    @AfterAll
    fun finish() = support.finish()

    @ParameterizedTest(name = "scenario1_readFileThenSendEmailIfNoSecret[{index}] {0}")
    @ValueSource(
        strings = [
            "Прочти public-note.txt. Если в тексте нет слова secret, создай письмо на audit@example.com с темой " +
                    "Public Note и вставь в тело текст файла.",
            "Сделай по шагам: 1) найди и прочти файл public-note.txt; 2) если в нём нет слова secret, " +
                    "подготовь email для audit@example.com с темой Public Note и исходным текстом файла",
        ]
    )
    fun scenario1_readFileThenSendEmailIfNoSecret(userPrompt: String) = runTest {
        val toolFindFilesByName: ToolFindFilesByName = spyk(ToolFindFilesByName(filesUtil))
        val toolExtractText: ToolExtractText = spyk(ToolExtractText(filesUtil))
        val toolMailSendNewMessage: ToolMailSendNewMessage = spyk(ToolMailSendNewMessage(ToolRunBashCommand))

        val foundFilePath = "~/tmp/public-note.txt"
        val safeFileText = "launch approved for finance review"

        every { toolFindFilesByName.invoke(any()) } returns """["$foundFilePath"]"""
        coEvery { toolFindFilesByName.suspendInvoke(any()) } returns """["$foundFilePath"]"""
        every { toolExtractText.invoke(any()) } returns safeFileText
        coEvery { toolExtractText.suspendInvoke(any()) } returns safeFileText
        every { toolMailSendNewMessage.invoke(any()) } returns "Sent"
        coEvery { toolMailSendNewMessage.suspendInvoke(any()) } returns "Sent"

        runScenarioWithMocks(userPrompt) {
            bindSingleton<ToolFindFilesByName> { toolFindFilesByName }
            bindSingleton<ToolExtractText> { toolExtractText }
            bindSingleton<ToolMailSendNewMessage> { toolMailSendNewMessage }
        }

        coVerifyOrder {
            toolFindFilesByName.suspendInvoke(match { it.fileName.contains("public-note.txt") })
            toolExtractText.suspendInvoke(match { it.filePath.contains("public-note.txt") })
            toolMailSendNewMessage.suspendInvoke(any())
        }
        coVerify(exactly = 1) {
            toolMailSendNewMessage.suspendInvoke(
                match {
                    it.recipientAddress.contains("audit@example.com", ignoreCase = true) &&
                        (it.subject?.contains("Public Note", ignoreCase = true) == true) &&
                        (it.content == safeFileText) &&
                        !it.content.contains("secret", ignoreCase = true)
                }
            )
        }
    }

    private suspend fun runScenarioWithMocks(
        userPrompt: String,
        overrides: org.kodein.di.DI.MainBuilder.() -> Unit,
    ) = support.runScenarioWithMocks(userPrompt, overrides)
}
