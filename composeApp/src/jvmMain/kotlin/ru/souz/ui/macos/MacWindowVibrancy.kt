package ru.souz.ui.macos

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Component
import java.awt.Window
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Installs native NSVisualEffectView behind Compose content for true macOS vibrancy.
 */
object MacWindowVibrancy {
    private val log = LoggerFactory.getLogger(MacWindowVibrancy::class.java)
    private val installedViews = WeakHashMap<Window, Long>()

    fun install(window: Window): Boolean {
        if (!isMacOs()) return false
        synchronized(installedViews) {
            if (installedViews.containsKey(window)) return true
        }

        return runCatching {
            val nativeViewPtr = resolveNativeContentViewPtr(window)
            if (nativeViewPtr == 0L) {
                return false
            }

            val effectViewPtr = attachVisualEffectView(nativeViewPtr)
            if (effectViewPtr == 0L) {
                return false
            }

            synchronized(installedViews) {
                installedViews[window] = effectViewPtr
            }
            true
        }.getOrElse { error ->
            log.debug("Failed to install macOS vibrancy: {}", error.message)
            false
        }
    }

    fun uninstall(window: Window) {
        if (!isMacOs()) return

        val effectPtr = synchronized(installedViews) { installedViews.remove(window) } ?: return
        runCatching {
            val effectView = Pointer(effectPtr)
            ObjC.msgVoid(effectView, "removeFromSuperview")
        }.onFailure { error ->
            log.debug("Failed to uninstall macOS vibrancy: {}", error.message)
        }
    }

    private fun resolveNativeContentViewPtr(window: Window): Long {
        val peer = getComponentPeer(window) ?: return 0L
        val platformWindow = invokeNoArgs(peer, "getPlatformWindow") ?: return 0L

        val platformWindowClass = Class.forName("sun.lwawt.PlatformWindow")
        val cPlatformWindowClass = Class.forName("sun.lwawt.macosx.CPlatformWindow")
        val getNativeViewPtr = cPlatformWindowClass.getDeclaredMethod(
            "getNativeViewPtr",
            platformWindowClass
        )
        if (!getNativeViewPtr.trySetAccessible()) {
            return 0L
        }

        val rawPtr = getNativeViewPtr.invoke(null, platformWindow) ?: return 0L
        return (rawPtr as? Number)?.toLong() ?: 0L
    }

    private fun getComponentPeer(component: Component): Any? {
        val getPeer = Component::class.java.getDeclaredMethod("getPeer")
        if (!getPeer.trySetAccessible()) {
            return null
        }
        return getPeer.invoke(component)
    }

    private fun invokeNoArgs(target: Any, methodName: String): Any? {
        val method = findMethod(target.javaClass, methodName) ?: return null
        if (!method.trySetAccessible()) {
            return null
        }
        return method.invoke(target)
    }

    private fun findMethod(type: Class<*>, name: String): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun attachVisualEffectView(nativeContentViewPtr: Long): Long {
        val contentView = Pointer(nativeContentViewPtr)
        val visualClass = ObjC.cls("NSVisualEffectView")
        val glassClass = ObjC.cls("NSGlassEffectView")
        val cls = visualClass ?: glassClass ?: return 0L
        val usesVisualEffect = visualClass != null
        val alloc = ObjC.msgPtr(cls, "alloc") ?: return 0L
        val view = ObjC.msgPtrRect(
            alloc,
            "initWithFrame:",
            NSRect.ByValue(0.0, 0.0, 10_000.0, 10_000.0)
        ) ?: return 0L

        // Match full parent size and keep effect behind all Compose-hosted content.
        ObjC.msgVoid(view, "setAutoresizingMask:", (NSViewWidthSizable or NSViewHeightSizable).toLong())
        if (usesVisualEffect) {
            ObjC.msgVoid(view, "setBlendingMode:", NSVisualEffectBlendingModeBehindWindow.toLong())
            ObjC.msgVoid(view, "setState:", NSVisualEffectStateFollowsWindowActiveState.toLong())
            ObjC.msgVoid(view, "setMaterial:", NSVisualEffectMaterialUnderWindowBackground.toLong())
        }
        ObjC.msgVoid(contentView, "addSubview:positioned:relativeTo:", view, NSWindowBelow.toLong(), Pointer.NULL)

        return Pointer.nativeValue(view)
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name", "").contains("Mac", ignoreCase = true)
}

private object ObjC {
    private interface ObjCStrict : Library {
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, rect: NSRect.ByValue): Pointer?
    }

    private val objc = NativeLibrary.getInstance("objc")
    private val fnGetClass = objc.getFunction("objc_getClass")
    private val fnSelRegisterName = objc.getFunction("sel_registerName")
    private val fnMsgSend = objc.getFunction("objc_msgSend")
    private val strictLib = Native.load("objc", ObjCStrict::class.java)
    private val selectorCache = ConcurrentHashMap<String, Pointer>()

    fun cls(name: String): Pointer? =
        fnGetClass.invoke(Pointer::class.java, arrayOf(name)) as? Pointer

    fun msgPtr(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        fnMsgSend.invoke(Pointer::class.java, buildArgs(receiver, selector, args)) as? Pointer

    fun msgPtrRect(receiver: Pointer?, selector: String, rect: NSRect.ByValue): Pointer? {
        val selectorPtr = selectorCache.computeIfAbsent(selector) { key ->
            fnSelRegisterName.invoke(Pointer::class.java, arrayOf(key)) as Pointer
        }
        return strictLib.objc_msgSend(receiver ?: Pointer.NULL, selectorPtr, rect)
    }

    fun msgVoid(receiver: Pointer?, selector: String, vararg args: Any?) {
        fnMsgSend.invokeVoid(buildArgs(receiver, selector, args))
    }

    private fun buildArgs(receiver: Pointer?, selector: String, args: Array<out Any?>): Array<Any?> {
        val selectorPtr = selectorCache.computeIfAbsent(selector) { key ->
            fnSelRegisterName.invoke(Pointer::class.java, arrayOf(key)) as Pointer
        }
        return arrayOf(receiver ?: Pointer.NULL, selectorPtr, *args)
    }
}

@Structure.FieldOrder("x", "y")
private open class NSPoint : Structure() {
    @JvmField var x: Double = 0.0
    @JvmField var y: Double = 0.0
}

@Structure.FieldOrder("width", "height")
private open class NSSize : Structure() {
    @JvmField var width: Double = 0.0
    @JvmField var height: Double = 0.0
}

@Structure.FieldOrder("origin", "size")
private open class NSRect : Structure() {
    @JvmField var origin: NSPoint = NSPoint()
    @JvmField var size: NSSize = NSSize()

    open class ByValue() : NSRect(), Structure.ByValue {
        constructor(x: Double, y: Double, width: Double, height: Double) : this() {
            origin.x = x
            origin.y = y
            size.width = width
            size.height = height
        }
    }
}

// NSViewAutoresizingMask
private const val NSViewWidthSizable = 2
private const val NSViewHeightSizable = 16

// NSWindowOrderingMode
private const val NSWindowBelow = -1

// NSVisualEffect enums
private const val NSVisualEffectBlendingModeBehindWindow = 0
private const val NSVisualEffectStateFollowsWindowActiveState = 0
private const val NSVisualEffectMaterialWindowBackground = 12
private const val NSVisualEffectMaterialUnderWindowBackground = 21
