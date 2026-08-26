package ru.souz.android

import android.app.Application
import android.content.Context
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.android.settings.AndroidSettingsProvider

class SouzApplication : Application() {
    val agentRuntime: AndroidAgentRuntime by lazy {
        AndroidAgentRuntime(applicationContext, AndroidSettingsProvider(applicationContext))
    }
}

val Context.souzAgentRuntime: AndroidAgentRuntime
    get() = (applicationContext as SouzApplication).agentRuntime
