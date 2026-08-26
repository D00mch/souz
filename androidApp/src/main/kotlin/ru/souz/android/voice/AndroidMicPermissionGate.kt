package ru.souz.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidMicPermissionGate(context: Context) : MicPermissionGate {
    private val appContext = context.applicationContext
    private var requester: ((onResult: (Boolean) -> Unit) -> Unit)? = null

    fun bindRequester(request: ((onResult: (Boolean) -> Unit) -> Unit)?) {
        requester = request
    }

    override suspend fun ensureMicrophonePermission(): Boolean {
        if (isGranted()) return true
        val request = requester ?: return false
        return suspendCancellableCoroutine { continuation ->
            request { granted -> continuation.resume(granted) }
        }
    }

    private fun isGranted(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
