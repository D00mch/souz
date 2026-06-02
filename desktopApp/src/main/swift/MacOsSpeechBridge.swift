import Darwin
import AVFoundation
import CoreMedia
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
    case cancelled = "LOCAL_MACOS_STT:CANCELLED"
    case unavailable = "LOCAL_MACOS_STT:UNAVAILABLE"
    case unsupportedLocale = "LOCAL_MACOS_STT:UNSUPPORTED_LOCALE"
    case onDeviceUnsupported = "LOCAL_MACOS_STT:ON_DEVICE_UNSUPPORTED"
}

private let authorizationTimeoutSeconds: Int = 30
private let recognitionTimeoutSeconds: Int = 120
private let activeRecognitionLock = NSLock()
private var activeRecognitionContext: RecognitionContext?

private final class RecognitionContext {
    let semaphore = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    var task: SFSpeechRecognitionTask?

    private var didFinish = false
    private var recognizedText: String?
    private var failure: String?

    func finishSuccess(_ text: String) {
        lock.lock()
        defer { lock.unlock() }
        guard !didFinish else { return }
        didFinish = true
        recognizedText = text
        semaphore.signal()
    }

    func finishFailure(_ message: String) {
        lock.lock()
        defer { lock.unlock() }
        guard !didFinish else { return }
        didFinish = true
        failure = message
        semaphore.signal()
    }

    func snapshot() -> (String?, String?) {
        lock.lock()
        defer { lock.unlock() }
        return (recognizedText, failure)
    }

    func cancel() {
        task?.cancel()
        finishFailure("\(BridgeError.cancelled.rawValue):Recognition cancelled.")
    }
}

private enum LiveSpeechBridgeError: Error {
    case unsupportedOS
    case unsupportedLocale(String)
    case modelUnavailable(String)
    case permissionDenied
    case unavailable(String)
    case sessionNotFound(Int64)

    var message: String {
        switch self {
        case .unsupportedOS:
            return "LOCAL_MACOS_LIVE_STT:UNSUPPORTED_OS:SpeechAnalyzer requires macOS 26.0 or newer."
        case .unsupportedLocale(let locale):
            return "LOCAL_MACOS_LIVE_STT:UNSUPPORTED_LOCALE:\(locale)"
        case .modelUnavailable(let details):
            return "LOCAL_MACOS_LIVE_STT:MODEL_UNAVAILABLE:\(details)"
        case .permissionDenied:
            return "LOCAL_MACOS_LIVE_STT:PERMISSION_DENIED:Speech recognition permission denied."
        case .unavailable(let details):
            return "LOCAL_MACOS_LIVE_STT:UNAVAILABLE:\(details)"
        case .sessionNotFound(let sessionId):
            return "LOCAL_MACOS_LIVE_STT:SESSION_NOT_FOUND:\(sessionId)"
        }
    }
}

private let liveStartTimeoutSeconds: Int = 30
private let liveAssetInstallTimeoutSeconds: Int = 600
private let liveFinishTimeoutSeconds: Int = 120
private let livePcmSampleRateHz: Int32 = 16_000
private let livePcmChannels: Int32 = 1
private let livePcmBitsPerSample: Int32 = 16

#if compiler(>=6.2)
@available(macOS 26.0, *)
private final class LiveSpeechSessionRegistry {
    static let shared = LiveSpeechSessionRegistry()

    private let lock = NSLock()
    private var nextSessionId: Int64 = 1
    private var sessions: [Int64: LiveSpeechSession] = [:]

    func register(_ session: LiveSpeechSession) -> Int64 {
        lock.lock()
        defer { lock.unlock() }

        let sessionId = nextSessionId
        nextSessionId += 1
        sessions[sessionId] = session
        return sessionId
    }

    func session(_ sessionId: Int64) throws -> LiveSpeechSession {
        lock.lock()
        defer { lock.unlock() }

        guard let session = sessions[sessionId] else {
            throw LiveSpeechBridgeError.sessionNotFound(sessionId)
        }
        return session
    }

    func remove(_ sessionId: Int64) {
        lock.lock()
        sessions.removeValue(forKey: sessionId)
        lock.unlock()
    }
}

