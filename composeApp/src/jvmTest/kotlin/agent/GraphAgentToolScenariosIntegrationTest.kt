package agent

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.kodein.di.DI
import org.kodein.di.instance
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.tool.files.FilesToolUtil
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Интеграционные тесты сценариев вызовов тулов через [GraphBasedAgent.execute].
 * Все сценарии проходят через graphAgent.execute(input) без моков LLM.
 *
 * Ресурсы для проверок: тестовые файлы копируются во временную папку под $HOME
 * (требование isPathSafe). Ожидаемые результаты проверяются по содержимому ответа.
 *
 * Требуют настроенный API (переменные окружения / конфиг) для прохождения.
 */
class GraphAgentToolScenariosIntegrationTest {

    private val di = DI.invoke { import(mainDiModule) }
    private val graphAgent: GraphBasedAgent by di.instance()
    private val filesToolUtil: FilesToolUtil by di.instance()

    /** Путь к тестовой директории под $HOME с файлами для сценариев (чтение/файлы/график/PDF). */
    private val testDataPath: String by lazy { prepareTestDataDir() }

    private fun prepareTestDataDir(): String {
        val home = filesToolUtil.applyDefaultEnvs("~")
        val testDir = File(File(home), "gigadesk-integration-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
        val dirSub = File(testDir, "directory")
        dirSub.mkdirs()

        // Копируем ресурсы из classpath во временную папку
        copyResource("test.txt", File(testDir, "test.txt"))
        copyResource("read_me.txt", File(testDir, "read_me.txt"))
        copyResource("sample.csv", File(testDir, "sample.csv"))
        copyResource("directory/file.txt", File(dirSub, "file.txt"))

        // Минимальный PDF для сценария 17 (PDFBox)
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(File(testDir, "sample.pdf"))
        }

        return testDir.absolutePath
    }

    private fun copyResource(resourceName: String, target: File) {
        GraphAgentToolScenariosIntegrationTest::class.java.getResourceAsStream("/$resourceName")
            ?.use { target.writeBytes(it.readBytes()) }
            ?: error("Resource not found: $resourceName")
    }

    /**
     * Запускает сценарий и проверяет результат.
     * @param expectedInResponse все эти подстроки должны присутствовать в ответе (без учёта регистра).
     * @param expectedAnyOf хотя бы одна из этих подстрок должна присутствовать (если список не пуст).
     */
    private fun runScenario(
        description: String,
        userPrompt: String,
        expectedInResponse: List<String> = emptyList(),
        expectedAnyOf: List<String> = emptyList(),
    ): String = runBlocking {
        val result = graphAgent.execute(userPrompt)
        assertNotNull(result, "Scenario '$description': response should not be null")
        assertTrue(result.isNotBlank(), "Scenario '$description': response should not be blank")
        val resultLower = result.lowercase()
        for (expected in expectedInResponse) {
            assertTrue(
                resultLower.contains(expected.lowercase()),
                "Scenario '$description': response should contain '$expected'. Response (first 500 chars): ${result.take(500)}"
            )
        }
        if (expectedAnyOf.isNotEmpty()) {
            val found = expectedAnyOf.any { resultLower.contains(it.lowercase()) }
            assertTrue(
                found,
                "Scenario '$description': response should contain at least one of: $expectedAnyOf. Response (first 500 chars): ${result.take(500)}"
            )
        }
        result
    }

    @Test
    fun scenario1_launchApplication() {
        runScenario(
            "Запусти программу",
            "Запусти программу Терминал",
            expectedAnyOf = listOf("терминал", "запуск", "открыт", "запущен"),
        )
    }

    @Test
    fun scenario2_openWebsite() {
        runScenario(
            "Открой сайт",
            "Открой сайт https://example.com",
            expectedAnyOf = listOf("example", "открыт", "вкладк", "браузер"),
        )
    }

    @Test
    fun scenario3_openWebsiteInNewTab() {
        runScenario(
            "Открой в новой вкладке сайт",
            "Открой в новой вкладке сайт https://example.com",
            expectedAnyOf = listOf("example", "вкладк", "открыт"),
        )
    }

    @Test
    fun scenario4_findSiteInHistory() {
        runScenario(
            "Найти сайт в истории",
            "Найди в истории браузера сайт example",
            expectedAnyOf = listOf("истори", "example", "найден", "сайт", "результат"),
        )
    }

    @Test
    fun scenario5_readPageInOpenTab() {
        runScenario(
            "Прочитать страницу в открытой вкладке",
            "Прочитай содержимое текущей открытой вкладки",
            expectedAnyOf = listOf("вкладк", "страниц", "содержим", "прочитан"),
        )
    }

    @Test
    fun scenario6_todayCalendarEvents() {
        runScenario(
            "Сегодняшние события в календаре",
            "Покажи сегодняшние события в календаре",
            expectedAnyOf = listOf("календар", "событи", "сегодня", "нет событий", "список"),
        )
    }

    @Test
    fun scenario7_createCalendarEvent() {
        runScenario(
            "Создать событие в календаре",
            "Создай событие в календаре: встреча завтра в 10:00",
            expectedAnyOf = listOf("создан", "событи", "календар", "встреча", "добавлен"),
        )
    }

