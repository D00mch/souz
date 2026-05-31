package ru.souz.ui.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.souz.service.telegram.TelegramAuthState

interface TelegramUiService {
    val authState: StateFlow<TelegramAuthState>

    fun isSupported(): Boolean
    suspend fun submitPhoneNumber(phoneNumber: String)
    fun submitLoginCode(code: String)
    fun submitTwoFaPassword(password: String)
    suspend fun requestCodeAgain(phoneNumber: String)
    suspend fun cancelAuth()
    suspend fun logout()
    suspend fun createControlBot(forceNew: Boolean = false)
    suspend fun fetchActiveBotUsernameFromBotFather(): String?
    suspend fun deleteControlBot(forceNew: Boolean = false)
}

data class TelegramControlIncomingMessage(
    val text: String,
    val responseDeferred: CompletableDeferred<String>,
    val isVoice: Boolean = false,
)

interface TelegramControlBot {
    val incomingMessages: Flow<TelegramControlIncomingMessage>
    val cleanCommands: Flow<Unit>

    fun start()
    fun close()
    fun restartPolling()
    fun stopPolling()
}
