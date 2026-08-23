package ru.souz.build.quality

import org.w3c.dom.Element
import org.w3c.dom.Document
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal data class DetektFinding(
    val ruleId: String,
    val diagnostic: QualityDiagnostic,
)

internal object DetektReports {
    fun read(repositoryDirectory: File, reportFiles: Set<File>): List<DetektFinding> =
        reportFiles.sortedBy { it.relativeInvariantPath(repositoryDirectory) }.flatMap { report ->
            readReport(repositoryDirectory, report)
        }.distinct()

    private fun readReport(repositoryDirectory: File, reportFile: File): List<DetektFinding> {
        check(reportFile.isFile) {
            "Expected Detekt report was not produced: ${reportFile.relativeInvariantPath(repositoryDirectory)}"
        }
        val document = parseSecureXml(reportFile)
        val findings = mutableListOf<DetektFinding>()
        val files = document.getElementsByTagName("file")
        for (fileIndex in 0 until files.length) {
            val fileElement = files.item(fileIndex) as Element
            val sourcePath = repositoryPath(repositoryDirectory, fileElement.getAttribute("name"))
            val errors = fileElement.getElementsByTagName("error")
            for (errorIndex in 0 until errors.length) {
                val error = errors.item(errorIndex) as Element
                findings += DetektFinding(
                    ruleId = error.getAttribute("source").substringAfterLast('.'),
                    diagnostic = QualityDiagnostic(
                        path = sourcePath,
                        line = error.getAttribute("line").toIntOrNull(),
                        message = error.getAttribute("message"),
                    ),
                )
            }
        }
        return findings
    }

    private fun repositoryPath(repositoryDirectory: File, path: String): String {
        val file = File(path)
        return if (file.isAbsolute) {
            file.relativeInvariantPath(repositoryDirectory)
        } else {
            path.replace(File.separatorChar, '/')
        }
    }
}

internal fun parseSecureXml(file: File): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    return factory.newDocumentBuilder().parse(file)
}

// link to classes
internal val CANCELLATION_RULES = setOf("SuspendFunSwallowedCancellation")
internal val THREAD_LOCAL_RULES = setOf("ThreadLocalInCoroutineCode")
internal val MONITOR_RULES = setOf("MonitorInsideSuspendContext")
