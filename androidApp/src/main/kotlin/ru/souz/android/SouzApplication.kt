package ru.souz.android

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.android.settings.AndroidSettingsProvider

/**
 * Building the dependency graph takes seconds on TV hardware, so it starts in the background as
 * soon as the process does. Touching it from the main thread would stall the first frame long
 * enough for input dispatch to time out.
 */
class SouzApplication : Application() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val runtime: Deferred<AndroidAgentRuntime> = scope.async(start = CoroutineStart.LAZY) {
        AndroidAgentRuntime(applicationContext, AndroidSettingsProvider(applicationContext))
    }

    override fun onCreate() {
        super.onCreate()
        runtime.start()
    }

    suspend fun awaitAgentRuntime(): AndroidAgentRuntime = runtime.await()
}

val Context.souzApplication: SouzApplication
    get() = applicationContext as SouzApplication

suspend fun Context.awaitSouzAgentRuntime(): AndroidAgentRuntime = souzApplication.awaitAgentRuntime()
