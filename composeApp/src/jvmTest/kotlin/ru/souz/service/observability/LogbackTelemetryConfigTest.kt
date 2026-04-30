package ru.souz.service.observability

import kotlin.test.Test
import kotlin.test.assertTrue

class LogbackTelemetryConfigTest {
    @Test
    fun `telemetry appender uses bounded rollover`() {
        val config = checkNotNull(javaClass.classLoader.getResource("logback.xml")) {
            "logback.xml is missing"
        }.readText()

        assertTrue(config.contains("SizeAndTimeBasedRollingPolicy"))
        assertTrue(config.contains("<maxFileSize>"))
        assertTrue(config.contains("<totalSizeCap>"))
    }
}