@available(macOS 26.0, *)
private final class LiveSpeechSession {
    private let transcriber: SpeechTranscriber
    private let analyzer: SpeechAnalyzer
    private let inputBuilder: AsyncStream<AnalyzerInput>.Continuation
    private let analyzerFormat: AVAudioFormat
    private let sourceFormat: AVAudioFormat
    private let audioConverter: AVAudioConverter
    private let lock = NSLock()
    private let conversionLock = NSLock()

    private var resultTask: Task<Void, Never>?
    private var queuedEvents: [LiveSpeechEvent] = []
    private var failure: String?
    private var closed = false

    static func isSupported(localeIdentifier: String) async -> Bool {
        guard let transcriber = try? await makeTranscriber(localeIdentifier: localeIdentifier) else {
            return false
        }
        let status = await AssetInventory.status(forModules: [transcriber])
        return status == .installed
    }

    static func prepareAssets(localeIdentifier: String) async throws {
        let transcriber = try await makeTranscriber(localeIdentifier: localeIdentifier)
        try await ensureAssetsInstalled(
            for: transcriber,
            localeIdentifier: localeIdentifier,
            allowInstall: true
        )
    }

    static func create(localeIdentifier: String) async throws -> LiveSpeechSession {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized:
            break
        case .denied:
            throw LiveSpeechBridgeError.permissionDenied
        case .restricted:
            throw LiveSpeechBridgeError.unavailable("Speech recognition is restricted on this device.")
        case .notDetermined:
            throw LiveSpeechBridgeError.permissionDenied
        @unknown default:
            throw LiveSpeechBridgeError.unavailable("Speech recognition authorization status is unsupported.")
        }

        let transcriber = try await makeTranscriber(localeIdentifier: localeIdentifier)
        try await ensureAssetsInstalled(
            for: transcriber,
            localeIdentifier: localeIdentifier,
            allowInstall: false
        )

