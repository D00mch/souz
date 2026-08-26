package ru.souz.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.android.ui.SouzAndroidApp
import ru.souz.android.voice.AndroidMicPermissionGate

class MainActivity : ComponentActivity() {
    private val permissionRequestLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            val pendingRequest = pendingPermissionRequest ?: return@registerForActivityResult
            pendingPermissionRequest = null

            val resolvedResults = pendingRequest.permissions.associateWith { permission ->
                grantResults[permission] ?: isPermissionGranted(permission)
            }
            pendingRequest.onResult(
                AndroidPermissionResult(
                    purpose = pendingRequest.purpose,
                    grantResults = resolvedResults,
                )
            )
        }

    private var pendingPermissionRequest: PendingAndroidPermissionRequest? = null

    private var micPermissionGate: AndroidMicPermissionGate? = null

    private var voiceTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeVoiceTrigger(intent)

        setContent {
            var agentRuntime by remember { mutableStateOf<AndroidAgentRuntime?>(null) }

            LaunchedEffect(Unit) {
                val runtime = awaitSouzAgentRuntime()
                micPermissionGate = runtime.di.direct.instance<AndroidMicPermissionGate>().apply {
                    bindRequester { onResult ->
                        val requested = requestPermissionsFor(AndroidPermissionPurpose.VoiceInput) {
                            onResult(it.allGranted)
                        }
                        if (!requested) onResult(false)
                    }
                }
                agentRuntime = runtime
            }

            agentRuntime
                ?.let { SouzAndroidApp(agentRuntime = it, voiceTrigger = voiceTrigger) }
                ?: StartingUpScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeVoiceTrigger(intent)
    }

    private fun consumeVoiceTrigger(intent: Intent?) {
        if (intent?.action in VOICE_TRIGGER_ACTIONS) voiceTrigger++
    }

    override fun onDestroy() {
        micPermissionGate?.bindRequester(null)
        super.onDestroy()
    }

    private fun hasPermissionsFor(purpose: AndroidPermissionPurpose): Boolean =
        permissionsFor(purpose).all(::isPermissionGranted)

    private fun requestPermissionsFor(
        purpose: AndroidPermissionPurpose,
        onResult: (AndroidPermissionResult) -> Unit,
    ): Boolean {
        if (pendingPermissionRequest != null) return false

        val permissions = permissionsFor(purpose)
        val missingPermissions = permissions.filterNot(::isPermissionGranted)
        if (missingPermissions.isEmpty()) {
            onResult(
                AndroidPermissionResult(
                    purpose = purpose,
                    grantResults = permissions.associateWith { true },
                )
            )
            return true
        }

        pendingPermissionRequest = PendingAndroidPermissionRequest(
            purpose = purpose,
            permissions = permissions,
            onResult = onResult,
        )
        permissionRequestLauncher.launch(missingPermissions.toTypedArray())
        return true
    }

    private fun permissionsFor(purpose: AndroidPermissionPurpose): List<String> =
        when (purpose) {
            AndroidPermissionPurpose.VoiceInput -> listOf(Manifest.permission.RECORD_AUDIO)
            AndroidPermissionPurpose.CameraCapture -> listOf(Manifest.permission.CAMERA)
            AndroidPermissionPurpose.Notifications -> notificationPermissions()
            AndroidPermissionPurpose.MediaLibrary -> mediaLibraryPermissions()
        }

    private fun notificationPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    private fun mediaLibraryPermissions(): List<String> =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )

            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun isPermissionGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private sealed interface AndroidPermissionPurpose {
        data object VoiceInput : AndroidPermissionPurpose
        data object CameraCapture : AndroidPermissionPurpose
        data object Notifications : AndroidPermissionPurpose
        data object MediaLibrary : AndroidPermissionPurpose
    }

    private data class PendingAndroidPermissionRequest(
        val purpose: AndroidPermissionPurpose,
        val permissions: List<String>,
        val onResult: (AndroidPermissionResult) -> Unit,
    )

    private companion object {
        val VOICE_TRIGGER_ACTIONS = setOf(
            Intent.ACTION_ASSIST,
            Intent.ACTION_VOICE_COMMAND,
            "android.intent.action.VOICE_ASSIST",
            "android.speech.action.VOICE_SEARCH_HANDS_FREE",
        )
    }

    private data class AndroidPermissionResult(
        val purpose: AndroidPermissionPurpose,
        val grantResults: Map<String, Boolean>,
    ) {
        val allGranted: Boolean = grantResults.values.all { it }
    }
}

@Composable
private fun StartingUpScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
