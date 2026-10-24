package ru.souz.tool.dataAnalytics

import io.mockk.every
import io.mockk.mockk
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.io.TempDir
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.tool.BadInputException
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolCreatePlotFromCsvTest {
    @TempDir
    lateinit var root: Path

    private val home: Path by lazy { Files.createDirectories(root.resolve("home")) }
    private val tool: ToolCreatePlotFromCsv by lazy {
        val settings = mockk<SettingsProvider>()
        every { settings.forbiddenFolders } returns emptyList()
        ToolCreatePlotFromCsv(
            FilesToolUtil(
                LocalRuntimeSandbox(
                    scope = SandboxScope(userId = "chart-user"),
                    settingsProvider = settings,
                    homePath = home,
                    stateRoot = home.resolve("state"),
                )
            )
        )
    }

    @Test
    fun `renders each chart type with categorical axes in headless mode`() {
        assertTrue(GraphicsEnvironment.isHeadless())
        val csv = home.resolve("teams.csv").apply { writeText("Team,Sales\nNorth,3\nSouth,2\nEast,1\n") }

        for (chartType in ChartType.entries) {
            val output = home.resolve("charts/$chartType.png")
            val result = render(csv, output, chartType, openAfterCreate = true)

            assertEquals("Plot saved to $output", result)
            assertPng(output)
        }
    }

    @Test
    fun `combines duplicate categories and keeps descending value order for every chart type`() {
        val rows = home.resolve("rows.csv").apply {
            writeText("Team,Sales\nNorth,3\nEast,1\nSouth,2\nSouth,4\nWest,not-a-number\n,90\n")
        }
        val totals = home.resolve("totals.csv").apply {
            writeText("Team,Sales\nSouth,6\nNorth,3\nEast,1\n")
        }

        for (chartType in ChartType.entries) {
            val actual = home.resolve("rows-$chartType.png")
            val expected = home.resolve("totals-$chartType.png")
            render(rows, actual, chartType)
            render(totals, expected, chartType)

            assertContentEquals(Files.readAllBytes(expected), Files.readAllBytes(actual))
        }
    }

    @Test
    fun `renders both Excel formats with numeric and decimal comma values`() {
        val workbooks = listOf("xls" to HSSFWorkbook(), "xlsx" to XSSFWorkbook())
        for ((extension, workbook) in workbooks) {
            val source = home.resolve("teams.$extension")
            workbook.use {
                writeWorkbook(it, source)
            }
            val output = home.resolve("$extension.png")

            render(source, output, ChartType.BAR)

            assertPng(output)
        }
    }

    @Test
    fun `omitted columns return the original headers without creating a chart`() {
        val csv = home.resolve("headers.csv").apply { writeText("Team,Sales\nNorth,3\n") }
        val output = home.resolve("headers.png")

        val result = tool.invoke(
            ToolCreatePlotFromCsv.Input(path = csv.toString(), output = output.toString()),
            ToolInvocationMeta.localDefault(),
        )

        assertEquals("Columns required. Available headers: [Team, Sales]", result)
        assertFalse(Files.exists(output))
    }

    @Test
    fun `invalid columns invalid values and unsupported files do not create output`() {
        val missing = home.resolve("missing.csv").apply { writeText("Team,Other\nNorth,3\n") }
        val invalid = home.resolve("invalid.csv").apply { writeText("Team,Sales\nNorth,invalid\n,3\n") }
        val unsupported = home.resolve("teams.txt").apply { writeText("Team,Sales\nNorth,3\n") }
        val output = home.resolve("invalid.png")

        for (source in listOf(missing, invalid, unsupported)) {
            assertFailsWith<BadInputException> { render(source, output, ChartType.BAR) }
            assertFalse(Files.exists(output))
        }
    }

    @Test
    fun `sandbox rejects input and output paths outside the user home`() {
        val outsideInput = root.resolve("outside.csv").apply { writeText("Team,Sales\nNorth,3\n") }
        val input = home.resolve("teams.csv").apply { writeText("Team,Sales\nNorth,3\n") }
        val outsideOutput = root.resolve("outside.png")
        val output = home.resolve("inside.png")

        assertFailsWith<BadInputException> { render(outsideInput, output, ChartType.BAR) }
        assertFailsWith<BadInputException> { render(input, outsideOutput, ChartType.BAR) }
        assertFalse(Files.exists(output))
        assertFalse(Files.exists(outsideOutput))
    }

    private fun render(source: Path, output: Path, chartType: ChartType, openAfterCreate: Boolean = false): String =
        tool.invoke(
            ToolCreatePlotFromCsv.Input(
                path = source.toString(),
                xColumn = "Team",
                yColumn = "Sales",
                chartType = chartType,
                output = output.toString(),
                openAfterCreate = openAfterCreate,
            ),
            ToolInvocationMeta.localDefault(),
        )

    private fun assertPng(path: Path) {
        val bytes = Files.readAllBytes(path)
        assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOf(8))
        val image = checkNotNull(ImageIO.read(path.toFile()))
        assertEquals(800, image.width)
        assertEquals(600, image.height)
        val background = image.getRGB(0, 0)
        assertTrue((0 until image.height step 5).any { y ->
            (0 until image.width step 5).any { x -> image.getRGB(x, y) != background }
        })
    }

    private fun writeWorkbook(workbook: Workbook, path: Path) {
        val sheet = workbook.createSheet("Sales")
        sheet.createRow(0).apply {
            createCell(0).setCellValue("Team")
            createCell(1).setCellValue("Sales")
        }
        sheet.createRow(1).apply {
            createCell(0).setCellValue("North")
            createCell(1).setCellValue(3.0)
        }
        sheet.createRow(2).apply {
            createCell(0).setCellValue("South")
            createCell(1).setCellValue("2,5")
        }
        Files.newOutputStream(path).use(workbook::write)
    }
}
