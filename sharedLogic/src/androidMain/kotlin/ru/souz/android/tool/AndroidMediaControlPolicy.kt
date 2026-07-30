package ru.souz.android.tool

enum class MediaCommand {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    STOP,
    NEXT,
    PREVIOUS,
    FAST_FORWARD,
    REWIND,
}

data class MediaCommandDispatchPlan(
    val primaryCommand: MediaCommand,
    val fallbackCommandIfStillActive: MediaCommand? = null,
)

fun mediaCommandDispatchPlan(command: MediaCommand): MediaCommandDispatchPlan = when (command) {
    MediaCommand.PAUSE -> MediaCommandDispatchPlan(
        primaryCommand = MediaCommand.PAUSE,
        fallbackCommandIfStillActive = MediaCommand.PLAY_PAUSE,
    )
    MediaCommand.STOP -> MediaCommandDispatchPlan(
        primaryCommand = MediaCommand.STOP,
        fallbackCommandIfStillActive = MediaCommand.PLAY_PAUSE,
    )
    else -> MediaCommandDispatchPlan(command)
}
