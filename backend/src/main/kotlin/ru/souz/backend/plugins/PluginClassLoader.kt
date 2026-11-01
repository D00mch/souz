package ru.souz.backend.plugins

import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Collections
import java.util.Enumeration
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/** Platform + explicitly shared ABI packages delegate; everything else is private to this JAR. */
internal class PluginClassLoader(url: URL) : URLClassLoader(arrayOf(url), SkillPluginParent) {
    override fun getResource(name: String): URL? =
        if (isSharedResource(name)) parent.getResource(name) else findResource(name)?.withoutJarCache()

    override fun getResources(name: String): Enumeration<URL> =
        if (isSharedResource(name)) parent.getResources(name)
        else Collections.enumeration(findResources(name).asSequence().map { it.withoutJarCache() }.toList())

    companion object {
        private val shared = listOf("ru.souz.plugins.api.", "ru.souz.llms.json.", "kotlin.", "kotlinx.coroutines.", "com.fasterxml.jackson.")
        private val sharedResources = shared.map { it.replace('.', '/') }

        fun isSharedClass(name: String): Boolean = shared.any(name::startsWith) ||
            (name.startsWith("ru.souz.llms.") && '.' !in name.removePrefix("ru.souz.llms."))

        fun isSharedResource(name: String): Boolean = sharedResources.any(name::startsWith) ||
            (name.startsWith("ru/souz/llms/") && '/' !in name.removePrefix("ru/souz/llms/"))
    }
}

// URL.openStream otherwise uses the JVM-wide JarURLConnection cache, beyond URLClassLoader.close().
private fun URL.withoutJarCache(): URL {
    val source = this
    return URL.of(toURI(), object : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection = source.openConnection().apply { useCaches = false }
    })
}

/** Synchronous SPI construction/schema access has the same context-loader rule as suspend calls. */
internal inline fun <T> withPluginLoader(loader: ClassLoader, block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = loader
    return try { block() } finally { thread.contextClassLoader = previous }
}

/** Never fall back to the application classpath for a plugin's private dependency. */
private object SkillPluginParent : ClassLoader(ClassLoader.getPlatformClassLoader()) {
    private val host = PluginClassLoader::class.java.classLoader

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (PluginClassLoader.isSharedClass(name)) host.loadClass(name) else super.loadClass(name, resolve)

    override fun getResource(name: String): URL? =
        if (PluginClassLoader.isSharedResource(name)) host.getResource(name) else super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        if (PluginClassLoader.isSharedResource(name)) host.getResources(name) else Collections.emptyEnumeration()
}

/** Library SPI lookup follows the invocation across dispatcher switches and suspension. */
internal class PluginClassLoaderContext(private val loader: ClassLoader) :
    ThreadContextElement<ClassLoader?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PluginClassLoaderContext>

    override fun updateThreadContext(context: CoroutineContext): ClassLoader? =
        Thread.currentThread().contextClassLoader.also { Thread.currentThread().contextClassLoader = loader }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ClassLoader?) {
        Thread.currentThread().contextClassLoader = oldState
    }
}
