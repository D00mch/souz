package ru.souz.android.python

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.ResultReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.souz.android.sandbox.AndroidPythonCommandRunner
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.tool.BadInputException

class ChaquopyPythonSkillRunner(
    context: Context,
) : AndroidPythonCommandRunner {
    private val appContext = context.applicationContext

    override suspend fun execute(
        request: SandboxCommandRequest,
        fileSystem: SandboxFileSystem,
    ): SandboxCommandResult = withContext(Dispatchers.IO) {
        val executionRequest = request.toPythonSkillExecutionRequest(fileSystem)
        executionMutex.withLock {
            executeInService(executionRequest)
        }
    }

    private suspend fun executeInService(
        request: PythonSkillExecutionRequest,
    ): SandboxCommandResult {
        val started = CompletableDeferred<Int>()
        val result = CompletableDeferred<SandboxCommandResult>()
        var bound = false
        var servicePid: Int? = null

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val data = resultData ?: Bundle.EMPTY
                when (resultCode) {
                    PythonSkillIpc.RESULT_STARTED -> {
                        val pid = data.getInt(PythonSkillIpc.KEY_PID, 0)
                        started.complete(pid)
                    }

                    PythonSkillIpc.RESULT_COMMAND -> {
                        result.complete(PythonSkillIpc.resultFromBundle(data))
                    }
                }
            }
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val message = Message.obtain(null, PythonSkillIpc.MSG_EXECUTE).apply {
                    data = request.toBundle().apply {
                        putParcelable(PythonSkillIpc.KEY_RESULT_RECEIVER, receiver)
                    }
                }
                runCatching { Messenger(service).send(message) }
                    .onFailure { error ->
                        completeServiceFailure(started, result, "Failed to send Python skill command.", error)
                    }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                completeServiceFailure(started, result, "Python skill service disconnected.", null)
            }

            override fun onBindingDied(name: ComponentName) {
                completeServiceFailure(started, result, "Python skill service binding died.", null)
            }
        }

        return try {
            val intent = Intent(appContext, PythonSkillExecutionService::class.java)
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                return SandboxCommandResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = "Failed to bind Python skill service.",
                    timedOut = false,
                )
            }

            val pid = withTimeout(SERVICE_START_TIMEOUT_MILLIS) {
                started.await()
            }
            servicePid = pid.takeIf { it > 0 }

            val timeoutMillis = request.timeoutMillis.takeIf { it > 0L }
            if (timeoutMillis == null) {
                result.await()
            } else {
                withTimeout(timeoutMillis) {
                    result.await()
                }
            }
        } catch (_: TimeoutCancellationException) {
            timeoutResult(request.timeoutMillis)
        } catch (error: Throwable) {
            SandboxCommandResult(
                exitCode = 1,
                stdout = "",
                stderr = error.stackTraceToString(),
                timedOut = false,
            )
        } finally {
            cleanupService(bound, connection, servicePid)
        }
    }

    private fun SandboxCommandRequest.toPythonSkillExecutionRequest(
        fileSystem: SandboxFileSystem,
    ): PythonSkillExecutionRequest {
        val resolvedScriptPath = scriptPath
            ?.let(fileSystem::resolveExistingFile)
            ?.path
        if (resolvedScriptPath == null && script == null) {
            throw BadInputException("script is required for PYTHON runtime.")
        }

        val resolvedWorkingDirectory = workingDirectory
            ?.let(fileSystem::resolveExistingDirectory)
            ?.path
        val environmentEntries = environment.entries.sortedBy { it.key }

        return PythonSkillExecutionRequest(
            script = script,
            scriptPath = resolvedScriptPath,
            args = args,
            workingDirectory = resolvedWorkingDirectory,
            environmentKeys = environmentEntries.map { it.key },
            environmentValues = environmentEntries.map { it.value },
            stdin = stdin,
            timeoutMillis = timeoutMillis ?: 0L,
        )
    }

    private fun cleanupService(
        bound: Boolean,
        connection: ServiceConnection,
        servicePid: Int?,
    ) {
        if (bound) {
            runCatching { appContext.unbindService(connection) }
        }
        runCatching {
            appContext.stopService(Intent(appContext, PythonSkillExecutionService::class.java))
        }
        servicePid
            ?.takeIf { it > 0 && it != Process.myPid() }
            ?.let(Process::killProcess)
    }

    private fun timeoutResult(timeoutMillis: Long): SandboxCommandResult {
        val timeoutDescription = if (timeoutMillis > 0L) "$timeoutMillis ms" else "the configured timeout"
        return SandboxCommandResult(
            exitCode = -1,
            stdout = "",
            stderr = "Python skill timed out after $timeoutDescription.",
            timedOut = true,
        )
    }

    private fun completeServiceFailure(
        started: CompletableDeferred<Int>,
        result: CompletableDeferred<SandboxCommandResult>,
        message: String,
        cause: Throwable?,
    ) {
        val error = IllegalStateException(message, cause)
        started.completeExceptionally(error)
        result.completeExceptionally(error)
    }

    private companion object {
        const val SERVICE_START_TIMEOUT_MILLIS = 10_000L
        val executionMutex = Mutex()
    }
}
