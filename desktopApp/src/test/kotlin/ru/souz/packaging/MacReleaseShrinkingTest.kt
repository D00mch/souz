package ru.souz.packaging

import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.net.URLClassLoader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.io.OutputStream
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses only the packaged classpath when loading icons, so test dependencies cannot hide missing code. */
@EnabledIfSystemProperty(named = "souz.packagedApp", matches = ".+")
class MacReleaseShrinkingTest {
    private val appDir = File(System.getProperty("souz.packagedApp"), "Contents/app")
    private val packagedJars = File(appDir, "Souz AI.cfg").readLines()
        .filter { it.startsWith("app.classpath=\$APPDIR/") }
        .map { File(appDir, it.substringAfter("app.classpath=\$APPDIR/")) }
    private val originalJars = System.getProperty("souz.originalJars").split(File.pathSeparator).map(::File)
    private val iconPrefix = "androidx/compose/material/icons/"

    @Test
    fun `packaged desktop jar matches the prepared release output after all packaging finalizers`() {
        assertTrue(packagedJars.isNotEmpty())
        packagedJars.forEach { assertTrue(it.isFile, "Missing packaged classpath entry: $it") }
        val desktopJar = packagedJars.single { it.name == "desktopApp.jar" || it.name.startsWith("desktopApp-") }
        val proguardJar = File("build/compose/tmp/main-release/proguard/desktopApp.jar")
        assertTrue(proguardJar.isFile)
        assertEquals(jarContentsDigest(proguardJar), jarContentsDigest(desktopJar))
    }

    @Test
    fun `runtime jar contents outside the icon catalog are byte identical including multi-release entries`() {
        val packagedDigests = packagedJars.map(::jarContentsDigest).toSet()
        val preparedDigests = File("build/compose/tmp/main-release/proguard").listFiles().orEmpty()
            .filter { it.extension == "jar" }.map(::archiveDigest).toSet()
        val unchangedJars = originalJars.filterNot { it.name.startsWith("material-icons-") }
        unchangedJars.forEach { original ->
            assertTrue(archiveDigest(original) in preparedDigests, "Modified prepared JAR: ${original.name}")
            assertTrue(jarContentsDigest(original) in packagedDigests, "Modified or missing retained JAR contents: ${original.name}")
        }
        println("Preserved ${unchangedJars.size} prepared runtime JARs byte for byte, including native resources")
        println("Preserved all packaged entries except macOS native signing/extraction outputs")
    }

    @Test
    fun `packaged application has a valid deep code signature`() {
        val process = ProcessBuilder("/usr/bin/codesign", "--verify", "--deep", "--strict", appDir.parentFile.parent)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun `classes outside the icon catalog and service descriptors survive shrinking`() {
        val originalClasses = classNames(originalJars).filterNot { it.startsWith(iconPrefix) }.toSet()
        val packagedClasses = classNames(packagedJars)
        val missing = originalClasses - packagedClasses
        assertTrue(missing.isEmpty(), "Removed non-icon classes: ${missing.sorted().take(30)}")
        assertEquals(serviceDescriptors(originalJars), serviceDescriptors(packagedJars))
        println("Preserved ${originalClasses.size} non-icon class entries and all service descriptors")
    }

    @Test
    fun `icon catalog is reduced and every retained icon can be constructed`() {
        val originalIcons = classNames(originalJars).filter { it.startsWith(iconPrefix) }
        val packagedIcons = classNames(packagedJars).filter { it.startsWith(iconPrefix) }
        assertTrue(originalIcons.size > 1_000, "Expected the full input icon catalog")
        assertTrue(packagedIcons.size in 1 until originalIcons.size / 2, "Icon catalog was not shrunk")
        URLClassLoader(packagedJars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            var constructed = 0
            packagedIcons.filter { it.endsWith("Kt.class") }.forEach { entry ->
                val iconClass = loader.loadClass(entry.removeSuffix(".class").replace('/', '.'))
                iconClass.declaredMethods.filter { it.name.startsWith("get") && it.parameterCount == 1 }.forEach { getter ->
                    val receiver = getter.parameterTypes.single().getField("INSTANCE").get(null)
                    val vector = getter.invoke(null, receiver)
                    assertTrue(vector.javaClass.getMethod("getName").invoke(vector).toString().isNotBlank())
                    constructed++
                }
            }
            assertTrue(constructed > 0)
            println("Icon classes: ${originalIcons.size} -> ${packagedIcons.size}; constructed $constructed icons")
        }
    }

    @Test
    fun `every icon class referenced by packaged application bytecode is present`() {
        val packagedClasses = classNames(packagedJars)
        val iconReference = Regex("${iconPrefix}[A-Za-z0-9_$/]+")
        val references = mutableSetOf<String>()
        packagedJars.forEach { file ->
            JarFile(file).use { jar ->
                jar.entries().asSequence().filter { it.name.startsWith("ru/souz/") && it.name.endsWith(".class") }.forEach { entry ->
                    val bytecode = jar.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    references += iconReference.findAll(bytecode).map { it.value + ".class" }.toList()
                }
            }
        }
        assertTrue(references.isNotEmpty())
        assertTrue((references - packagedClasses).isEmpty(), "Missing referenced icons: ${references - packagedClasses}")
        println("Verified ${references.size} icon class references from packaged application bytecode")
    }

    private fun classNames(jars: List<File>): Set<String> = buildSet {
        jars.forEach { file ->
            JarFile(file).use { jar ->
                addAll(jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList())
            }
        }
    }

    private fun archiveDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { it.copyTo(OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // macOS packaging rewrites ZIP headers and signs embedded Mach-O libraries;
    // Skiko's host library/checksum are extracted. Prepared JARs above retain the
    // exact native inputs. All other packaged entry names/bytes must be unchanged.
    private fun jarContentsDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        JarFile(file).use { jar ->
            val buffer = ByteArray(64 * 1024)
            jar.entries().asSequence().filterNot {
                it.isDirectory || it.name.endsWith(".dylib") || it.name.endsWith(".jnilib") || it.name.endsWith(".dylib.sha256")
            }.sortedBy { it.name }.forEach { entry ->
                digest.update(entry.name.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                val entryDigest = MessageDigest.getInstance("SHA-256")
                jar.getInputStream(entry).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        entryDigest.update(buffer, 0, count)
                    }
                }
                digest.update(entryDigest.digest())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun serviceDescriptors(jars: List<File>): Map<String, Set<String>> = buildMap {
        jars.forEach { file ->
            JarFile(file).use { jar ->
                jar.entries().asSequence().filter { it.name.startsWith("META-INF/services/") && !it.isDirectory }.forEach { entry ->
                    val descriptor = jar.getInputStream(entry).bufferedReader().use { it.readText() }
                    put(entry.name, getOrDefault(entry.name, emptySet()) + descriptor)
                }
            }
        }
    }
}
