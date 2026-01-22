package ru.gigadesk.tool.desktop

import ru.gigadesk.image.ImageUtils
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup
import org.slf4j.LoggerFactory
import ru.gigadesk.audio.Say
import java.io.File

class ToolReadScreenText(
    private val bash: ToolRunBashCommand = ToolRunBashCommand,
) : ToolSetup<ToolReadScreenText.Input> {
    private val l = LoggerFactory.getLogger(ToolReadScreenText::class.java)

    data class Input(
        @InputParamDescription("Languages for OCR, e.g., 'eng', 'eng+rus'")
        val lang: String = "eng+rus"
    )

    override val name: String = "ReadScreenText"
    override val description: String = "Captures the screen, detects the largest text block and reads it aloud"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Прочитай что на экране",
            params = mapOf("lang" to "eng+rus")
        ),
        FewShotExample(
            request = "Прочти весь текст с экрана",
            params = mapOf("lang" to "eng+rus")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        try {
            val screenshot = ImageUtils.screenshotJpegBytes()
            val file = File.createTempFile("screenshot", ".jpg")
            file.writeBytes(screenshot)
            val cmd = listOf("tesseract", file.absolutePath, "stdout", "-l", input.lang, "--psm", "6", "tsv")
            val tsv = try {
                bash.sh(cmd.joinToString(" "))
            } finally {
                file.delete()
            }
            val lines = tsv.lines()
            if (lines.isEmpty()) return "No text found"
            val header = lines.first().split('\t')
            val levelIdx = header.indexOf("level")
            val blockIdx = header.indexOf("block_num")
            val leftIdx = header.indexOf("left")
            val topIdx = header.indexOf("top")
            val widthIdx = header.indexOf("width")
            val heightIdx = header.indexOf("height")
            val textIdx = header.indexOf("text")

            data class Block(var minLeft:Int, var minTop:Int, var maxRight:Int, var maxBottom:Int, val words:MutableList<String>)
            val blocks = mutableMapOf<Int, Block>()

            lines.drop(1).forEach { line ->
                val cols = line.split('\t')
                if (cols.size <= textIdx) return@forEach
                val level = cols[levelIdx].toIntOrNull() ?: return@forEach
                if (level != 5) return@forEach
                val blockNum = cols[blockIdx].toIntOrNull() ?: return@forEach
                val left = cols[leftIdx].toIntOrNull() ?: return@forEach
                val top = cols[topIdx].toIntOrNull() ?: return@forEach
                val width = cols[widthIdx].toIntOrNull() ?: return@forEach
                val height = cols[heightIdx].toIntOrNull() ?: return@forEach
                val text = cols[textIdx].trim()
                val block = blocks.getOrPut(blockNum) {
                    Block(left, top, left + width, top + height, mutableListOf())
                }
                block.minLeft = minOf(block.minLeft, left)
                block.minTop = minOf(block.minTop, top)
                block.maxRight = maxOf(block.maxRight, left + width)
                block.maxBottom = maxOf(block.maxBottom, top + height)
                if (text.isNotBlank()) block.words.add(text)
            }

            val main = blocks.values.maxByOrNull { (it.maxRight - it.minLeft) * (it.maxBottom - it.minTop) }
            val text = main?.words?.joinToString(" ")?.trim().orEmpty()
            return if (text.isNotBlank()) {
                "Done"
            } else {
                "No text found"
            }
        } catch (e: Exception) {
            l.error("ReadScreenText failed", e)
            throw RuntimeException("ReadScreenText failed: ${e.message}", e)
        }
    }
}

fun main() {
    val tool = ToolReadScreenText()
    val result = tool.invoke(ToolReadScreenText.Input())
    println(result)
}
