@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.main

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.junit.jupiter.api.Assumptions.assumeTrue
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.chat_action_web_search
import souz.composeapp.generated.resources.onboarding_display_text
import souz.composeapp.generated.resources.onboarding_input_permission_request
import souz.composeapp.generated.resources.voice_status_processing_input
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import ru.souz.agent.AgentFacade
import ru.souz.agent.AgentSideEffect
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.AgentSettings
import ru.souz.service.audio.ActiveSoundRecorderImpl
import ru.souz.service.audio.InMemoryAudioRecorder
import ru.souz.service.audio.Say
import ru.souz.db.DesktopInfoRepository
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.TokenLogging
import ru.souz.llms.GigaModel
import ru.souz.llms.GigaResponse
import ru.souz.llms.GigaResponse.FunctionCall
import ru.souz.llms.giga.GigaVoiceAPI
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.service.telegram.TelegramBotController
import ru.souz.telemetry.TelemetryRequestContext
import ru.souz.telemetry.TelemetryRequestSource
import ru.souz.telemetry.TelemetryService
import ru.souz.tool.SelectionApprovalSource
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.files.FilesToolUtil
import ru.souz.ui.main.usecases.FinderPathExtractor
import ru.souz.ui.main.usecases.MainUseCasesFactory
import ru.souz.ui.main.usecases.SaluteSpeechRecognitionProvider
import ru.souz.ui.main.usecases.SpeechRecognitionProvider
import ru.souz.ui.main.usecases.VoiceInputUseCase
import ru.souz.ui.common.FinderService
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainViewModelTest {

    private lateinit var mainDispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        assumeTrue(hasOpenGlRuntime(), "MainViewModelTest requires libGL runtime on Linux CI")

        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)

        mockkConstructor(ActiveSoundRecorderImpl::class)
        every { anyConstructed<ActiveSoundRecorderImpl>().prepare() } just runs
        every { anyConstructed<ActiveSoundRecorderImpl>().startRecording() } just runs
        coEvery { anyConstructed<ActiveSoundRecorderImpl>().stopRecording() } returns ByteArray(0)

        mockkStatic("org.jetbrains.compose.resources.StringResourcesKt")
        mockkStatic("org.jetbrains.compose.resources.StringArrayResourcesKt")
        coEvery { getString(any()) } answers { firstArg<Any>().toString() }
        coEvery { getString(Res.string.chat_action_web_search) } returns "Ищу в интернете: %1\$s"
        coEvery { getStringArray(any()) } returns listOf("tip")

    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `send stop, send drops, canceled first message, and keeps processing state`() = runTest(mainDispatcher) {
        val firstResponse = CompletableDeferred<String>()
        val secondResponse = CompletableDeferred<String>()
        val harness = createHarness(executeBehavior = { input ->
            when (input) {
                "first request" -> firstResponse.await()
                "second request" -> secondResponse.await()
                else -> error("Unexpected input: $input")
            }
        }, onCancelActiveJob = {
            firstResponse.completeExceptionally(CancellationException("Stopped by user"))
        })

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("first request"))

            val firstInProgress = awaitState(viewModel) { it.isProcessing }
            assertTrue(firstInProgress.chatMessages.any { it.isUser && it.text == "first request" })

            viewModel.handleEvent(MainEvent.UserPressStop)

            val afterStop = awaitState(viewModel) { !it.isProcessing }
            assertFalse(afterStop.chatMessages.any { it.text == "first request" })

            viewModel.handleEvent(MainEvent.SendChatMessage("second request"))

            awaitState(viewModel) { state ->
                state.isProcessing && state.chatMessages.any { it.isUser && it.text == "second request" }
            }

            secondResponse.complete("second answer")

            val finalState = awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { !it.isUser && it.text == "second answer" }
            }
            assertEquals(listOf("second request", "second answer"), finalState.chatMessages.map { it.text })
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `stop exits processing when agent cancel is non-cooperative`() = runTest(mainDispatcher) {
        val firstResponse = CompletableDeferred<String>()
        val secondResponse = CompletableDeferred<String>()
        val harness = createHarness(
            executeBehavior = { input ->
                when (input) {
                    "first request" -> firstResponse.await()
                    "second request" -> secondResponse.await()
                    else -> error("Unexpected input: $input")
                }
            },
            onCancelActiveJob = { /* Simulate hung execution that ignores cancel */ },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("first request"))

            awaitState(viewModel) { state ->
                state.isProcessing && state.chatMessages.any { it.isUser && it.text == "first request" }
            }

            viewModel.handleEvent(MainEvent.UserPressStop)

            val afterStop = awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.none { it.text == "first request" }
            }
            assertFalse(afterStop.isProcessing)
            assertFalse(afterStop.chatMessages.any { it.text == "first request" })

            firstResponse.complete("late answer")
            advanceUntilIdle()
            val afterLateCompletion = viewModel.uiState.value
            assertFalse(afterLateCompletion.chatMessages.any { it.text == "late answer" })
            assertFalse(afterLateCompletion.isProcessing)

            viewModel.handleEvent(MainEvent.SendChatMessage("second request"))
            awaitState(viewModel) { state ->
                state.isProcessing && state.chatMessages.any { it.isUser && it.text == "second request" }
            }

            secondResponse.complete("second answer")
            val finalState = awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { !it.isUser && it.text == "second answer" }
            }
            assertEquals(listOf("second request", "second answer"), finalState.chatMessages.map { it.text })
        } finally {
            firstResponse.completeExceptionally(CancellationException("cleanup"))
            secondResponse.completeExceptionally(CancellationException("cleanup"))
            harness.clear()
        }
    }

    @Test
    fun `audio flow event cancels first message and runs second request`() = runTest(mainDispatcher) {
        val firstResponse = CompletableDeferred<String>()
        val secondResponse = CompletableDeferred<String>()
        val harness: TestHarness = createHarness(
            executeBehavior = { input ->
                when (input) {
                    "first request" -> firstResponse.await()
                    "second request" -> secondResponse.await()
                    else -> error("Unexpected input: $input")
                }
            },
            onCancelActiveJob = {
                firstResponse.completeExceptionally(CancellationException("Cancelled by alt press"))
            },
            recognizeBehavior = {
                GigaResponse.RecognizeResponse(result = listOf("second request"))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("first request"))

            awaitState(viewModel) { state ->
                state.isProcessing && state.chatMessages.any { it.isUser && it.text == "first request" }
            }

            emitAudioFlowEvent(viewModel, byteArrayOf(9, 8, 7))
            val secondInProgress = awaitState(viewModel) { state ->
                state.isProcessing && state.chatMessages.any { it.isUser && it.text == "second request" }
            }
            assertFalse(secondInProgress.chatMessages.any { it.text == "first request" })

            secondResponse.complete("second answer")

            val finalState = awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { !it.isUser && it.text == "second answer" }
            }
            assertEquals(listOf("second request", "second answer"), finalState.chatMessages.map { it.text })
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `voice recognition stores draft in input when review is enabled`() = runTest(mainDispatcher) {
        val draft = "voice draft input"
        val harness = createHarness(
            voiceInputReviewEnabled = true,
            recognizeBehavior = {
                GigaResponse.RecognizeResponse(result = listOf(draft))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            emitAudioFlowEvent(viewModel, byteArrayOf(7, 7, 7))

            val state = awaitState(viewModel) { uiState ->
                uiState.pendingVoiceInputDraft == draft && uiState.pendingVoiceInputDraftToken > 0
            }
            assertEquals(draft, state.pendingVoiceInputDraft)
            assertTrue(state.chatMessages.isEmpty())
            assertFalse(state.isProcessing)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `consuming pending voice draft clears it`() = runTest(mainDispatcher) {
        val draft = "voice draft input"
        val harness = createHarness(
            voiceInputReviewEnabled = true,
            recognizeBehavior = { GigaResponse.RecognizeResponse(result = listOf(draft)) },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            emitAudioFlowEvent(viewModel, byteArrayOf(7, 7, 7))
            val stateWithDraft = awaitState(viewModel) { it.pendingVoiceInputDraft == draft }

            viewModel.handleEvent(
                MainEvent.ConsumePendingVoiceInputDraft(token = stateWithDraft.pendingVoiceInputDraftToken)
            )

            val state = awaitState(viewModel) { it.pendingVoiceInputDraft == null }
            assertNull(state.pendingVoiceInputDraft)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `stale consume event does not clear newer pending voice draft`() = runTest(mainDispatcher) {
        val firstDraft = "first voice draft"
        val secondDraft = "second voice draft"
        var call = 0
        val harness = createHarness(
            voiceInputReviewEnabled = true,
            recognizeBehavior = {
                call += 1
                val result = if (call == 1) firstDraft else secondDraft
                GigaResponse.RecognizeResponse(result = listOf(result))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            emitAudioFlowEvent(viewModel, byteArrayOf(1, 2, 3))
            val firstState = awaitState(viewModel) { it.pendingVoiceInputDraft == firstDraft }
            val staleToken = firstState.pendingVoiceInputDraftToken

            emitAudioFlowEvent(viewModel, byteArrayOf(4, 5, 6))
            val secondState = awaitState(viewModel) {
                it.pendingVoiceInputDraft == secondDraft && it.pendingVoiceInputDraftToken > staleToken
            }

            viewModel.handleEvent(MainEvent.ConsumePendingVoiceInputDraft(token = staleToken))
            runCurrent()

            assertEquals(secondDraft, viewModel.uiState.value.pendingVoiceInputDraft)
            assertEquals(secondState.pendingVoiceInputDraftToken, viewModel.uiState.value.pendingVoiceInputDraftToken)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `start listening is ignored while previous voice recognition is still processing`() = runTest(mainDispatcher) {
        val recognitionStarted = CompletableDeferred<Unit>()
        val releaseRecognition = CompletableDeferred<Unit>()
        val harness = createHarness(
            voiceInputReviewEnabled = true,
            recognizeBehavior = {
                recognitionStarted.complete(Unit)
                releaseRecognition.await()
                GigaResponse.RecognizeResponse(result = listOf("final draft"))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            emitAudioFlowEvent(viewModel, byteArrayOf(9, 9, 9))
            recognitionStarted.await()

            viewModel.handleEvent(MainEvent.StartListening)

            val expectedStatus = getString(Res.string.voice_status_processing_input)
            val state = awaitState(viewModel) { it.statusMessage == expectedStatus }
            assertFalse(state.isListening)

            releaseRecognition.complete(Unit)
            runCurrent()
        } finally {
            releaseRecognition.complete(Unit)
            harness.clear()
        }
    }

    @Test
    fun `response while speaking sets isSpeaking true, updates history, completes processing`() =
        runTest(mainDispatcher) {
            val response = CompletableDeferred<String>()
            val harness = createHarness(
                executeBehavior = { input ->
                    if (input != "hello") error("Unexpected input: $input")
                    response.await()
                },
                recognizeBehavior = {
                    GigaResponse.RecognizeResponse(result = listOf("hello"))
                },
            )

            try {
                val viewModel = harness.viewModel
                advanceUntilIdle()

                awaitVoiceRequestStarted(viewModel, byteArrayOf(1, 2, 3)) { state ->
                    state.isProcessing && state.chatMessages.any { it.isUser && it.text == "hello" }
                }

                harness.isSpeakingFlow.value = true
                response.complete("hi there")

                val finalState = awaitState(viewModel) { state ->
                    !state.isProcessing && state.isSpeaking && state.chatMessages.any { !it.isUser && it.text == "hi there" }
                }

                assertTrue(finalState.isSpeaking)
                assertEquals(listOf("hello", "hi there"), finalState.chatMessages.map { it.text })
            } finally {
                harness.clear()
            }
        }

    @Test
    fun `missing input monitoring permission updates status message`() = runTest(mainDispatcher) {
        val previousHeadless = System.getProperty("java.awt.headless")
        System.setProperty("java.awt.headless", "true")
        val harness = createHarness()

        try {
            val viewModel = harness.viewModel
            val expectedPermissionMessage = getString(Res.string.onboarding_input_permission_request)

            val permissionState = awaitState(viewModel) { state ->
                state.statusMessage == expectedPermissionMessage
            }

            assertEquals(expectedPermissionMessage, permissionState.statusMessage)
        } finally {
            if (previousHeadless == null) {
                System.clearProperty("java.awt.headless")
            } else {
                System.setProperty("java.awt.headless", previousHeadless)
            }
            harness.clear()
        }
    }

    @Test
    fun `tool invocation adds transient agent action during processing`() = runTest(mainDispatcher) {
        val response = CompletableDeferred<String>()
        val harness = createHarness(
            executeBehavior = { input ->
                if (input != "hello") error("Unexpected input: $input")
                response.await()
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("hello"))
            awaitState(viewModel) { it.isProcessing }

            harness.sideEffects.emit(
                AgentSideEffect.Fn(
                    FunctionCall("WebSearch", mapOf("query" to "котлин корутины"))
                )
            )

            val inProgress = awaitState(viewModel) {
                it.agentActions.contains("Ищу в интернете: котлин корутины")
            }
            assertEquals(listOf("Ищу в интернете: котлин корутины"), inProgress.agentActions)

            response.complete("done")

            val finalState = awaitState(viewModel) { !it.isProcessing }
            assertEquals(
                listOf("Ищу в интернете: котлин корутины"),
                finalState.chatMessages.last { !it.isUser }.agentActions
            )
            assertTrue(finalState.agentActions.isEmpty())
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `tool invocation after stop is ignored until next request starts`() = runTest(mainDispatcher) {
        val firstResponse = CompletableDeferred<String>()
        val secondResponse = CompletableDeferred<String>()
        val harness = createHarness(
            executeBehavior = { input ->
                when (input) {
                    "first" -> firstResponse.await()
                    "second" -> secondResponse.await()
                    else -> error("Unexpected input: $input")
                }
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("first"))
            awaitState(viewModel) { it.isProcessing }

            viewModel.handleEvent(MainEvent.UserPressStop)
            awaitState(viewModel) { !it.isProcessing }

            harness.sideEffects.emit(
                AgentSideEffect.Fn(
                    FunctionCall("WebSearch", mapOf("query" to "устаревший запрос"))
                )
            )
            runCurrent()
            assertTrue(viewModel.uiState.value.agentActions.isEmpty())

            viewModel.handleEvent(MainEvent.SendChatMessage("second"))
            awaitState(viewModel) { it.isProcessing }

            harness.sideEffects.emit(
                AgentSideEffect.Fn(
                    FunctionCall("WebSearch", mapOf("query" to "актуальный запрос"))
                )
            )

            val inProgress = awaitState(viewModel) {
                it.agentActions.contains("Ищу в интернете: актуальный запрос")
            }
            assertEquals(listOf("Ищу в интернете: актуальный запрос"), inProgress.agentActions)

            secondResponse.complete("done")
            val finalState = awaitState(viewModel) { !it.isProcessing }
            assertEquals(
                listOf("Ищу в интернете: актуальный запрос"),
                finalState.chatMessages.last { !it.isUser }.agentActions,
            )
        } finally {
            firstResponse.completeExceptionally(CancellationException("Stopped"))
            harness.clear()
        }
    }

    @Test
    fun `onboarding shows welcome text and marks onboarding as completed`() = runTest(mainDispatcher) {
        val harness = createHarness(needsOnboarding = true)

        try {
            val viewModel = harness.viewModel
            val expectedOnboardingText = getString(Res.string.onboarding_display_text)

            val onboardingState = awaitState(viewModel) { state ->
                state.chatMessages.any { !it.isUser && it.text == expectedOnboardingText }
            }

            assertEquals(1, onboardingState.chatMessages.size)
            assertEquals(expectedOnboardingText, onboardingState.chatMessages.single().text)
            verify { harness.settingsProvider.needsOnboarding = false }
            verify { harness.settingsProvider.onboardingCompleted = true }
            verify(exactly = 1) { harness.say.queue(any()) }
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `selecting missing local model opens download prompt in main chat`() = runTest(mainDispatcher) {
        val harness = createHarness(
            qwenChatKey = "qwen-key",
            localAvailableModel = GigaModel.LocalQwen3_4B_Instruct_2507,
            localModelDownloaded = false,
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.UpdateChatModel(GigaModel.LocalQwen3_4B_Instruct_2507.alias))

            val state = awaitState(viewModel) { it.localModelDownloadPrompt?.model == GigaModel.LocalQwen3_4B_Instruct_2507 }
            assertEquals(GigaModel.LocalQwen3_4B_Instruct_2507, state.localModelDownloadPrompt?.model)
            assertFalse(state.selectedModel == GigaModel.LocalQwen3_4B_Instruct_2507.alias)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `pick attachments adds files to state`() = runTest(mainDispatcher) {
        val harness = createHarness()
        val tempFile = File.createTempFile("souz-attachment", ".txt").apply {
            writeText("sample")
            deleteOnExit()
        }
        val normalizedPath = FinderService.normalizePath(tempFile.absolutePath)!!

        mockkObject(FinderService)
        every { FinderService.normalizePath(any()) } answers { callOriginal() }
        every { FinderService.displayName(any()) } answers { callOriginal() }
        coEvery { FinderService.chooseFilesFromFinder(any()) } returns Result.success(listOf(tempFile.absolutePath))

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.PickChatAttachments)

            val stateWithAttachment = awaitState(viewModel) { state ->
                state.attachedFiles.map { it.path }.contains(normalizedPath)
            }
            assertEquals(1, stateWithAttachment.attachedFiles.size)
            assertEquals(tempFile.name, stateWithAttachment.attachedFiles.single().displayName)
        } finally {
            unmockkObject(FinderService)
            harness.clear()
            tempFile.delete()
        }
    }

    @Test
    fun `dropped attachments are deduplicated and removable`() = runTest(mainDispatcher) {
        val harness = createHarness()
        val tempFile = File.createTempFile("souz-drop", ".txt").apply {
            writeText("drop")
            deleteOnExit()
        }
        val normalizedPath = FinderService.normalizePath(tempFile.absolutePath)!!

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(
                MainEvent.AttachDroppedFiles(
                    listOf(tempFile.absolutePath, "file://${tempFile.absolutePath}")
                )
            )

            val attached = awaitState(viewModel) { state -> state.attachedFiles.size == 1 }
            assertEquals(listOf(normalizedPath), attached.attachedFiles.map { it.path })

            viewModel.handleEvent(MainEvent.RemoveChatAttachment("file://${tempFile.absolutePath}"))
            val cleared = awaitState(viewModel) { it.attachedFiles.isEmpty() }
            assertTrue(cleared.attachedFiles.isEmpty())
        } finally {
            harness.clear()
            tempFile.delete()
        }
    }

    @Test
    fun `sending message with attachments composes payload and clears pending attachments`() = runTest(mainDispatcher) {
        var executedInput: String? = null
        val harness = createHarness(executeBehavior = { input ->
            executedInput = input
            "assistant reply"
        })
        val tempFile = File.createTempFile("souz-send", ".txt").apply {
            writeText("send")
            deleteOnExit()
        }
        val normalizedPath = FinderService.normalizePath(tempFile.absolutePath)!!

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.AttachDroppedFiles(listOf(tempFile.absolutePath)))
            awaitState(viewModel) { state -> state.attachedFiles.size == 1 }

            viewModel.handleEvent(MainEvent.SendChatMessage("Please inspect"))

            val finalState = awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { !it.isUser && it.text == "assistant reply" }
            }

            assertEquals("Please inspect\n\n$normalizedPath", executedInput)
            val userMessage = finalState.chatMessages.first { it.isUser }
            assertEquals("Please inspect", userMessage.text)
            assertEquals(listOf(normalizedPath), userMessage.attachedFiles.map { it.path })
            assertTrue(finalState.attachedFiles.isEmpty())
        } finally {
            harness.clear()
            tempFile.delete()
        }
    }


    private suspend fun TestScope.awaitState(
        viewModel: MainViewModel,
        predicate: (MainState) -> Boolean,
    ): MainState {
        val deadlineMs = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadlineMs) {
            runCurrent()
            val state = viewModel.uiState.value
            if (predicate(state)) return state
            withContext(Dispatchers.Default) { yield() }
        }
        error("Timed out waiting for expected MainState")
    }

    private suspend fun TestScope.awaitVoiceRequestStarted(
        viewModel: MainViewModel,
        data: ByteArray,
        predicate: (MainState) -> Boolean,
    ): MainState {
        val deadlineMs = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadlineMs) {
            emitAudioFlowEvent(viewModel, data)
            runCurrent()
            val state = viewModel.uiState.value
            if (predicate(state)) return state
            withContext(Dispatchers.Default) { yield() }
        }
        error("Timed out waiting for voice request to start")
    }

    private suspend fun emitAudioFlowEvent(viewModel: MainViewModel, data: ByteArray) {
        val voiceInputUseCaseField = MainViewModel::class.java.getDeclaredField("voiceInputUseCase")
        voiceInputUseCaseField.isAccessible = true
        val voiceInputUseCase = voiceInputUseCaseField.get(viewModel) as VoiceInputUseCase
        val recorder = voiceInputUseCase.audioRecorder

        val flowField = InMemoryAudioRecorder::class.java.getDeclaredField("_audioFlow")
        flowField.isAccessible = true
        @Suppress("UNCHECKED_CAST") val audioFlow = flowField.get(recorder) as MutableSharedFlow<ByteArray>
        audioFlow.emit(data)
    }

    private fun createHarness(
        executeBehavior: suspend (String) -> String = { "stub response" },
        onCancelActiveJob: () -> Unit = {},
        needsOnboarding: Boolean = false,
        voiceInputReviewEnabled: Boolean = false,
        qwenChatKey: String = "",
        localAvailableModel: GigaModel? = null,
        localModelDownloaded: Boolean = true,
        recognizeBehavior: suspend (ByteArray) -> GigaResponse.RecognizeResponse = {
            GigaResponse.RecognizeResponse()
        },
    ): TestHarness {
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        val sideEffects = MutableSharedFlow<AgentSideEffect>()
        every { agentFacade.sideEffects } returns sideEffects
        every { agentFacade.currentContext } returns MutableStateFlow(emptyAgentContext())
        every { agentFacade.cancelActiveJob() } answers { onCancelActiveJob.invoke() }
        coEvery { agentFacade.execute(any()) } coAnswers {
            executeBehavior.invoke(firstArg())
        }
        every { agentFacade.activeAgentId } returns MutableStateFlow(ru.souz.agent.AgentId.LUA_GRAPH)
        every { agentFacade.availableAgents } returns listOf(ru.souz.agent.AgentId.LUA_GRAPH, ru.souz.agent.AgentId.GRAPH)

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.gigaModel } returns GigaModel.Max
        every { settingsProvider.contextSize } returns 16_000
        every { settingsProvider.useStreaming } returns false
        every { settingsProvider.regionProfile } returns "ru"
        every { settingsProvider.qwenChatKey } returns qwenChatKey
        every { settingsProvider.regionProfile = any() } just runs
        val localProviderAvailability = mockk<LocalProviderAvailability>(relaxed = true)
        every { localProviderAvailability.isProviderAvailable() } returns (localAvailableModel != null)
        every { localProviderAvailability.availableGigaModels() } returns listOfNotNull(localAvailableModel)
        every { localProviderAvailability.defaultGigaModel() } returns localAvailableModel
        val llmBuildProfile = LlmBuildProfile(settingsProvider, localProviderAvailability)
        val localModelStore = mockk<LocalModelStore>(relaxed = true)
        val localLlamaRuntime = mockk<LocalLlamaRuntime>(relaxed = true)
        localAvailableModel?.let { model ->
            val profile = LocalModelProfiles.forAlias(model.alias) ?: error("Missing local profile for ${model.alias}")
            every { localModelStore.isPresent(profile) } returns localModelDownloaded
            every { localModelStore.modelPath(profile) } returns File(System.getProperty("java.io.tmpdir"), profile.ggufFilename).toPath()
        }
        var needsOnboardingState = needsOnboarding
        every { settingsProvider.needsOnboarding } answers { needsOnboardingState }
        every { settingsProvider.needsOnboarding = any() } answers { needsOnboardingState = firstArg<Boolean>() }
        var onboardingCompletedState = false
        every { settingsProvider.onboardingCompleted } answers { onboardingCompletedState }
        every { settingsProvider.onboardingCompleted = any() } answers { onboardingCompletedState = firstArg<Boolean>() }
        every { settingsProvider.safeModeEnabled } returns false
        var voiceInputReviewEnabledState = voiceInputReviewEnabled
        every { settingsProvider.voiceInputReviewEnabled } answers { voiceInputReviewEnabledState }
        every { settingsProvider.voiceInputReviewEnabled = any() } answers {
            voiceInputReviewEnabledState = firstArg<Boolean>()
        }

        val say = mockk<Say>(relaxed = true)
        val speakingFlow = MutableStateFlow(false)
        every { say.isSpeaking } returns speakingFlow

        val desktopInfoRepository = mockk<DesktopInfoRepository>(relaxed = true)
        coEvery { desktopInfoRepository.storeDesktopDataDaily() } returns Unit

        val gigaVoiceApi = mockk<GigaVoiceAPI>(relaxed = true)
        coEvery { gigaVoiceApi.recognize(any()) } coAnswers {
            recognizeBehavior.invoke(firstArg())
        }

        val toolPermissionBroker = ToolPermissionBroker(settingsProvider)

        val telegramBotController = mockk<TelegramBotController>(relaxed = true)
        val incomingMessages = MutableSharedFlow<TelegramBotController.IncomingMessage>()
        val cleanCommands = MutableSharedFlow<Unit>()
        every { telegramBotController.incomingMessages } returns incomingMessages
        every { telegramBotController.cleanCommands } returns cleanCommands
        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        val telemetryService = mockk<TelemetryService>(relaxed = true)
        var telemetryRequestCounter = 0
        var telemetryConversationCounter = 0
        every { tokenLogging.requestContextElement(any()) } returns EmptyCoroutineContext
        every { tokenLogging.currentRequestTokenUsage(any()) } returns GigaResponse.Usage(0, 0, 0, 0)
        every { tokenLogging.sessionTokenUsage() } returns GigaResponse.Usage(0, 0, 0, 0)
        every { telemetryService.startConversation(any()) } answers {
            "conversation-${++telemetryConversationCounter}"
        }
        every { telemetryService.beginRequest(any(), any(), any(), any(), any(), any()) } answers {
            TelemetryRequestContext(
                requestId = "request-${++telemetryRequestCounter}",
                conversationId = firstArg(),
                source = secondArg<TelemetryRequestSource>(),
                model = thirdArg(),
                provider = arg(3),
                inputLengthChars = arg(4),
                attachedFilesCount = arg(5),
                startedAtMs = 0L,
            )
        }
        every { telemetryService.requestContextElement(any()) } returns EmptyCoroutineContext

        val di = DI {
            bindSingleton<AgentFacade> { agentFacade }
            bindSingleton { gigaVoiceApi }
            bindSingleton<SpeechRecognitionProvider> { SaluteSpeechRecognitionProvider(instance(), instance()) }
            bindSingleton { desktopInfoRepository }
            bindSingleton<SettingsProvider> { settingsProvider }
            bindSingleton<LlmBuildProfile> { llmBuildProfile }
            bindSingleton { localModelStore }
            bindSingleton { localLlamaRuntime }
            bindSingleton { say }
            bindSingleton { toolPermissionBroker }
            bindSingleton { telegramBotController }
            bindSingleton { InMemoryAudioRecorder() }
            bindSingleton { FilesToolUtil(instance()) }
            bindSingleton { FinderPathExtractor(instance()) }
            bindSingleton<Set<SelectionApprovalSource>> { emptySet() }
            bindSingleton<TokenLogging> { tokenLogging }
            bindSingleton { telemetryService }
            bindSingleton {
                MainUseCasesFactory(instance(), instance(), instance(), instance(), instance(), instance(), instance(), instance(), instance(), instance())
            }
        }

        val viewModel = MainViewModel(di)

        return TestHarness(
            viewModel = viewModel,
            isSpeakingFlow = speakingFlow,
            settingsProvider = settingsProvider,
            say = say,
            incomingMessages = incomingMessages,
            sideEffects = sideEffects,
        )
    }

    private fun isJNativeHookRuntimeAvailable(): Boolean {
        val mapped = System.mapLibraryName("Xtst")
        val candidates = listOf(
            File("/usr/lib/x86_64-linux-gnu/$mapped"),
            File("/lib/x86_64-linux-gnu/$mapped"),
            File("/usr/lib64/$mapped"),
            File("/usr/lib/$mapped"),
        )
        return candidates.any { it.exists() }
    }

    private fun hasOpenGlRuntime(): Boolean {
        val mapped = System.mapLibraryName("GL")
        val candidates = listOf(
            File("/usr/lib/x86_64-linux-gnu/$mapped"),
            File("/lib/x86_64-linux-gnu/$mapped"),
            File("/usr/lib64/$mapped"),
            File("/usr/lib/$mapped"),
        )
        return candidates.any { it.exists() }
    }

    private fun emptyAgentContext() = AgentContext(
        input = "", settings = AgentSettings(
            model = GigaModel.Max.alias, temperature = 0f, toolsByCategory = emptyMap()
        ), history = emptyList(), activeTools = emptyList(), systemPrompt = ""
    )

    private data class TestHarness(
        val viewModel: MainViewModel,
        val isSpeakingFlow: MutableStateFlow<Boolean>,
        val settingsProvider: SettingsProvider,
        val say: Say,
        val incomingMessages: MutableSharedFlow<TelegramBotController.IncomingMessage>,
        val sideEffects: MutableSharedFlow<AgentSideEffect>,
    ) {
        fun clear() {
            val onCleared = MainViewModel::class.java.getDeclaredMethod("onCleared")
            onCleared.isAccessible = true
            onCleared.invoke(viewModel)
        }
    }
}
