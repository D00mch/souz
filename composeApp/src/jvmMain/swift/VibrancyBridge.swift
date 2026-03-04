import AppKit
import Foundation

private final class PassthroughView: NSView {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}

private final class VibrancyController {
    private weak var window: NSWindow?
    private weak var contentView: NSView?
    private weak var frameView: NSView?
    private var sidebarViewStorage: NSView?
    private let backgroundTintView: NSView = {
        let view = NSView(frame: .zero)
        view.autoresizingMask = [.width, .height]
        view.wantsLayer = true
        return view
    }()
    private let overlayContainerView: PassthroughView = {
        let view = PassthroughView(frame: .zero)
        view.autoresizingMask = [.width, .height]
        view.wantsLayer = true
        return view
    }()
    private let fallbackSidebarView: NSVisualEffectView = {
        let view = NSVisualEffectView(frame: .zero)
        view.autoresizingMask = [.width, .height]
        view.blendingMode = .behindWindow
        view.material = .sidebar
        view.state = .active
        view.appearance = NSAppearance(named: .darkAqua)
        view.wantsLayer = true
        return view
    }()

    func update(
        hostPointer: UnsafeMutableRawPointer?,
        width: Double,
        height: Double,
        cornerRadius: Double,
        focused: Int32,
        visible: Int32
    ) {
        guard let resolvedWindow = resolveWindow(from: hostPointer) else { return }

        if window !== resolvedWindow {
            restoreWindowIfNeeded()
            window = resolvedWindow
        }

        guard let resolvedContentView = resolvedWindow.contentView,
              let resolvedFrameView = resolvedContentView.superview else {
            return
        }

        contentView = resolvedContentView
        frameView = resolvedFrameView

        installShellIfNeeded(
            contentView: resolvedContentView,
            frameView: resolvedFrameView
        )

        resolvedWindow.isOpaque = false
        resolvedWindow.backgroundColor = .clear
        resolvedWindow.hasShadow = true
        makeViewHierarchyTransparent(resolvedContentView)

        let shellBounds = resolvedFrameView.bounds
        backgroundTintView.frame = shellBounds
        overlayContainerView.frame = shellBounds
        updateBackgroundTint(focused: focused, visible: visible)

        let sidebarView = currentSidebarView()
        if sidebarView.superview !== overlayContainerView {
            overlayContainerView.addSubview(sidebarView)
        }

        let sidebarInset: CGFloat = 14.0
        let sidebarWidth = max(272.0, min(320.0, width * 0.29))
        let sidebarFrame = NSRect(
            x: sidebarInset,
            y: sidebarInset,
            width: min(sidebarWidth, max(0.0, width - sidebarInset * 2.0)),
            height: max(0.0, height - sidebarInset * 2.0)
        )
        updateSidebarView(
            sidebarView,
            frame: sidebarFrame,
            cornerRadius: cornerRadius + 3.0,
            focused: focused,
            visible: visible
        )
    }

    func dispose() {
        restoreWindowIfNeeded()
    }

