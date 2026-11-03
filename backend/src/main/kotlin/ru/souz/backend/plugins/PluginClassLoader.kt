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

/** Only the platform and the explicitly shared SDK/runtime delegate to the host. */
internal class PluginClassLoader(url: URL) : URLClassLoader(arrayOf(url), PluginParent) {
    override fun getResource(name: String): URL? =
        if (isSharedResource(name)) parent.getResource(name) else findResource(name)?.withoutJarCache()

    override fun getResources(name: String): Enumeration<URL> =
        if (isSharedResource(name)) parent.getResources(name)
        else Collections.enumeration(findResources(name).asSequence().map { it.withoutJarCache() }.toList())

    companion object {
        private val shared = listOf("ru.souz.plugins.api.", "kotlin.", "kotlinx.coroutines.")
        private val resources = shared.map { it.replace('.', '/') }
        fun isSharedClass(name: String): Boolean = shared.any(name::startsWith)
        fun isSharedResource(name: String): Boolean = resources.any(name::startsWith)
    }
}

// Resource URLs must not keep JARs in the JVM-global JarURLConnection cache after unload.
private fun URL.withoutJarCache(): URL {
    val source = this
    return URL.of(toURI(), object : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection = source.openConnection().apply { useCaches = false }
    })
}

internal inline fun <T> withPluginLoader(loader: ClassLoader, block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = loader
    return try { block() } finally { thread.contextClassLoader = previous }
}

private object PluginParent : ClassLoader(ClassLoader.getPlatformClassLoader()) {
    private val host = PluginClassLoader::class.java.classLoader
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (PluginClassLoader.isSharedClass(name)) host.loadClass(name) else super.loadClass(name, resolve)

    override fun getResource(name: String): URL? =
        if (PluginClassLoader.isSharedResource(name)) host.getResource(name) else super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        if (PluginClassLoader.isSharedResource(name)) host.getResources(name) else Collections.emptyEnumeration()
}

/** Library SPI lookup follows suspending calls across dispatcher changes. */
internal class PluginClassLoaderContext(private val loader: ClassLoader) :
    ThreadContextElement<ClassLoader?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PluginClassLoaderContext>
    override fun updateThreadContext(context: CoroutineContext): ClassLoader? =
        Thread.currentThread().contextClassLoader.also { Thread.currentThread().contextClassLoader = loader }
    override fun restoreThreadContext(context: CoroutineContext, oldState: ClassLoader?) {
        Thread.currentThread().contextClassLoader = oldState
    }
}
