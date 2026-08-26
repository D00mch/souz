package ru.souz.android.ui

import androidx.compose.runtime.Composable
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.ui.android.SouzAndroidSharedUiApp

@Composable
fun SouzAndroidApp(
    agentRuntime: AndroidAgentRuntime,
    voiceTrigger: Int = 0,
) {
    SouzAndroidSharedUiApp(di = agentRuntime.di, voiceTrigger = voiceTrigger)
}
