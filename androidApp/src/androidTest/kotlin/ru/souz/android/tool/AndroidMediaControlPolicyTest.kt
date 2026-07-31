package ru.souz.android.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidMediaControlPolicyTest {
    @Test
    fun stopAndPauseUseDirectKeyWithPlayPauseFallbackForSberMediaSessions() {
        assertEquals(
            MediaCommandDispatchPlan(
                primaryCommand = MediaCommand.STOP,
                fallbackCommandIfStillActive = MediaCommand.PLAY_PAUSE,
            ),
            mediaCommandDispatchPlan(MediaCommand.STOP),
        )
        assertEquals(
            MediaCommandDispatchPlan(
                primaryCommand = MediaCommand.PAUSE,
                fallbackCommandIfStillActive = MediaCommand.PLAY_PAUSE,
            ),
            mediaCommandDispatchPlan(MediaCommand.PAUSE),
        )
    }

    @Test
    fun explicitPlaybackAndNavigationCommandsKeepDirectKeyMapping() {
        assertEquals(MediaCommandDispatchPlan(MediaCommand.PLAY), mediaCommandDispatchPlan(MediaCommand.PLAY))
        assertEquals(
            MediaCommandDispatchPlan(MediaCommand.PLAY_PAUSE),
            mediaCommandDispatchPlan(MediaCommand.PLAY_PAUSE),
        )
        assertEquals(MediaCommandDispatchPlan(MediaCommand.NEXT), mediaCommandDispatchPlan(MediaCommand.NEXT))
        assertEquals(
            MediaCommandDispatchPlan(MediaCommand.PREVIOUS),
            mediaCommandDispatchPlan(MediaCommand.PREVIOUS),
        )
        assertEquals(
            MediaCommandDispatchPlan(MediaCommand.FAST_FORWARD),
            mediaCommandDispatchPlan(MediaCommand.FAST_FORWARD),
        )
        assertEquals(MediaCommandDispatchPlan(MediaCommand.REWIND), mediaCommandDispatchPlan(MediaCommand.REWIND))
    }
}