        guard let analyzerFormat = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) else {
            throw LiveSpeechBridgeError.unavailable("No compatible SpeechAnalyzer audio format is available.")
        }
        guard let sourceFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(livePcmSampleRateHz),
            channels: AVAudioChannelCount(livePcmChannels),
            interleaved: false
        ) else {
            throw LiveSpeechBridgeError.unavailable("Failed to create source audio format.")
        }
        guard let audioConverter = AVAudioConverter(from: sourceFormat, to: analyzerFormat) else {
            throw LiveSpeechBridgeError.unavailable("Failed to create audio converter.")
        }

        let analyzer = SpeechAnalyzer(modules: [transcriber])
        let (inputSequence, inputBuilder) = AsyncStream.makeStream(of: AnalyzerInput.self)
        try await analyzer.start(inputSequence: inputSequence)

        let session = LiveSpeechSession(
            transcriber: transcriber,
            analyzer: analyzer,
            inputBuilder: inputBuilder,
            analyzerFormat: analyzerFormat,
            sourceFormat: sourceFormat,
            audioConverter: audioConverter
        )
        session.startResultReader()
        return session
    }

    private static func makeTranscriber(localeIdentifier: String) async throws -> SpeechTranscriber {
        guard SpeechTranscriber.isAvailable else {
            throw LiveSpeechBridgeError.unsupportedOS
        }

        let requestedLocale = Locale(identifier: localeIdentifier)
        guard let supportedLocale = await SpeechTranscriber.supportedLocale(equivalentTo: requestedLocale) else {
            throw LiveSpeechBridgeError.unsupportedLocale(localeIdentifier)
        }

        return SpeechTranscriber(
            locale: supportedLocale,
            transcriptionOptions: [],
            reportingOptions: [.volatileResults],
            attributeOptions: [.audioTimeRange]
        )
    }

    private static func ensureAssetsInstalled(
        for transcriber: SpeechTranscriber,
        localeIdentifier: String,
        allowInstall: Bool
    ) async throws {
        let status = await AssetInventory.status(forModules: [transcriber])
        switch status {
        case .installed:
            return
        case .supported, .downloading:
            guard allowInstall else {
                throw LiveSpeechBridgeError.modelUnavailable("SpeechTranscriber model assets are not installed for \(localeIdentifier).")
            }
        case .unsupported:
            throw LiveSpeechBridgeError.unsupportedLocale(localeIdentifier)
        @unknown default:
            throw LiveSpeechBridgeError.modelUnavailable("SpeechTranscriber model asset status is unknown for \(localeIdentifier).")
        }

        guard let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) else {
            let updatedStatus = await AssetInventory.status(forModules: [transcriber])
            if updatedStatus == .installed {
                return
            }
            throw LiveSpeechBridgeError.modelUnavailable("SpeechTranscriber model assets are not available for installation for \(localeIdentifier).")
        }
        try await request.downloadAndInstall()

        let updatedStatus = await AssetInventory.status(forModules: [transcriber])
        guard updatedStatus == .installed else {
            throw LiveSpeechBridgeError.modelUnavailable("SpeechTranscriber model assets failed to install for \(localeIdentifier).")
        }
    }

    private init(
        transcriber: SpeechTranscriber,
        analyzer: SpeechAnalyzer,
        inputBuilder: AsyncStream<AnalyzerInput>.Continuation,
        analyzerFormat: AVAudioFormat,
        sourceFormat: AVAudioFormat,
        audioConverter: AVAudioConverter
    ) {
        self.transcriber = transcriber
        self.analyzer = analyzer
        self.inputBuilder = inputBuilder
        self.analyzerFormat = analyzerFormat
        self.sourceFormat = sourceFormat
        self.audioConverter = audioConverter
    }

    func acceptPcm(
        audio: Data,
        sampleRateHz: Int32,
        channels: Int32,
        bitsPerSample: Int32
    ) throws {
        try ensureOpen()
        let buffer = try makeAnalyzerBuffer(
            audio: audio,
            sampleRateHz: sampleRateHz,
            channels: channels,
            bitsPerSample: bitsPerSample
        )
        inputBuilder.yield(AnalyzerInput(buffer: buffer))
    }

    func pollEvents() throws -> String {
        try drainEncodedEvents()
    }

    func finalizeAndFinish() async throws -> String {
        try markClosed()
        inputBuilder.finish()
        try await analyzer.finalizeAndFinishThroughEndOfInput()
        _ = await resultTask?.result
        return try drainEncodedEvents()
    }

    func cancel() async {
        let wasClosed = markCancelled()

        if !wasClosed {
            inputBuilder.finish()
        }
        resultTask?.cancel()
        await analyzer.cancelAndFinishNow()
    }

    private func startResultReader() {
        resultTask = Task { [weak self] in
            guard let self else { return }
            do {
                for try await result in self.transcriber.results {
                    let text = String(result.text.characters)
                    let timeRange = Self.audioTimeRange(from: result.text)
                    self.enqueueEvent(text: text, isFinal: result.isFinal, timeRange: timeRange)
                }
            } catch {
                self.recordFailure("SpeechTranscriber failed: \(error.localizedDescription)")
            }
        }
    }

    private static func audioTimeRange(from text: AttributedString) -> CMTimeRange? {
        var startedAt: CMTime?
        var endedAt: CMTime?

        for run in text.runs {
            guard let runRange = run.audioTimeRange, isUsable(range: runRange) else {
                continue
            }
            let runStart = runRange.start
            let runEnd = CMTimeRangeGetEnd(runRange)

            if startedAt == nil || CMTimeCompare(runStart, startedAt!) < 0 {
                startedAt = runStart
            }
            if endedAt == nil || CMTimeCompare(runEnd, endedAt!) > 0 {
                endedAt = runEnd
            }
        }

        guard let startedAt, let endedAt, CMTimeCompare(endedAt, startedAt) >= 0 else {
            return nil
        }
        return CMTimeRangeFromTimeToTime(start: startedAt, end: endedAt)
    }

    private static func isUsable(range: CMTimeRange) -> Bool {
        range.start.isValid &&
            range.duration.isValid &&
            !range.start.isIndefinite &&
            !range.duration.isIndefinite &&
            !range.start.isPositiveInfinity &&
            !range.start.isNegativeInfinity &&
            !range.duration.isPositiveInfinity &&
            !range.duration.isNegativeInfinity
    }

    private func enqueueEvent(text: String, isFinal: Bool, timeRange: CMTimeRange?) {
        lock.lock()
        defer { lock.unlock() }

        let event = LiveSpeechEvent(
            text: text,
            isFinal: isFinal,
            startedAtMs: timeRange.flatMap { Self.milliseconds(from: $0.start) },
            endedAtMs: timeRange.flatMap { Self.milliseconds(from: CMTimeRangeGetEnd($0)) }
        )

        queuedEvents.removeAll { !$0.isFinal }
        queuedEvents.append(event)
        trimQueueIfNeeded()
    }

    private static func milliseconds(from time: CMTime) -> Int64? {
        guard time.isValid,
              !time.isIndefinite,
              !time.isPositiveInfinity,
              !time.isNegativeInfinity else {
            return nil
        }
        let seconds = CMTimeGetSeconds(time)
        guard seconds.isFinite else { return nil }
        return Int64((seconds * 1_000.0).rounded())
    }

    private func trimQueueIfNeeded() {
        if queuedEvents.count > 128 {
            let finalEvents = queuedEvents.filter(\.isFinal)
            let volatileEvents = queuedEvents.filter { !$0.isFinal }.suffix(1)
            // Future ambient listening needs explicit backpressure/overflow policy; keep all finals for now.
            queuedEvents = finalEvents + Array(volatileEvents)
        }
    }

    private func recordFailure(_ message: String) {
        lock.lock()
        if failure == nil {
            failure = message
        }
        lock.unlock()
    }

    private func drainEncodedEvents() throws -> String {
        lock.lock()
        defer { lock.unlock() }

        if let failure {
            throw LiveSpeechBridgeError.unavailable(failure)
        }

        let events = queuedEvents
        queuedEvents.removeAll()
        return events.map(\.encodedLine).joined(separator: "\n")
    }

    private func ensureOpen() throws {
        lock.lock()
        let isClosed = closed
        let currentFailure = failure
        lock.unlock()

        if let currentFailure {
            throw LiveSpeechBridgeError.unavailable(currentFailure)
        }
        if isClosed {
            throw LiveSpeechBridgeError.unavailable("Live speech transcription session is closed.")
        }
    }

    private func markClosed() throws {
        lock.lock()
        let wasClosed = closed
        let currentFailure = failure
        if !closed {
            closed = true
        }
        lock.unlock()

        if let currentFailure {
            throw LiveSpeechBridgeError.unavailable(currentFailure)
        }
        if wasClosed {
            throw LiveSpeechBridgeError.unavailable("Live speech transcription session is closed.")
        }
    }

    private func markCancelled() -> Bool {
        lock.lock()
        defer { lock.unlock() }

        let wasClosed = closed
        closed = true
        return wasClosed
    }

    private func makeAnalyzerBuffer(
        audio: Data,
        sampleRateHz: Int32,
        channels: Int32,
        bitsPerSample: Int32
    ) throws -> AVAudioPCMBuffer {
        guard sampleRateHz == livePcmSampleRateHz,
              channels == livePcmChannels,
              bitsPerSample == livePcmBitsPerSample else {
            throw LiveSpeechBridgeError.unavailable("Unsupported PCM format.")
        }
        guard audio.count % MemoryLayout<Int16>.size == 0 else {
            throw LiveSpeechBridgeError.unavailable("PCM audio byte count must be aligned to 16-bit samples.")
        }
        guard !audio.isEmpty else {
            throw LiveSpeechBridgeError.unavailable("PCM audio chunk is empty.")
        }
        let frameCount = AVAudioFrameCount(audio.count / MemoryLayout<Int16>.size)
        guard let sourceBuffer = AVAudioPCMBuffer(pcmFormat: sourceFormat, frameCapacity: frameCount) else {
            throw LiveSpeechBridgeError.unavailable("Failed to create source audio buffer.")
        }
        sourceBuffer.frameLength = frameCount

        guard let channelData = sourceBuffer.int16ChannelData?[0] else {
            throw LiveSpeechBridgeError.unavailable("Failed to access source audio buffer.")
        }
        audio.withUnsafeBytes { rawBuffer in
            if let source = rawBuffer.bindMemory(to: Int16.self).baseAddress {
                channelData.update(from: source, count: Int(frameCount))
            }
        }

        let ratio = analyzerFormat.sampleRate / sourceFormat.sampleRate
        let convertedCapacity = AVAudioFrameCount(Double(frameCount) * ratio) + 1024
        guard let convertedBuffer = AVAudioPCMBuffer(pcmFormat: analyzerFormat, frameCapacity: convertedCapacity) else {
            throw LiveSpeechBridgeError.unavailable("Failed to create converted audio buffer.")
        }

        var didProvideInput = false
        var conversionError: NSError?
        conversionLock.lock()
        defer { conversionLock.unlock() }

        let status = audioConverter.convert(to: convertedBuffer, error: &conversionError) { _, outStatus in
            if didProvideInput {
                outStatus.pointee = .noDataNow
                return nil
            }
            didProvideInput = true
            outStatus.pointee = .haveData
            return sourceBuffer
        }

        if status == .error {
            throw LiveSpeechBridgeError.unavailable(
                conversionError?.localizedDescription ?? "Audio conversion failed."
            )
        }
        return convertedBuffer
    }
}

