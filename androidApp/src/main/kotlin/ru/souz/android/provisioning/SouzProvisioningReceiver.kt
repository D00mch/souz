package ru.souz.android.provisioning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.slf4j.LoggerFactory
import ru.souz.android.settings.AndroidSettingsProvider

/**
 * Provisions credentials from `adb shell am broadcast`, so a freshly flashed device does not need
 * them typed in with a remote control.
 *
 * The manifest requires senders to hold WRITE_SECURE_SETTINGS, which shell and system hold but
 * installed apps cannot: without that gate any app could point the assistant at its own proxy.
 * Values are never logged.
 */
class SouzProvisioningReceiver : BroadcastReceiver() {
    private val l = LoggerFactory.getLogger(SouzProvisioningReceiver::class.java)

    override fun onReceive(context: Context, intent: Intent) {
        val settings = AndroidSettingsProvider(context.applicationContext)
        val applied = mutableListOf<String>()

        fun apply(extra: String, assign: (String) -> Unit) {
            val value = intent.getStringExtra(extra) ?: return
            assign(value.trim())
            applied += extra
        }

        apply(EXTRA_GIGACHAT) { settings.gigaChatKey = it }
        apply(EXTRA_AITUNNEL) { settings.aiTunnelKey = it }
        apply(EXTRA_OPENAI) { settings.openaiKey = it }
        apply(EXTRA_OPENAI_BASE_URL) { settings.openaiBaseUrl = it }
        apply(EXTRA_OPENAI_MODEL) { settings.openaiModel = it }
        apply(EXTRA_SALUTE_SPEECH) { settings.saluteSpeechKey = it }

        val summary = if (applied.isEmpty()) {
            "No supported extras. Expected any of: ${SUPPORTED_EXTRAS.joinToString()}"
        } else {
            "Applied: ${applied.joinToString()}"
        }
        l.info("Provisioning broadcast. {}", summary)
        resultCode = if (applied.isEmpty()) RESULT_NOTHING_APPLIED else RESULT_APPLIED
        resultData = summary
    }

    private companion object {
        const val EXTRA_GIGACHAT = "gigachat"
        const val EXTRA_AITUNNEL = "aitunnel"
        const val EXTRA_OPENAI = "openai"
        const val EXTRA_OPENAI_BASE_URL = "openai_base_url"
        const val EXTRA_OPENAI_MODEL = "openai_model"
        const val EXTRA_SALUTE_SPEECH = "salutespeech"

        val SUPPORTED_EXTRAS = listOf(
            EXTRA_GIGACHAT,
            EXTRA_AITUNNEL,
            EXTRA_OPENAI,
            EXTRA_OPENAI_BASE_URL,
            EXTRA_OPENAI_MODEL,
            EXTRA_SALUTE_SPEECH,
        )

        const val RESULT_APPLIED = 0
        const val RESULT_NOTHING_APPLIED = 1
    }
}
