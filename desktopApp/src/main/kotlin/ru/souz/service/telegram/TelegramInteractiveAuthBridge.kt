package ru.souz.service.telegram

import it.tdlight.client.AuthenticationData
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.ClientInteraction
import it.tdlight.client.InputParameter
import it.tdlight.client.ParameterInfo
import it.tdlight.client.ParameterInfoCode
import it.tdlight.client.ParameterInfoPasswordHint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

internal class TelegramInteractiveAuthBridge(
    private val authStateFlow: MutableStateFlow<TelegramAuthState>,
) : AuthenticationSupplier<AuthenticationData>, ClientInteraction {

    private val phoneFutureRef = AtomicReference<CompletableFuture<AuthenticationData>?>(null)
    private val codeFutureRef = AtomicReference<CompletableFuture<String>?>(null)
    private val passwordFutureRef = AtomicReference<CompletableFuture<String>?>(null)
    private val queuedPhoneRef = AtomicReference<String?>(null)
    private val queuedCodeRef = AtomicReference<String?>(null)
    private val queuedPasswordRef = AtomicReference<String?>(null)

    override fun get(): CompletableFuture<AuthenticationData> {
        val queuedPhone = queuedPhoneRef.getAndSet(null)
        if (!queuedPhone.isNullOrBlank()) {
            @Suppress("UNCHECKED_CAST")
            val authData = AuthenticationSupplier.user(queuedPhone) as AuthenticationData
            return CompletableFuture.completedFuture(authData)
        }

        val created = CompletableFuture<AuthenticationData>()
        phoneFutureRef.set(created)
        authStateFlow.update {
            it.copy(
                step = TelegramAuthStep.WAIT_PHONE,
                isBusy = false,
                errorMessage = null,
            )
        }
        return created
    }

    override fun onParameterRequest(parameter: InputParameter, parameterInfo: ParameterInfo): CompletableFuture<String> {
        return when (parameter) {
            InputParameter.ASK_CODE -> {
                val hintPhone = (parameterInfo as? ParameterInfoCode)?.phoneNumber
                val queuedCode = queuedCodeRef.getAndSet(null)
                if (!queuedCode.isNullOrBlank()) {
                    authStateFlow.update {
                        it.copy(
                            step = TelegramAuthStep.WAIT_CODE,
                            codeHint = hintPhone,
                            isBusy = true,
                            errorMessage = null,
                        )
                    }
                    return CompletableFuture.completedFuture(queuedCode)
                }

                val future = CompletableFuture<String>()
                codeFutureRef.set(future)
                authStateFlow.update {
                    it.copy(
                        step = TelegramAuthStep.WAIT_CODE,
                        codeHint = hintPhone,
                        isBusy = false,
                        errorMessage = null,
                    )
                }
                future
            }

            InputParameter.ASK_PASSWORD -> {
                val hint = (parameterInfo as? ParameterInfoPasswordHint)?.hint
                val queuedPassword = queuedPasswordRef.getAndSet(null)
                if (!queuedPassword.isNullOrBlank()) {
                    authStateFlow.update {
                        it.copy(
                            step = TelegramAuthStep.WAIT_PASSWORD,
                            passwordHint = hint,
                            isBusy = true,
                            errorMessage = null,
                        )
                    }
                    return CompletableFuture.completedFuture(queuedPassword)
                }

                val future = CompletableFuture<String>()
                passwordFutureRef.set(future)
                authStateFlow.update {
                    it.copy(
                        step = TelegramAuthStep.WAIT_PASSWORD,
                        passwordHint = hint,
                        isBusy = false,
                        errorMessage = null,
                    )
                }
                future
            }

            else -> CompletableFuture.completedFuture("")
        }
    }

    fun providePhone(phone: String) {
        val pending = phoneFutureRef.getAndSet(null)
        if (pending != null && !pending.isDone) {
            @Suppress("UNCHECKED_CAST")
            pending.complete(AuthenticationSupplier.user(phone) as AuthenticationData)
        } else {
            queuedPhoneRef.set(phone)
        }
    }

    fun provideCode(code: String) {
        val pending = codeFutureRef.getAndSet(null)
        if (pending != null && !pending.isDone) {
            pending.complete(code)
        } else {
            queuedCodeRef.set(code)
        }
    }

    fun providePassword(password: String) {
        val pending = passwordFutureRef.getAndSet(null)
        if (pending != null && !pending.isDone) {
            pending.complete(password)
        } else {
            queuedPasswordRef.set(password)
        }
    }

    fun reset() {
        phoneFutureRef.getAndSet(null)?.cancel(true)
        codeFutureRef.getAndSet(null)?.cancel(true)
        passwordFutureRef.getAndSet(null)?.cancel(true)
        queuedPhoneRef.set(null)
        queuedCodeRef.set(null)
        queuedPasswordRef.set(null)
    }
}