private struct LiveSpeechEvent {
    let text: String
    let isFinal: Bool
    let startedAtMs: Int64?
    let endedAtMs: Int64?

    var encodedLine: String {
        let finalField = isFinal ? "1" : "0"
        let startField = startedAtMs.map(String.init) ?? ""
        let endField = endedAtMs.map(String.init) ?? ""
        let encodedText = text.data(using: .utf8)?.base64EncodedString() ?? ""
        return "\(finalField)\t\(startField)\t\(endField)\t\(encodedText)"
    }
}
#endif

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
    guard semaphore.wait(timeout: .now() + .seconds(authorizationTimeoutSeconds)) == .success else {
        return authorizationUnsupported
    }
    return mapAuthorizationStatus(resolved)
}

@_cdecl("souz_macos_speech_cancel_recognition")
public func souz_macos_speech_cancel_recognition() {
    activeRecognitionLock.lock()
    let context = activeRecognitionContext
    activeRecognitionLock.unlock()
    context?.cancel()
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
    guard recognizer.supportsOnDeviceRecognition else {
        writeError(
            "\(BridgeError.onDeviceUnsupported.rawValue):On-device speech recognition is not supported for \(localeIdentifier).",
            to: errorBuffer,
            size: errorBufferSize
        )
        return nil
    }

    let request = SFSpeechURLRecognitionRequest(url: URL(fileURLWithPath: String(cString: pathPtr)))
    request.requiresOnDeviceRecognition = true
    request.shouldReportPartialResults = false

    let context = RecognitionContext()
    activeRecognitionLock.lock()
    activeRecognitionContext = context
    activeRecognitionLock.unlock()

    var task: SFSpeechRecognitionTask?

    task = recognizer.recognitionTask(with: request) { result, error in
        if let error {
            context.finishFailure("\(BridgeError.unavailable.rawValue):\(error.localizedDescription)")
            return
        }

        guard let result else {
            return
        }
        if result.isFinal {
            context.finishSuccess(result.bestTranscription.formattedString)
        }
    }
    context.task = task

    let waitResult = context.semaphore.wait(timeout: .now() + .seconds(recognitionTimeoutSeconds))
    if waitResult == .timedOut {
        context.finishFailure("\(BridgeError.unavailable.rawValue):Speech recognition timed out.")
    }

    activeRecognitionLock.lock()
    if activeRecognitionContext === context {
        activeRecognitionContext = nil
    }
    activeRecognitionLock.unlock()

    task?.cancel()

    let (finalText, finalFailure) = context.snapshot()

    if let finalText {
        return strdup(finalText)
    }

    writeError(
        finalFailure ?? "\(BridgeError.unavailable.rawValue):Speech recognition finished without a final result.",
        to: errorBuffer,
        size: errorBufferSize
    )
    return nil
}

