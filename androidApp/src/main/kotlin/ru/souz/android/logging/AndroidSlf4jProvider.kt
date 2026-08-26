package ru.souz.android.logging

import android.util.Log
import org.slf4j.ILoggerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

class AndroidSlf4jProvider : SLF4JServiceProvider {
    private val loggerFactory = AndroidLoggerFactory()
    private val markerFactory = BasicMarkerFactory()
    private val mdcAdapter = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggerFactory
    override fun getMarkerFactory() = markerFactory
    override fun getMDCAdapter() = mdcAdapter
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() = Unit
}

private class AndroidLoggerFactory : ILoggerFactory {
    private val loggers = ConcurrentHashMap<String, Logger>()

    override fun getLogger(name: String): Logger =
        loggers.getOrPut(name) { AndroidLogger(name) }
}

private class AndroidLogger(name: String) : LegacyAbstractLogger() {
    private val tag = name.substringAfterLast('.').take(23)

    init {
        this.name = name
    }

    override fun getFullyQualifiedCallerName(): String? = null

    override fun isTraceEnabled(): Boolean = false
    override fun isDebugEnabled(): Boolean = true
    override fun isInfoEnabled(): Boolean = true
    override fun isWarnEnabled(): Boolean = true
    override fun isErrorEnabled(): Boolean = true

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?,
    ) {
        val message = MessageFormatter.basicArrayFormat(messagePattern, arguments)
        val priority = when (level) {
            Level.TRACE -> Log.VERBOSE
            Level.DEBUG -> Log.DEBUG
            Level.INFO -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR -> Log.ERROR
        }
        if (throwable == null) {
            Log.println(priority, tag, message)
        } else {
            Log.println(priority, tag, message + '\n' + Log.getStackTraceString(throwable))
        }
    }
}
