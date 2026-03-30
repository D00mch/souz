package ru.souz.service.permissions

import io.ktor.util.logging.debug
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.system.exitProcess

/**
 * Relaunches the packaged application (DMG/APP image) or the current JVM
 * invocation. This is helpful when macOS privacy permissions (e.g., Input
 * Monitoring) are granted and the running process needs to be restarted for
 * the new entitlements to take effect.
 */
object AppRelauncher {
    private const val MAC_APP_EXTENSION = "app"
    private val l = LoggerFactory.getLogger(AppRelauncher::class.java)

    fun relaunch(): Boolean {
        // Prefer the launcher path provided by jpackage (used by Compose Desktop).
        val packagedAppPath = System.getProperty("jpackage.app-path")
        l.debug { "packagedAppPath: $packagedAppPath" }
        val builder = when {
            !packagedAppPath.isNullOrBlank() -> {
                val appPath = Paths.get(packagedAppPath)
                l.debug { "appPath: $appPath" }
                when {
                    Files.isDirectory(appPath) && appPath.extension.equals(MAC_APP_EXTENSION, ignoreCase = true) -> {
                        l.info("About to open with -n")
                        ProcessBuilder("open", "-n", appPath.toString())
                    }

                    else -> {
                        l.info("Just using 'ProcessBuilder(appPath.toString())'")
                        ProcessBuilder(appPath.toString())
                    }
                }
            }

            else -> {
                val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
                val classpath = System.getProperty("java.class.path")
                val mainCommand = System.getProperty("sun.java.command")
                l.debug { "javaBin $javaBin, classpath $classpath, mainCommand $mainCommand" }
                ProcessBuilder(javaBin, "-cp", classpath, mainCommand)
            }
        }

        return try {
            builder.start()
            l.info("Relaunch process started, exiting current process")
            exitProcess(0)
        } catch (e: Exception) {
            l.error("Failed to relaunch application", e)
            false
        }
    }
}