@_cdecl("souz_macos_live_speech_is_supported")
public func souz_macos_live_speech_is_supported(_ localePtr: UnsafePointer<CChar>?) -> Int32 {
    guard let localePtr else { return 0 }

#if compiler(>=6.2)
    if #available(macOS 26.0, *) {
        let localeIdentifier = String(cString: localePtr)
        let supported = (try? waitForLiveAsync(timeoutSeconds: liveStartTimeoutSeconds) {
            await LiveSpeechSession.isSupported(localeIdentifier: localeIdentifier)
        }) ?? false
        return supported ? 1 : 0
    }
#endif

    return 0
}

@_cdecl("souz_macos_live_speech_prepare_assets")
public func souz_macos_live_speech_prepare_assets(
    _ localePtr: UnsafePointer<CChar>?,
    _ errorBuffer: UnsafeMutablePointer<CChar>?,
    _ errorBufferSize: Int32
) -> Int32 {
    guard let localePtr else {
        writeError(
            LiveSpeechBridgeError.unavailable("Missing locale.").message,
            to: errorBuffer,
            size: errorBufferSize
        )
        return 0
    }

#if compiler(>=6.2)
    if #available(macOS 26.0, *) {
        do {
            let localeIdentifier = String(cString: localePtr)
            try waitForLiveAsync(timeoutSeconds: liveAssetInstallTimeoutSeconds) {
                try await LiveSpeechSession.prepareAssets(localeIdentifier: localeIdentifier)
            }
            return 1
        } catch {
            writeError(liveSpeechErrorMessage(error), to: errorBuffer, size: errorBufferSize)
            return 0
        }
    }
