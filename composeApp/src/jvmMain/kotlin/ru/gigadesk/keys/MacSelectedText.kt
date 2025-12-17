package ru.gigadesk.keys

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.atomic.AtomicBoolean

interface SelectedText {
    fun getOrNull(): String?
}

class AXUIElementRef : PointerType {
    constructor() : super()
    constructor(p: Pointer?) : super(p)
}

private interface AppServices : Library {
    companion object {
        val INSTANCE: AppServices = Native.load("ApplicationServices", AppServices::class.java)
    }

    fun AXIsProcessTrusted(): Boolean
    fun AXUIElementCreateSystemWide(): AXUIElementRef
    fun AXUIElementCopyAttributeValue(
        element: AXUIElementRef,
        attribute: CFStringRef,
        value: PointerByReference
    ): Int
}

object MacSelectedText : SelectedText {
    // These attribute strings are defined by Apple as "AXFocusedUIElement" / "AXSelectedText". :contentReference[oaicite:2]{index=2}
    private val AX_FOCUSED_UI_ELEMENT = CFStringRef.createCFString("AXFocusedUIElement")
    private val AX_SELECTED_TEXT = CFStringRef.createCFString("AXSelectedText")

    private val released = AtomicBoolean(false)

    init {
        Runtime.getRuntime().addShutdownHook(Thread { releaseStatics() })
    }

    private fun releaseStatics() {
        if (!released.compareAndSet(false, true)) return
        // CFStringRef extends PointerType, so .pointer is the owned CF object.
        cfRelease(AX_FOCUSED_UI_ELEMENT.pointer)
        cfRelease(AX_SELECTED_TEXT.pointer)
    }

    override fun getOrNull(): String? {
        // User must enable Accessibility permission for your app. :contentReference[oaicite:3]{index=3}
        if (!AppServices.INSTANCE.AXIsProcessTrusted()) return null

        val sys = AppServices.INSTANCE.AXUIElementCreateSystemWide()
        try {
            val focusedRef = PointerByReference()
            if (AppServices.INSTANCE.AXUIElementCopyAttributeValue(
                    sys,
                    AX_FOCUSED_UI_ELEMENT,
                    focusedRef
                ) != 0
            ) return null
            val focusedPtr = focusedRef.value ?: return null

            val focused = AXUIElementRef(focusedPtr)
            try {
                val selectedRef = PointerByReference()
                if (AppServices.INSTANCE.AXUIElementCopyAttributeValue(
                        focused,
                        AX_SELECTED_TEXT,
                        selectedRef
                    ) != 0
                ) return null
                val selectedPtr = selectedRef.value ?: return null
                val text = try {
                    CFStringRef(selectedPtr).stringValue()
                } finally {
                    cfRelease(selectedPtr)
                }
                return text?.takeIf { it.isNotEmpty() }
            } finally {
                cfRelease(focusedPtr)
            }
        } finally {
            cfRelease(sys.pointer) // wrap it too
        }
    }

    private fun cfRelease(p: Pointer?) {
        if (p != null) CoreFoundation.INSTANCE.CFRelease(CoreFoundation.CFTypeRef(p))
    }
}
