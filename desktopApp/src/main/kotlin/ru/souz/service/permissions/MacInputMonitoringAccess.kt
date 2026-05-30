package ru.souz.service.permissions

import com.sun.jna.Library
import com.sun.jna.Native
import org.slf4j.LoggerFactory

object MacInputMonitoringAccess {
    private val l = LoggerFactory.getLogger(MacInputMonitoringAccess::class.java)

    private interface ApplicationServices : Library {
        fun CGPreflightListenEventAccess(): Boolean
        fun CGRequestListenEventAccess(): Boolean
    }

    private val api: ApplicationServices? by lazy {
        if (!MacAppEnvironment.isMac) return@lazy null
        runCatching {
            Native.load("ApplicationServices", ApplicationServices::class.java)
        }.getOrElse { error ->
            l.warn("Failed to load ApplicationServices for input monitoring preflight", error)
            null
        }
    }

    fun requestAccessPromptIfNeeded(): Boolean? {
        val accessApi = api ?: return null
        return runCatching {
            if (accessApi.CGPreflightListenEventAccess()) {
                true
            } else {
                accessApi.CGRequestListenEventAccess()
            }
        }.getOrElse { error ->
            l.warn("Failed to request input monitoring access", error)
            null
        }
    }
}