#endif

    writeError(LiveSpeechBridgeError.unsupportedOS.message, to: errorBuffer, size: errorBufferSize)
    return 0
}

@_cdecl("souz_macos_live_speech_start")
public func souz_macos_live_speech_start(
    _ localePtr: UnsafePointer<CChar>?,
    _ errorBuffer: UnsafeMutablePointer<CChar>?,
    _ errorBufferSize: Int32
) -> Int64 {
    guard let localePtr else {
        writeError(
            LiveSpeechBridgeError.unavailable("Missing locale.").message,
            to: errorBuffer,
            size: errorBufferSize
        )
        return 0
    }

#if compiler(>=6.2)
    if #available(macOS 26.0, *) {
        do {
            let localeIdentifier = String(cString: localePtr)
            let session = try waitForLiveAsync(timeoutSeconds: liveStartTimeoutSeconds) {
                try await LiveSpeechSession.create(localeIdentifier: localeIdentifier)
            }
            return LiveSpeechSessionRegistry.shared.register(session)
        } catch {
            writeError(liveSpeechErrorMessage(error), to: errorBuffer, size: errorBufferSize)
            return 0
        }
    }
#endif

    writeError(LiveSpeechBridgeError.unsupportedOS.message, to: errorBuffer, size: errorBufferSize)
    return 0
}

@_cdecl("souz_macos_live_speech_accept_pcm")
public func souz_macos_live_speech_accept_pcm(
    _ sessionId: Int64,
    _ audioPtr: UnsafePointer<Int8>?,
    _ audioSize: Int32,
    _ sampleRateHz: Int32,
    _ channels: Int32,
    _ bitsPerSample: Int32,
    _ errorBuffer: UnsafeMutablePointer<CChar>?,
    _ errorBufferSize: Int32
) -> Int32 {
#if compiler(>=6.2)
    if #available(macOS 26.0, *) {
        do {
            guard let audioPtr, audioSize >= 0 else {
                throw LiveSpeechBridgeError.unavailable("Missing PCM audio.")
            }
            let audio = Data(bytes: UnsafeRawPointer(audioPtr), count: Int(audioSize))
            let session = try LiveSpeechSessionRegistry.shared.session(sessionId)
            try session.acceptPcm(
                audio: audio,
                sampleRateHz: sampleRateHz,
                channels: channels,
                bitsPerSample: bitsPerSample
            )
            return 1
        } catch {
            writeError(liveSpeechErrorMessage(error), to: errorBuffer, size: errorBufferSize)
            return 0
        }
    }
#endif

    writeError(LiveSpeechBridgeError.unsupportedOS.message, to: errorBuffer, size: errorBufferSize)
    return 0
}

@_cdecl("souz_macos_live_speech_poll_events")
public func souz_macos_live_speech_poll_events(
    _ sessionId: Int64,
    _ errorBuffer: UnsafeMutablePointer<CChar>?,
    _ errorBufferSize: Int32
) -> UnsafeMutablePointer<CChar>? {
#if compiler(>=6.2)
    if #available(macOS 26.0, *) {
        do {
            let session = try LiveSpeechSessionRegistry.shared.session(sessionId)
            return strdup(try session.pollEvents())
        } catch {
            writeError(liveSpeechErrorMessage(error), to: errorBuffer, size: errorBufferSize)
            return nil
        }
    }
