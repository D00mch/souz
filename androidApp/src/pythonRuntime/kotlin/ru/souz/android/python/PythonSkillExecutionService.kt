package ru.souz.android.python

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.ResultReceiver
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.souz.android.python.PythonSkillIpc.toBundle
import ru.souz.runtime.sandbox.SandboxCommandResult

class PythonSkillExecutionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executionMutex = Mutex()
    private val messenger = Messenger(IncomingHandler(this))

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun execute(bundle: Bundle) {
        val receiver = bundle.resultReceiver() ?: return
        val request = PythonSkillExecutionRequest.fromBundle(bundle)
        receiver.send(
            PythonSkillIpc.RESULT_STARTED,
            Bundle().apply { putInt(PythonSkillIpc.KEY_PID, Process.myPid()) },
        )
        serviceScope.launch {
            val result = executionMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching { runPython(request) }
                        .getOrElse { error ->
                            SandboxCommandResult(
                                exitCode = 1,
                                stdout = "",
                                stderr = error.stackTraceToString(),
                                timedOut = false,
                            )
                        }
                }
            }
            receiver.send(PythonSkillIpc.RESULT_COMMAND, result.toBundle())
            stopSelf()
        }
    }

    private fun runPython(request: PythonSkillExecutionRequest): SandboxCommandResult {
        val resultJson = python()
            .getModule(PYTHON_BRIDGE_MODULE)
            .callAttr(
                PYTHON_BRIDGE_FUNCTION,
                request.script,
                request.scriptPath,
                request.args.toTypedArray(),
                request.workingDirectory,
                request.environmentKeys.toTypedArray(),
                request.environmentValues.toTypedArray(),
                request.stdin,
                request.timeoutMillis,
            )
            .toString()
        return resultJson.toSandboxCommandResult()
    }

    private fun python(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(applicationContext))
        }
        return Python.getInstance()
    }

    private fun String.toSandboxCommandResult(): SandboxCommandResult {
        val json = JSONObject(this)
        return SandboxCommandResult(
            exitCode = json.getInt(PythonSkillIpc.KEY_EXIT_CODE),
            stdout = json.optString(PythonSkillIpc.KEY_STDOUT, ""),
            stderr = json.optString(PythonSkillIpc.KEY_STDERR, ""),
            timedOut = json.optBoolean(PythonSkillIpc.KEY_TIMED_OUT, false),
        )
    }

    private class IncomingHandler(
        private val service: PythonSkillExecutionService,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                PythonSkillIpc.MSG_EXECUTE -> service.execute(msg.data)
                else -> super.handleMessage(msg)
            }
        }
    }

    private companion object {
        const val PYTHON_BRIDGE_MODULE = "souz_skill_runner"
        const val PYTHON_BRIDGE_FUNCTION = "run_skill_command"
    }
}

@Suppress("DEPRECATION")
private fun Bundle.resultReceiver(): ResultReceiver? {
    classLoader = PythonSkillExecutionService::class.java.classLoader
    return getParcelable(PythonSkillIpc.KEY_RESULT_RECEIVER)
}