    @Test
    fun scenario8_deleteCalendarEvent() {
        runScenario(
            "Удалить событие из календаря",
            "Удали событие из календаря на завтра в 10:00",
            expectedAnyOf = listOf("удален", "событи", "календар", "удал", "нет такого"),
        )
    }

    @Test
    fun scenario9_findCalendarEvent() {
        runScenario(
            "Найти событие",
            "Найди событие в календаре на эту неделю",
            expectedAnyOf = listOf("календар", "событи", "недел", "найден", "список"),
        )
    }

    @Test
    fun scenario10_saveInstruction() {
        runScenario(
            "Сохранить инструкцию",
            "Сохрани инструкцию: при запросе погоды открывать ya.ru/pogoda",
            expectedAnyOf = listOf("инструкц", "сохранен", "погод", "ya.ru"),
        )
    }

    @Test
    fun scenario11_buildChartFromFile() {
        runScenario(
            "Построить график по файлу",
            "Построй график по данным из файла sample.csv по пути $testDataPath",
            expectedAnyOf = listOf("график", "построен", "файл", "sample", "сохранен", "plot"),
        )
    }

    @Test
    fun scenario12_findFileByName() {
        runScenario(
            "Найти файл по имени",
            "Найди файл по имени test.txt в папке $testDataPath",
            expectedInResponse = listOf("test.txt"),
            expectedAnyOf = listOf("найден", "файл", "путь"),
        )
    }

    @Test
    fun scenario13_listFilesInFolder() {
        runScenario(
            "Посмотреть файлы в папке",
            "Покажи список файлов в папке $testDataPath",
            expectedAnyOf = listOf("test.txt", "read_me", "sample.csv", "directory", "файл", "список"),
        )
    }

    @Test
    fun scenario14_createReadModifyDeleteFile() {
        val tempFile = "test_integration_${System.currentTimeMillis()}.txt"
        runScenario(
            "Создать, прочитать, изменить, удалить файл",
            "В папке $testDataPath создай файл $tempFile с текстом Hello, прочитай его, добавь строку World, потом удали этот файл",
            expectedInResponse = listOf("hello", "world"),
            expectedAnyOf = listOf("создан", "удален", "прочитан", "изменен"),
        )
    }

    @Test
    fun scenario15_moveFile() {
        val destDir = File(filesToolUtil.applyDefaultEnvs("~"), "gigadesk-move-test").apply { mkdirs() }
        val srcFile = File(testDataPath, "read_me.txt")
        runScenario(
            "Перенести файл",
            "Перенеси файл ${srcFile.absolutePath} в папку ${destDir.absolutePath}",
            expectedAnyOf = listOf("перенесен", "перемещен", "read_me", "успеш"),
        )
        // Вернуть файл обратно, чтобы не ломать другие тесты при повторном запуске
        File(destDir, "read_me.txt").takeIf { it.exists() }?.copyTo(File(testDataPath, "read_me.txt"), overwrite = true)
    }

    @Test
    fun scenario16_extractTextFromFile() {
        runScenario(
            "Получить текст из файла",
            "Извлеки текст из файла $testDataPath/test.txt",
            expectedInResponse = listOf("Test content", "проверки", "извлечения"),
        )
    }

    @Test
    fun scenario17_readPdfPageByPage() {
        runScenario(
            "Прочитать PDF постранично",
            "Прочитай PDF постранично: файл $testDataPath/sample.pdf",
            expectedAnyOf = listOf("pdf", "страниц", "sample", "1", "прочитан"),
        )
    }

    @Test
    fun scenario18_openFile() {
        runScenario(
            "Открыть файл",
            "Открой файл $testDataPath/read_me.txt",
            expectedAnyOf = listOf("открыт", "read_me", "файл", "содержим"),
        )
    }

    @Test
    fun scenario19_notesFindCreateDeleteList() {
        runScenario(
            "Заметки: найти, создать, удалить, перечислить",
            "Создай заметку \"тест интеграции\", перечисли заметки, найди заметку тест, удали заметку тест интеграции",
            expectedAnyOf = listOf("заметк", "создан", "перечисл", "найден", "удален"),
        )
    }

    @Test
    fun scenario20_mailFindUnreadListReply() {
        runScenario(
            "Почта: найти письмо, непрочитанные, перечислить",
            "Сколько непрочитанных писем? Перечисли последние письма. Найди письмо от сегодня.",
            expectedAnyOf = listOf("почт", "письм", "непрочитан", "список", "найден", "0", "1", "2"),
        )
    }

    @Test
    fun scenario21_sendEmail() {
        runScenario(
            "Написать письмо",
            "Напиши письмо (тестовое) на test@example.com с темой Тест",
            expectedAnyOf = listOf("письм", "отправлен", "test@example", "тест", "написан", "черновик"),
        )
    }

    @Test
    fun scenario22_readSelectedText() {
        runScenario(
            "Прочитать текст из выделенного",
            "Получи текст из буфера обмена или выделения и кратко перескажи",
            expectedAnyOf = listOf("буфер", "выделен", "текст", "пересказ", "пуст", "содержим"),
        )
    }
}
