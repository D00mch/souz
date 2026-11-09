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
import ru.souz.plugins.api.SouzPlugin

private val sharedPackages = listOf("ru.souz.plugins.api.", "kotlin.", "kotlinx.coroutines.")
internal fun isSharedPluginClass(name: String): Boolean = sharedPackages.any(name::startsWith)

internal class PluginClassLoader(url: URL) : URLClassLoader(arrayOf(url), PluginParent) {
    override fun getResource(name: String): URL? =
        if (isSharedPluginClass(name.replace('/', '.'))) parent.getResource(name) else findResource(name)?.uncached()

    override fun getResources(name: String): Enumeration<URL> = when {
        isSharedPluginClass(name.replace('/', '.')) -> parent.getResources(name)
        else -> Collections.enumeration(findResources(name).asSequence().map { it.uncached() }.toList())
    }
}

private object PluginParent : ClassLoader(getPlatformClassLoader()) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = when {
        isSharedPluginClass(name) -> SouzPlugin::class.java.classLoader.loadClass(name)
        else -> super.loadClass(name, resolve)
    }

    override fun getResource(name: String): URL? =
        if (isSharedPluginClass(name.replace('/', '.'))) SouzPlugin::class.java.classLoader.getResource(name) else null

    override fun getResources(name: String): Enumeration<URL> = when {
        isSharedPluginClass(name.replace('/', '.')) -> SouzPlugin::class.java.classLoader.getResources(name)
        else -> Collections.emptyEnumeration()
    }
}

private fun URL.uncached(): URL {
    val original = this
    return URL.of(toURI(), object : URLStreamHandler() {
        override fun openConnection(url: URL): URLConnection = original.openConnection().apply { useCaches = false }
    })
}

internal class PluginClassLoaderContext(private val loader: ClassLoader) :
    ThreadContextElement<ClassLoader?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PluginClassLoaderContext>

    override fun updateThreadContext(context: CoroutineContext): ClassLoader? =
        Thread.currentThread().contextClassLoader.also { Thread.currentThread().contextClassLoader = loader }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ClassLoader?) {
        Thread.currentThread().contextClassLoader = oldState
    }
}
