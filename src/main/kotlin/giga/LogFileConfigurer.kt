package com.dumch.giga

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.classic.filter.ThresholdFilter
import org.slf4j.LoggerFactory

object LogFileConfigurer {
    fun configure(
        logFile: String,
        envVars: List<String>,
        defaultLevel: Level = Level.INFO,
        treatNoneAsOff: Boolean = false,
    ): Level {
        val rawLevel = envVars.asSequence()
            .mapNotNull { System.getenv(it) }
            .firstOrNull()
        val consoleLevel = rawLevel
            ?.let {
                val normalized = if (treatNoneAsOff && it.equals("NONE", true)) "OFF" else it
                runCatching { Level.valueOf(normalized) }.getOrNull()
            }
            ?: defaultLevel

        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as? Logger ?: return consoleLevel
        root.level = Level.ALL

        root.iteratorForAppenders().asSequence()
            .filterIsInstance<ConsoleAppender<ILoggingEvent>>()
            .forEach { appender ->
                appender.clearAllFilters()
                val filter = ThresholdFilter().apply {
                    setLevel(consoleLevel.levelStr)
                    start()
                }
                appender.addFilter(filter)
            }

        if (root.getAppender("FILE_$logFile") == null) {
            val context = root.loggerContext
            val encoder = PatternLayoutEncoder().apply {
                this.context = context
                pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
                start()
            }
            val appender = FileAppender<ILoggingEvent>().apply {
                this.context = context
                name = "FILE_$logFile"
                file = logFile
                isAppend = true
                this.encoder = encoder
                start()
            }
            root.addAppender(appender)
        }

        return consoleLevel
    }
}

