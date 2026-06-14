package ru.souz.android.ui

import androidx.compose.runtime.Composable
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.ui.android.SouzAndroidSharedUiApp

@Composable
fun SouzAndroidApp(
    agentRuntime: AndroidAgentRuntime,
) {
    SouzAndroidSharedUiApp(di = agentRuntime.di)
}
