package ru.souz.android.python

import android.os.Bundle
import ru.souz.runtime.sandbox.SandboxCommandResult

internal data class PythonSkillExecutionRequest(
    val script: String?,
    val scriptPath: String?,
    val args: List<String>,
    val workingDirectory: String?,
    val environmentKeys: List<String>,
    val environmentValues: List<String>,
    val stdin: String?,
    val timeoutMillis: Long,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(PythonSkillIpc.KEY_SCRIPT, script)
        putString(PythonSkillIpc.KEY_SCRIPT_PATH, scriptPath)
        putStringArrayList(PythonSkillIpc.KEY_ARGS, ArrayList(args))
        putString(PythonSkillIpc.KEY_WORKING_DIRECTORY, workingDirectory)
        putStringArrayList(PythonSkillIpc.KEY_ENVIRONMENT_KEYS, ArrayList(environmentKeys))
        putStringArrayList(PythonSkillIpc.KEY_ENVIRONMENT_VALUES, ArrayList(environmentValues))
        putString(PythonSkillIpc.KEY_STDIN, stdin)
        putLong(PythonSkillIpc.KEY_TIMEOUT_MILLIS, timeoutMillis)
    }

    companion object {
        fun fromBundle(bundle: Bundle): PythonSkillExecutionRequest = PythonSkillExecutionRequest(
            script = bundle.getString(PythonSkillIpc.KEY_SCRIPT),
            scriptPath = bundle.getString(PythonSkillIpc.KEY_SCRIPT_PATH),
            args = bundle.getStringArrayList(PythonSkillIpc.KEY_ARGS).orEmpty(),
            workingDirectory = bundle.getString(PythonSkillIpc.KEY_WORKING_DIRECTORY),
            environmentKeys = bundle.getStringArrayList(PythonSkillIpc.KEY_ENVIRONMENT_KEYS).orEmpty(),
            environmentValues = bundle.getStringArrayList(PythonSkillIpc.KEY_ENVIRONMENT_VALUES).orEmpty(),
            stdin = bundle.getString(PythonSkillIpc.KEY_STDIN),
            timeoutMillis = bundle.getLong(PythonSkillIpc.KEY_TIMEOUT_MILLIS, 0L),
        )
    }
}

internal object PythonSkillIpc {
    const val MSG_EXECUTE = 1
    const val RESULT_STARTED = 1
    const val RESULT_COMMAND = 2

    const val KEY_RESULT_RECEIVER = "resultReceiver"
    const val KEY_PID = "pid"
    const val KEY_SCRIPT = "script"
    const val KEY_SCRIPT_PATH = "scriptPath"
    const val KEY_ARGS = "args"
    const val KEY_WORKING_DIRECTORY = "workingDirectory"
    const val KEY_ENVIRONMENT_KEYS = "environmentKeys"
    const val KEY_ENVIRONMENT_VALUES = "environmentValues"
    const val KEY_STDIN = "stdin"
    const val KEY_TIMEOUT_MILLIS = "timeoutMillis"
    const val KEY_EXIT_CODE = "exitCode"
    const val KEY_STDOUT = "stdout"
    const val KEY_STDERR = "stderr"
    const val KEY_TIMED_OUT = "timedOut"

    fun SandboxCommandResult.toBundle(): Bundle = Bundle().apply {
        putInt(KEY_EXIT_CODE, exitCode)
        putString(KEY_STDOUT, stdout)
        putString(KEY_STDERR, stderr)
        putBoolean(KEY_TIMED_OUT, timedOut)
    }

    fun resultFromBundle(bundle: Bundle): SandboxCommandResult = SandboxCommandResult(
        exitCode = bundle.getInt(KEY_EXIT_CODE),
        stdout = bundle.getString(KEY_STDOUT).orEmpty(),
        stderr = bundle.getString(KEY_STDERR).orEmpty(),
        timedOut = bundle.getBoolean(KEY_TIMED_OUT, false),
    )
}
