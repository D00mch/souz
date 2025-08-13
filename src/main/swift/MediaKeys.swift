import Foundation

#if canImport(Cocoa)
import Cocoa

@_cdecl("sendMediaKeyEvent")
public func sendMediaKeyEvent(_ keyCode: UInt32) {
    // Создаем системное событие
    let event = NSEvent.otherEvent(
        with: .systemDefined,
        location: NSPoint.zero,
        modifierFlags: NSEvent.ModifierFlags(rawValue: 0),
        timestamp: 0,
        windowNumber: 0,
        context: nil,
        subtype: 8,  // NX_SUBTYPE_AUX_CONTROL_BUTTONS
        data1: Int((keyCode << 16) | (0xA << 8)),
        data2: -1
    )

    // Отправляем событие
    event?.cgEvent?.post(tap: .cghidEventTap)

    // Создаем событие отпускания клавиши
    let releaseEvent = NSEvent.otherEvent(
        with: .systemDefined,
        location: NSPoint.zero,
        modifierFlags: NSEvent.ModifierFlags(rawValue: 0),
        timestamp: 0,
        windowNumber: 0,
        context: nil,
        subtype: 8,
        data1: Int((keyCode << 16) | (0xB << 8)),
        data2: -1
    )
    releaseEvent?.cgEvent?.post(tap: .cghidEventTap)
}
#else
@_cdecl("sendMediaKeyEvent")
public func sendMediaKeyEvent(_ keyCode: UInt32) {
    // Stub for non-macOS platforms
}
#endif

// Системные коды медиа-клавиш
let NX_KEY_PLAY: UInt32 = 16
let NX_KEY_NEXT: UInt32 = 17
let NX_KEY_PREV: UInt32 = 18

// Пример использования
// sendMediaKeyEvent(NX_KEY_PLAY) // Play/Pause
// sendMediaKeyEvent(NX_KEY_NEXT) // Next Track

