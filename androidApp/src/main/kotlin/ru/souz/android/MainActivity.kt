package ru.souz.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.android.ui.SouzAndroidApp

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = AndroidSettingsProvider(applicationContext)
        val agentRuntime = AndroidAgentRuntime(applicationContext, settings)

        setContent {
            SouzAndroidApp(
                agentRuntime = agentRuntime,
            )
        }
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

    private data class AndroidPermissionResult(
        val purpose: AndroidPermissionPurpose,
        val grantResults: Map<String, Boolean>,
    ) {
        val allGranted: Boolean = grantResults.values.all { it }
    }
}