#endif

    writeError(LiveSpeechBridgeError.unsupportedOS.message, to: errorBuffer, size: errorBufferSize)
    return nil
}

@_cdecl("souz_macos_live_speech_finalize_and_finish")
public func souz_macos_live_speech_finalize_and_finish(
    _ sessionId: Int64,
    _ errorBuffer: UnsafeMutablePointer<CChar>?,
    _ errorBufferSize: Int32
) -> UnsafeMutablePointer<CChar>? {
#if compiler(>=6.2)
    if #available(macOS 26.0, *) {
        do {
            let session = try LiveSpeechSessionRegistry.shared.session(sessionId)
            defer { LiveSpeechSessionRegistry.shared.remove(sessionId) }
            let events = try waitForLiveAsync(timeoutSeconds: liveFinishTimeoutSeconds) {
                try await session.finalizeAndFinish()
            }
            return strdup(events)
        } catch {
            LiveSpeechSessionRegistry.shared.remove(sessionId)
            writeError(liveSpeechErrorMessage(error), to: errorBuffer, size: errorBufferSize)
            return nil
        }
    }
#endif

    writeError(LiveSpeechBridgeError.unsupportedOS.message, to: errorBuffer, size: errorBufferSize)
    return nil
}

@_cdecl("souz_macos_live_speech_cancel")
public func souz_macos_live_speech_cancel(
    _ sessionId: Int64,
    _ errorBuffer: UnsafeMutablePointer<CChar>?,
    _ errorBufferSize: Int32
) -> Int32 {
#if compiler(>=6.2)
    if #available(macOS 26.0, *) {
        do {
            let session = try LiveSpeechSessionRegistry.shared.session(sessionId)
            LiveSpeechSessionRegistry.shared.remove(sessionId)
            try waitForLiveAsync(timeoutSeconds: liveStartTimeoutSeconds) {
                await session.cancel()
            }
            return 1
        } catch {
            writeError(liveSpeechErrorMessage(error), to: errorBuffer, size: errorBufferSize)
            return 0
        }
    }
#endif

    writeError(LiveSpeechBridgeError.unsupportedOS.message, to: errorBuffer, size: errorBufferSize)
    return 0
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
    let requested = normalizedLocaleIdentifier(localeIdentifier)
    return SFSpeechRecognizer.supportedLocales().contains { supported in
        normalizedLocaleIdentifier(supported.identifier) == requested
    }
}

private func normalizedLocaleIdentifier(_ identifier: String) -> String {
    Locale.canonicalIdentifier(from: identifier)
        .replacingOccurrences(of: "_", with: "-")
        .lowercased()
}

private final class AsyncResultBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: Result<T, Error>?

    func set(_ result: Result<T, Error>) {
        lock.lock()
        self.result = result
        lock.unlock()
    }

    func get() -> Result<T, Error>? {
        lock.lock()
        defer { lock.unlock() }
        return result
    }
}

private func waitForLiveAsync<T>(
    timeoutSeconds: Int,
    _ operation: @escaping () async throws -> T
) throws -> T {
    let semaphore = DispatchSemaphore(value: 0)
    let box = AsyncResultBox<T>()

    Task {
        do {
            box.set(.success(try await operation()))
        } catch {
            box.set(.failure(error))
        }
        semaphore.signal()
    }

    guard semaphore.wait(timeout: .now() + .seconds(timeoutSeconds)) == .success else {
        throw LiveSpeechBridgeError.unavailable("Live speech transcription timed out.")
    }
    guard let result = box.get() else {
        throw LiveSpeechBridgeError.unavailable("Live speech transcription finished without a result.")
    }
    return try result.get()
}

private func liveSpeechErrorMessage(_ error: Error) -> String {
    if let liveError = error as? LiveSpeechBridgeError {
        return liveError.message
    }
    if error is CancellationError {
        return "LOCAL_MACOS_LIVE_STT:UNAVAILABLE:Live speech transcription was cancelled."
    }
    return "LOCAL_MACOS_LIVE_STT:UNAVAILABLE:\(error.localizedDescription)"
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
