# Audio recording

`ActiveSoundRecorderImpl` serves both push-to-talk (`InMemoryAudioRecorder`) and
ambient streaming (`ActiveRecorderPcmAudioFrameSource`). Its blocking Java Sound
capture job can survive stop/start cycles on the prepared line.

Lifecycle transitions use `stateMutex`. The current session channel is published
through `MutableStateFlow`; each completed frame reads its thread-safe snapshot
before `trySend`. Do not cache that destination across reads or hold the lifecycle
mutex around the capture loop: stop must detach the old channel while a native
read is blocked, and a later recording uses a different channel.

Sending occurs outside the lifecycle critical section. Stop can close a channel
after capture takes its snapshot; `trySend` safely rejects a closed or full channel.
The 3,072-frame buffer retains its non-blocking overflow behavior. `frames()` and
`stopRecording()` consume the same channel; stop returns the frames still queued.

Run `./gradlew :desktopApp:test --tests 'ru.souz.service.audio.*'` for controlled
blocking-read, session-lifecycle, buffer, and capture-lock-count regressions.
