import Darwin
import Foundation
import Speech

private let authorizationNotDetermined: Int32 = 0
private let authorizationDenied: Int32 = 1
private let authorizationRestricted: Int32 = 2
private let authorizationAuthorized: Int32 = 3
private let authorizationUnsupported: Int32 = 4

private enum BridgeError: String {
    case permissionDenied = "LOCAL_MACOS_STT:PERMISSION_DENIED"
    case restricted = "LOCAL_MACOS_STT:RESTRICTED"
    case unavailable = "LOCAL_MACOS_STT:UNAVAILABLE"
    case unsupportedLocale = "LOCAL_MACOS_STT:UNSUPPORTED_LOCALE"
}

@_cdecl("souz_macos_speech_has_usage_description")
public func souz_macos_speech_has_usage_description() -> Int32 {
    let usageDescription = Bundle.main.object(forInfoDictionaryKey: "NSSpeechRecognitionUsageDescription") as? String
    let hasUsageDescription = !(usageDescription?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
    return hasUsageDescription ? 1 : 0
}

@_cdecl("souz_macos_speech_authorization_status")
public func souz_macos_speech_authorization_status() -> Int32 {
    mapAuthorizationStatus(SFSpeechRecognizer.authorizationStatus())
}

@_cdecl("souz_macos_speech_request_authorization_if_needed")
public func souz_macos_speech_request_authorization_if_needed() -> Int32 {
    let current = SFSpeechRecognizer.authorizationStatus()
    if current != .notDetermined {
        return mapAuthorizationStatus(current)
    }

    let semaphore = DispatchSemaphore(value: 0)
    var resolved = current
    SFSpeechRecognizer.requestAuthorization { status in
        resolved = status
        semaphore.signal()
    }
    semaphore.wait()
    return mapAuthorizationStatus(resolved)
}

@_cdecl("souz_macos_speech_recognize_wav")
public func souz_macos_speech_recognize_wav(
    _ pathPtr: UnsafePointer<CChar>?,
    _ localePtr: UnsafePointer<CChar>?,
    _ errorBuffer: UnsafeMutablePointer<CChar>?,
    _ errorBufferSize: Int32
) -> UnsafeMutablePointer<CChar>? {
    guard let pathPtr, let localePtr else {
        writeError(
            "\(BridgeError.unavailable.rawValue):Missing path or locale.",
            to: errorBuffer,
            size: errorBufferSize
        )
        return nil
    }

    let localeIdentifier = String(cString: localePtr)
    guard isLocaleSupported(localeIdentifier) else {
        writeError(
            "\(BridgeError.unsupportedLocale.rawValue):\(localeIdentifier)",
            to: errorBuffer,
            size: errorBufferSize
        )
        return nil
    }

    switch SFSpeechRecognizer.authorizationStatus() {
    case .authorized:
        break
    case .denied:
        writeError(BridgeError.permissionDenied.rawValue, to: errorBuffer, size: errorBufferSize)
        return nil
    case .restricted:
        writeError(BridgeError.restricted.rawValue, to: errorBuffer, size: errorBufferSize)
        return nil
    case .notDetermined:
        writeError(
            "\(BridgeError.unavailable.rawValue):Speech recognition permission has not been granted yet.",
            to: errorBuffer,
            size: errorBufferSize
        )
        return nil
    @unknown default:
        writeError(
            "\(BridgeError.unavailable.rawValue):Speech recognition authorization status is unsupported.",
            to: errorBuffer,
            size: errorBufferSize
        )
        return nil
    }

    let locale = Locale(identifier: localeIdentifier)
    guard let recognizer = SFSpeechRecognizer(locale: locale) else {
        writeError(
            "\(BridgeError.unsupportedLocale.rawValue):\(localeIdentifier)",
            to: errorBuffer,
            size: errorBufferSize
        )
        return nil
    }
    guard recognizer.isAvailable else {
        writeError(
            "\(BridgeError.unavailable.rawValue):Speech recognizer is currently unavailable.",
            to: errorBuffer,
            size: errorBufferSize
        )
        return nil
    }

    let request = SFSpeechURLRecognitionRequest(url: URL(fileURLWithPath: String(cString: pathPtr)))
    request.shouldReportPartialResults = false

    let semaphore = DispatchSemaphore(value: 0)
    let lock = NSLock()
    var didFinish = false
    var recognizedText: String?
    var failure: String?
    var task: SFSpeechRecognitionTask?

    task = recognizer.recognitionTask(with: request) { result, error in
        lock.lock()
        defer { lock.unlock() }
        if didFinish {
            return
        }

        if let error {
            didFinish = true
            failure = "\(BridgeError.unavailable.rawValue):\(error.localizedDescription)"
            semaphore.signal()
            return
        }

        guard let result else {
            return
        }
        if result.isFinal {
            didFinish = true
            recognizedText = result.bestTranscription.formattedString
            semaphore.signal()
        }
    }

    semaphore.wait()
    task?.cancel()

    if let recognizedText {
        return strdup(recognizedText)
    }

    writeError(
        failure ?? "\(BridgeError.unavailable.rawValue):Speech recognition finished without a final result.",
        to: errorBuffer,
        size: errorBufferSize
    )
    return nil
}

@_cdecl("souz_macos_speech_string_free")
public func souz_macos_speech_string_free(_ value: UnsafeMutablePointer<CChar>?) {
    free(value)
}

private func mapAuthorizationStatus(_ status: SFSpeechRecognizerAuthorizationStatus) -> Int32 {
    switch status {
    case .notDetermined:
        return authorizationNotDetermined
    case .denied:
        return authorizationDenied
    case .restricted:
        return authorizationRestricted
    case .authorized:
        return authorizationAuthorized
    @unknown default:
        return authorizationUnsupported
    }
}

private func isLocaleSupported(_ localeIdentifier: String) -> Bool {
    SFSpeechRecognizer.supportedLocales().contains { supported in
        supported.identifier.caseInsensitiveCompare(localeIdentifier) == .orderedSame
    }
}

private func writeError(_ message: String, to buffer: UnsafeMutablePointer<CChar>?, size: Int32) {
    guard let buffer, size > 0 else { return }

    let utf8 = Array(message.utf8)
    let maxCount = max(Int(size) - 1, 0)
    let copyCount = min(utf8.count, maxCount)
    if copyCount > 0 {
        for index in 0..<copyCount {
            buffer[index] = CChar(bitPattern: utf8[index])
        }
    }
    buffer[copyCount] = 0
}