    private func restoreWindowIfNeeded() {
        if #available(macOS 26.0, *), let glassView = sidebarViewStorage as? NSGlassEffectView {
            glassView.contentView = nil
        }
        sidebarViewStorage?.removeFromSuperview()
        fallbackSidebarView.removeFromSuperview()
        overlayContainerView.removeFromSuperview()
        backgroundTintView.removeFromSuperview()
        window = nil
        contentView = nil
        frameView = nil
    }

    private func installShellIfNeeded(contentView: NSView, frameView: NSView) {
        if backgroundTintView.superview !== frameView {
            frameView.addSubview(backgroundTintView, positioned: .below, relativeTo: contentView)
        }
        if overlayContainerView.superview !== frameView {
            frameView.addSubview(overlayContainerView, positioned: .above, relativeTo: contentView)
        }
    }

    private func currentSidebarView() -> NSView {
        if #available(macOS 26.0, *) {
            return glassSidebarView()
        } else {
            return fallbackSidebarView
        }
    }

    @available(macOS 26.0, *)
    private func glassSidebarView() -> NSGlassEffectView {
        if let glassView = sidebarViewStorage as? NSGlassEffectView {
            return glassView
        }

        let view = NSGlassEffectView(frame: .zero)
        view.autoresizingMask = [.width, .height]
        view.style = .regular
        view.wantsLayer = true
        let contentView = NSView(frame: .zero)
        contentView.autoresizingMask = [.width, .height]
        contentView.wantsLayer = true
        contentView.layer?.backgroundColor = NSColor.clear.cgColor
        view.contentView = contentView
        sidebarViewStorage = view
        return view
    }

    private func updateBackgroundTint(focused: Int32, visible: Int32) {
        backgroundTintView.isHidden = false
        backgroundTintView.alphaValue = visible == 0 ? 0.0 : 1.0
        backgroundTintView.layer?.cornerRadius = 0.0
        backgroundTintView.layer?.backgroundColor = NSColor(
            calibratedWhite: focused != 0 ? 0.03 : 0.05,
            alpha: focused != 0 ? 0.20 : 0.28
        ).cgColor
    }

    private func updateSidebarView(
        _ sidebarView: NSView,
        frame: NSRect,
        cornerRadius: Double,
        focused: Int32,
        visible: Int32
    ) {
        sidebarView.frame = frame
        sidebarView.wantsLayer = true
        sidebarView.layer?.cornerRadius = CGFloat(cornerRadius)
        sidebarView.layer?.masksToBounds = true
        sidebarView.isHidden = false
        sidebarView.alphaValue = visible == 0 ? 0.0 : 1.0

        if #available(macOS 26.0, *), let glassView = sidebarView as? NSGlassEffectView {
            glassView.style = .regular
            glassView.cornerRadius = CGFloat(cornerRadius)
            glassView.tintColor = NSColor(
                calibratedWhite: focused != 0 ? 0.10 : 0.07,
                alpha: focused != 0 ? 0.14 : 0.22
            )
            glassView.contentView?.frame = glassView.bounds
        } else if let effectView = sidebarView as? NSVisualEffectView {
            effectView.material = .sidebar
            effectView.appearance = NSAppearance(named: .darkAqua)
            effectView.blendingMode = .behindWindow
            effectView.state = .active
        }
    }
}

private func makeViewHierarchyTransparent(_ view: NSView) {
    view.wantsLayer = true
    view.layer?.backgroundColor = NSColor.clear.cgColor
    view.layer?.isOpaque = false

    let object = view
    if object.responds(to: Selector(("setDrawsBackground:"))) {
        object.setValue(false, forKey: "drawsBackground")
    }
    if object.responds(to: Selector(("setBackgroundColor:"))) {
        object.setValue(NSColor.clear, forKey: "backgroundColor")
    }
    if object.responds(to: Selector(("setOpaque:"))) {
        object.setValue(false, forKey: "opaque")
    }

    for subview in view.subviews {
        makeViewHierarchyTransparent(subview)
    }
}

private func resolveWindow(from pointer: UnsafeMutableRawPointer?) -> NSWindow? {
    guard let pointer else { return nil }

    let candidate = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue()
    if let window = candidate as? NSWindow {
        return window
    }
    if let view = candidate as? NSView {
        return view.window
    }
    if let object = candidate as? NSObject {
        if let window = object.value(forKey: "window") as? NSWindow {
            return window
        }
        if let contentView = object.value(forKey: "contentView") as? NSView {
            return contentView.window
        }
    }

    return nil
}

private func onMainThread(_ block: @escaping () -> Void) {
    if Thread.isMainThread {
        block()
    } else {
        DispatchQueue.main.async(execute: block)
    }
}

private func createControllerHandle() -> UnsafeMutableRawPointer {
    Unmanaged.passRetained(VibrancyController()).toOpaque()
}

@_cdecl("souz_vibrancy_create")
public func souz_vibrancy_create() -> UnsafeMutableRawPointer? {
    var handle: UnsafeMutableRawPointer?
    if Thread.isMainThread {
        handle = createControllerHandle()
    } else {
        DispatchQueue.main.sync {
            handle = createControllerHandle()
        }
    }
    return handle
}

@_cdecl("souz_vibrancy_update")
public func souz_vibrancy_update(
    _ handle: UnsafeMutableRawPointer?,
    _ hostPointer: UnsafeMutableRawPointer?,
    _ width: Double,
    _ height: Double,
    _ cornerRadius: Double,
    _ focused: Int32,
    _ visible: Int32
) {
    guard let handle else { return }
    onMainThread {
        let controller = Unmanaged<VibrancyController>.fromOpaque(handle).takeUnretainedValue()
        controller.update(
            hostPointer: hostPointer,
            width: width,
            height: height,
            cornerRadius: cornerRadius,
            focused: focused,
            visible: visible
        )
    }
}

@_cdecl("souz_vibrancy_dispose")
public func souz_vibrancy_dispose(_ handle: UnsafeMutableRawPointer?) {
    guard let handle else { return }
    onMainThread {
        let controller = Unmanaged<VibrancyController>.fromOpaque(handle).takeRetainedValue()
        controller.dispose()
    }
}
