package ru.souz.android

import android.app.Application
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.android.settings.AndroidSettingsProvider

class SouzApplication : Application() {
    private val runtimeOwner = ApplicationRuntimeOwner {
        AndroidAgentRuntime(
            context = this,
            settings = AndroidSettingsProvider(this),
        )
    }
    val agentRuntime: AndroidAgentRuntime
        get() = runtimeOwner.runtime

    override fun onTerminate() {
        runtimeOwner.close()
        super.onTerminate()
    }
}
