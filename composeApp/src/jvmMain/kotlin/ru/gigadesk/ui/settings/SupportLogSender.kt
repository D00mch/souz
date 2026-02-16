package ru.gigadesk.ui.settings

import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.inputStream
import kotlin.io.path.name

import gigadesk.composeapp.generated.resources.Res
import gigadesk.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

class SupportLogSender(
    private val defaultRecipient: String = DEFAULT_SUPPORT_EMAIL,
) {
    data class Result(val message: String, val recipient: String, val logArchive: Path, val logDirectory: Path)

    suspend fun sendLatestLogs(recipient: String?): Result {
        val targetRecipient = recipient?.takeIf { it.isNotBlank() } ?: defaultRecipient
        val logDir = resolveLogDir()
        if (!Files.exists(logDir)) {
            error("Папка с логами не найдена: ${logDir.absolutePathString()}")
        }

        val recentLogs = Files.list(logDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(LOG_EXTENSION) }
                .sorted { a, b ->
                    Files.getLastModifiedTime(b).toMillis().compareTo(Files.getLastModifiedTime(a).toMillis())
                }
                .limit(2)
                .toList()
        }

        if (recentLogs.isEmpty()) {
            error("Файлы логов не найдены в ${logDir.absolutePathString()}")
        }

        val zipped = zipLogs(recentLogs)

        return Result(
            message = sendEmailWithAttachment(targetRecipient, zipped),
            recipient = targetRecipient,
            logArchive = zipped,
            logDirectory = logDir,
        )
    }

    fun logDirectory(): Path = resolveLogDir()

    private fun resolveLogDir(): Path {
        val logDir = System.getenv(LOG_DIR_ENV)
            ?: System.getProperty(LOG_DIR_ENV)
            ?: defaultLogDir()

        return Paths.get(logDir)
    }

    private fun defaultLogDir(): String {
        val home = System.getenv("HOME") ?: System.getProperty("user.home")
        return Paths.get(home, ".local", "state", "gigadesk", "logs").toString()
    }

    private fun zipLogs(logs: List<Path>): Path {
        val tmp = Files.createTempFile("gigadesk-logs-${Instant.now().epochSecond}", ".zip")
        ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
            logs.forEach { log ->
                zos.putNextEntry(ZipEntry(log.name))
                log.inputStream().use { input -> input.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return tmp
    }

    private suspend fun sendEmailWithAttachment(recipient: String, zipped: Path): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> {
                sendViaAppleScript(recipient, zipped)
                successMessage(zipped)
            }

            os.contains("win") -> {
                sendViaPowerShell(recipient, zipped)
                successMessage(zipped)
            }

            else -> {
                sendViaXdgEmail(recipient, zipped)
                successMessage(zipped)
            }
        }
    }

    private suspend fun sendViaXdgEmail(recipient: String, zipped: Path) {
        val subject = getString(Res.string.support_email_subject)
        val body = getString(Res.string.support_email_body)
        val command = listOf(
            "xdg-email",
            "--attach",
            zipped.absolutePathString(),
            "--subject",
            subject,
            "--body",
            body,
            recipient,
        )

        val exitCode = ProcessBuilder(command)
            .start()
            .waitFor()

        if (exitCode != 0) {
            fallbackMailTo(recipient, zipped)
        }
    }

    private suspend fun sendViaAppleScript(recipient: String, zipped: Path) {
        val subject = getString(Res.string.support_email_subject)
        val body = getString(Res.string.support_email_body)
        val script = """
            on run argv
                set targetRecipient to item 1 of argv
                set attachmentPath to item 2 of argv

                tell application "Mail"
                    activate
                    set newMessage to make new outgoing message with properties {subject:"${'$'}subject", content:"${'$'}body", visible:true}
                    tell newMessage
                        make new to recipient at end of to recipients with properties {address:targetRecipient}
                        try
                            make new attachment with properties {file name:attachmentPath} at after last paragraph of content
                        end try
                    end tell
                end tell
            end run
        """.trimIndent()

        val exitCode = ProcessBuilder("osascript", "-e", script, recipient, zipped.absolutePathString())
            .start()
            .waitFor()

        if (exitCode != 0) {
            fallbackMailTo(recipient, zipped)
        }
    }

    private suspend fun sendViaPowerShell(recipient: String, zipped: Path) {
        val subject = getString(Res.string.support_email_subject)
        val body = getString(Res.string.support_email_body)
        val psScript = """
            ${DOLLAR}recipientAddress = "$recipient"
            ${DOLLAR}attachmentPath = "${zipped.absolutePathString()}"
            ${DOLLAR}subject = "$subject"
            ${DOLLAR}body = "$body"

            ${DOLLAR}outlook = New-Object -ComObject Outlook.Application
            if (${DOLLAR}outlook -eq ${DOLLAR}null) { exit 1 }

            ${DOLLAR}mail = ${DOLLAR}outlook.CreateItem(0)
            ${DOLLAR}mail.To = ${DOLLAR}recipientAddress
            ${DOLLAR}mail.Subject = ${DOLLAR}subject
            ${DOLLAR}mail.Body = ${DOLLAR}body
            ${DOLLAR}mail.Attachments.Add(${DOLLAR}attachmentPath) | Out-Null
            ${DOLLAR}mail.Display()
        """.trimIndent()

        val process = ProcessBuilder("powershell", "-Command", psScript).start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            fallbackMailTo(recipient, zipped)
        }
    }

    private suspend fun fallbackMailTo(recipient: String, zipped: Path) {
        val subject = getString(Res.string.support_email_subject)
        val body = getString(Res.string.support_email_body)
        val encodedSubject = urlEncode(subject)
        val encodedBody = urlEncode("$body\nВложение: ${zipped.absolutePathString()}")
        val uri = URI("mailto:$recipient?subject=$encodedSubject&body=$encodedBody")
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().mail(uri)
        } else {
            error("Не удалось открыть почтовое приложение. Файл логов: ${zipped.absolutePathString()}")
        }
    }

    private suspend fun successMessage(zipped: Path): String =
        getString(Res.string.support_email_success_format).format(zipped.absolutePathString())

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        private const val DEFAULT_SUPPORT_EMAIL = "support@gigadesk.ru" // Restoring this if it was missing or just assumed
        private const val LOG_DIR_ENV = "LOG_DIR"
        private const val LOG_EXTENSION = ".log"
        private const val DOLLAR = '$'
    }
}
