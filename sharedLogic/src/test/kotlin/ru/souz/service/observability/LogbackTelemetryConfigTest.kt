package ru.souz.service.observability

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class LogbackTelemetryConfigTest {
    @Test
    fun `telemetry appender uses bounded rollover`() {
        val config = javaClass.classLoader.getResource("logback.xml")?.readText()
            ?: listOf(
                Path.of("desktopApp/src/main/resources/logback.xml"),
                Path.of("../desktopApp/src/main/resources/logback.xml"),
            ).first(Files::exists).let(Files::readString)

        assertTrue(config.contains("SizeAndTimeBasedRollingPolicy"))
        assertTrue(config.contains("<maxFileSize>"))
        assertTrue(config.contains("<totalSizeCap>"))
    }
}
