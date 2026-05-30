package ru.souz.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ru.souz.android.llms.AndroidOpenAiClient
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.android.storage.AndroidChatDatabase
import ru.souz.android.ui.SouzAndroidApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = AndroidSettingsProvider(applicationContext)
        val chatDatabase = AndroidChatDatabase(applicationContext)
        val chatClient = AndroidOpenAiClient(settings)

        setContent {
            SouzAndroidApp(
                settings = settings,
                chatDatabase = chatDatabase,
                chatClient = chatClient,
            )
        }
    }
}
