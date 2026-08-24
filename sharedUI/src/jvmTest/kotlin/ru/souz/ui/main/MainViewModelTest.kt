@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.main

import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.scoped
import org.kodein.di.singleton
import org.kodein.di.with
import org.junit.jupiter.api.Assumptions.assumeTrue
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.chat_action_web_search
import souz.sharedui.generated.resources.onboarding_display_text
import souz.sharedui.generated.resources.onboarding_input_permission_request
import souz.sharedui.generated.resources.voice_error_local_macos_audio_too_long
import souz.sharedui.generated.resources.voice_error_local_macos_unavailable
import souz.sharedui.generated.resources.voice_error_tool_review_pending
import souz.sharedui.generated.resources.voice_status_processing_input
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import ru.souz.agent.AgentFacade
import ru.souz.agent.AgentId
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.AgentSideEffect
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.ambient.AmbientBlockAnalyzer
import ru.souz.ambient.AmbientSemanticBlock
import ru.souz.ambient.AmbientSpeechAvailability
import ru.souz.ambient.AmbientTranscriptEvent
import ru.souz.ambient.AmbientTranscriptionService
import ru.souz.ambient.AmbientTranscriptionState
import ru.souz.ambient.SemanticBlockBuilder
import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.TokenLogging
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMResponse.FunctionCall
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.local.LocalEmbeddingProfiles
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.service.observability.ChatConversationCloseReason
import ru.souz.service.observability.ChatConversationMetrics
import ru.souz.service.observability.ChatObservabilityTracker
import ru.souz.service.observability.DesktopStructuredLogger
import ru.souz.service.speech.LocalMacOsSpeechAudioTooLongException
import ru.souz.service.speech.LocalMacOsSpeechUnavailableException
import ru.souz.service.speech.SpeechRecognitionProvider
import ru.souz.tool.ImmediateToolPermissionBroker
import ru.souz.tool.SelectionApprovalSource
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.knowledge.ToolGetKnowledge
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.files.DeferredToolModifyPermissionBroker
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.files.createDefaultFilesToolUtil
import ru.souz.tool.files.ToolModifyFile
import ru.souz.ui.BaseViewModel
import ru.souz.di.sharedUiMainViewModelUseCasesDiModule
import ru.souz.ui.common.LocalModelDownloadPromptUi
import ru.souz.ui.common.LocalModelDownloadStateUi
import ru.souz.ui.main.createMainViewModel
import ru.souz.ui.main.mainViewModelDiScope
import ru.souz.ui.main.usecases.FinderPathExtractor
import ru.souz.ui.main.usecases.DesktopAttachmentMetadataProvider
import ru.souz.ui.main.usecases.DesktopDroppedFilePathExtractor
import ru.souz.ui.main.usecases.DesktopPathPicker
import ru.souz.ui.main.usecases.MemoryConversationCleanup
import ru.souz.ui.main.usecases.NoopMemoryConversationCleanup
import ru.souz.ui.main.usecases.VoiceInputController
import ru.souz.ui.main.usecases.VoiceInputRoute
import ru.souz.ui.main.usecases.VoiceInputUseCase
import ru.souz.ui.common.FinderService
import ru.souz.ui.host.BackgroundIndexRefresher
import ru.souz.ui.host.ChatCommandInputSource
import ru.souz.ui.host.DesktopChatCommandInputSource
import ru.souz.ui.host.DesktopLocalModelUiHost
import ru.souz.ui.host.LocalModelUiHost
import ru.souz.ui.host.NoopPathOpener
import ru.souz.ui.host.PathOpener
import ru.souz.ui.host.PermissionPromptService
import ru.souz.ui.host.TelegramControlBot
import ru.souz.ui.host.TelegramControlIncomingMessage
import ru.souz.ui.host.UiAudioRecorder
import ru.souz.ui.host.UiAudioRecordingState
import ru.souz.ui.host.UiSpeechPlayer
import ru.souz.ui.main.usecases.AttachmentMetadataProvider
import ru.souz.ui.main.usecases.DroppedFilePathExtractor
import ru.souz.ui.main.usecases.PathPicker
import ru.souz.ui.main.search.ChatSearchState
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MainViewModelTest {

    private lateinit var mainDispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        assumeTrue(hasOpenGlRuntime(), "MainViewModelTest requires libGL runtime on Linux CI")

        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)

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
    fun `send during capable agent processing submits input to the active run`() = runTest(mainDispatcher) {
        val response = CompletableDeferred<String>()
        val harness = createHarness(
            activeAgentId = AgentId.GRAPH,
            supportsActiveRunInput = true,
            executeBehavior = { response.await() },
            submitActiveRunBehavior = { true },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.supportsActiveRunInput)

            viewModel.handleEvent(MainEvent.SendChatMessage("initial request"))
            awaitState(viewModel) { it.isProcessing }

            viewModel.handleEvent(MainEvent.SendChatMessage("steer this"))

            val steeredState = awaitState(viewModel) { state ->
                state.chatMessages.any { it.isUser && it.text == "steer this" }
            }
            assertTrue(steeredState.isProcessing)
            assertEquals(listOf("initial request"), harness.executedInputs)
            assertEquals(listOf("steer this"), harness.submittedActiveRunInputs)
            assertTrue(steeredState.chatInputSubmissionFeedback.accepted)
            assertEquals("steer this", steeredState.chatInputSubmissionFeedback.input)

            response.complete("final answer")
            val finalState = awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { !it.isUser && it.text == "final answer" }
            }
            assertEquals(
                listOf("initial request", "steer this", "final answer"),
                finalState.chatMessages.map { it.text },
            )
        } finally {
            response.complete("final answer")
            harness.clear()
        }
    }

    @Test
    fun `rejected active run input publishes feedback for retaining the draft`() = runTest(mainDispatcher) {
        val response = CompletableDeferred<String>()
        val harness = createHarness(
            activeAgentId = AgentId.SKILLS_GRAPH,
            executeBehavior = { response.await() },
            submitActiveRunBehavior = { false },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()
            viewModel.handleEvent(MainEvent.SendChatMessage("initial request"))
            awaitState(viewModel) { it.isProcessing }
            val initialRevision = viewModel.uiState.value.chatInputSubmissionFeedback.revision

            viewModel.handleEvent(MainEvent.SendChatMessage("keep this draft"))
            runCurrent()

            val feedback = viewModel.uiState.value.chatInputSubmissionFeedback
            assertEquals(initialRevision + 1, feedback.revision)
            assertEquals("keep this draft", feedback.input)
            assertFalse(feedback.accepted)
            assertEquals(listOf("keep this draft"), harness.submittedActiveRunInputs)
        } finally {
            response.complete("final answer")
            harness.clear()
        }
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
    fun `voice capture cancels classic request and runs recognized request`() = runTest(mainDispatcher) {
        val firstStarted = CompletableDeferred<Unit>()
        val firstResponse = CompletableDeferred<String>()
        val secondResponse = CompletableDeferred<String>()
        val harness: TestHarness = createHarness(
            executeBehavior = { input ->
                when (input) {
                    "first request" -> {
                        firstStarted.complete(Unit)
                        firstResponse.await()
                    }
                    "second request" -> secondResponse.await()
                    else -> error("Unexpected input: $input")
                }
            },
            onCancelActiveJob = {
                if (firstStarted.isCompleted) {
                    firstResponse.completeExceptionally(CancellationException("Cancelled by voice capture"))
                }
            },
            recognizeBehavior = {
                LLMResponse.RecognizeResponse(result = listOf("second request"))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("first request"))

            awaitState(viewModel) { state ->
                state.isProcessing && state.chatMessages.any { it.isUser && it.text == "first request" }
            }
            firstStarted.await()

            prepareAudioCapture(viewModel, byteArrayOf(9, 8, 7))
            viewModel.handleEvent(MainEvent.StartListening)
            awaitState(viewModel) { state ->
                state.isListening && state.chatMessages.none { it.text == "first request" }
            }
            viewModel.handleEvent(MainEvent.StopListening)

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
    fun `active run voice continuation bypasses review without cancellation`() = runTest(mainDispatcher) {
        val response = CompletableDeferred<String>()
        val recognizedTexts = ArrayDeque(
            listOf("initial voice request", "voice continuation", "rejected continuation"),
        )
        var cancellationCalls = 0
        val harness = createHarness(
            activeAgentId = AgentId.SKILLS_GRAPH,
            executeBehavior = { response.await() },
            submitActiveRunBehavior = { it != "rejected continuation" },
            onCancelActiveJob = { cancellationCalls += 1 },
            recognizeBehavior = {
                LLMResponse.RecognizeResponse(result = listOf(recognizedTexts.removeFirst()))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            prepareAudioCapture(viewModel, byteArrayOf(1, 2, 3))
            viewModel.send(MainEvent.StartListening)
            awaitState(viewModel) { it.isListening }
            viewModel.send(MainEvent.StopListening)

            awaitState(viewModel) { state ->
                state.isProcessing && state.chatMessages.any { it.isUser && it.text == "initial voice request" }
            }
            harness.settingsProvider.voiceInputReviewEnabled = true
            val cancellationsBeforeCapture = cancellationCalls

            prepareAudioCapture(viewModel, byteArrayOf(4, 5, 6))
            viewModel.send(MainEvent.StartListening)
            val captureState = awaitState(viewModel) { it.isListening }
            assertTrue(captureState.isProcessing)
            assertEquals(cancellationsBeforeCapture, cancellationCalls)

            viewModel.send(MainEvent.StopListening)
            val continuedState = awaitState(viewModel) { state ->
                state.chatMessages.any { it.isUser && it.text == "voice continuation" }
            }
            assertEquals(listOf("initial voice request"), harness.executedInputs)
            assertEquals(listOf("voice continuation"), harness.submittedActiveRunInputs)
            assertTrue(continuedState.chatMessages.last { it.text == "voice continuation" }.isVoice)
            assertNull(continuedState.pendingVoiceInputDraft)

            recordVoiceInput(viewModel, byteArrayOf(7, 8, 9))
            val rejectedState = awaitState(viewModel) {
                it.pendingVoiceInputDraft?.text == "rejected continuation"
            }
            assertFalse(requireNotNull(rejectedState.pendingVoiceInputDraft).submitAsVoice)
            assertFalse(rejectedState.chatMessages.any { it.text == "rejected continuation" })
            assertEquals(
                listOf("voice continuation", "rejected continuation"),
                harness.submittedActiveRunInputs,
            )
            assertEquals(cancellationsBeforeCapture, cancellationCalls)

            response.complete("final answer")
            awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { !it.isUser && it.text == "final answer" }
            }
        } finally {
            response.complete("final answer")
            harness.clear()
        }
    }

    @Test
    fun `reviewed new request voice becomes a one-shot voice draft`() = runTest(mainDispatcher) {
        val draftText = "review this voice request"
        val harness = createHarness(
            voiceInputReviewEnabled = true,
            recognizeBehavior = {
                LLMResponse.RecognizeResponse(result = listOf(draftText))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            recordVoiceInput(viewModel, byteArrayOf(9, 8, 7))
            val stateWithDraft = awaitState(viewModel) { state ->
                state.pendingVoiceInputDraft?.text == draftText
            }
            val draft = requireNotNull(stateWithDraft.pendingVoiceInputDraft)
            assertTrue(draft.submitAsVoice)
            assertTrue(stateWithDraft.chatMessages.isEmpty())
            assertFalse(stateWithDraft.isProcessing)

            viewModel.handleEvent(MainEvent.SendChatMessage("unrelated typed request"))
            awaitState(viewModel) {
                !it.isProcessing && harness.executedInputs == listOf("unrelated typed request")
            }
            assertEquals(draft, viewModel.uiState.value.pendingVoiceInputDraft)

            viewModel.consumePendingVoiceInputDraft(draft.token)
            awaitState(viewModel) { it.pendingVoiceInputDraft == null }

            viewModel.handleEvent(
                MainEvent.SendChatMessage(draft.text, isVoice = draft.submitAsVoice),
            )
            val sentState = awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { it.isUser && it.text == draftText }
            }
            assertTrue(sentState.chatMessages.last { it.isUser }.isVoice)
            assertTrue(sentState.chatMessages.last { !it.isUser }.isVoice)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `acknowledged voice drafts are excluded while unconsumed drafts merge`() = runTest(mainDispatcher) {
        val recognizedTexts = ArrayDeque(listOf("first draft", "second draft", "third draft"))
        val harness = createHarness(
            voiceInputReviewEnabled = true,
            recognizeBehavior = {
                LLMResponse.RecognizeResponse(result = listOf(recognizedTexts.removeFirst()))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            recordVoiceInput(viewModel, byteArrayOf(1, 2, 3))
            val firstDraft = requireNotNull(
                awaitState(viewModel) { it.pendingVoiceInputDraft?.text == "first draft" }
                    .pendingVoiceInputDraft,
            )
            viewModel.consumePendingVoiceInputDraft(firstDraft.token)
            assertNull(viewModel.uiState.value.pendingVoiceInputDraft)

            recordVoiceInput(viewModel, byteArrayOf(4, 5, 6))
            val secondDraft = requireNotNull(
                awaitState(viewModel) { it.pendingVoiceInputDraft?.text == "second draft" }
                    .pendingVoiceInputDraft,
            )
            recordVoiceInput(viewModel, byteArrayOf(7, 8, 9))
            val combinedDraft = requireNotNull(
                awaitState(viewModel) { it.pendingVoiceInputDraft?.text == "second draft\nthird draft" }
                    .pendingVoiceInputDraft,
            )

            viewModel.consumePendingVoiceInputDraft(secondDraft.token)

            assertTrue(secondDraft.token > firstDraft.token)
            assertTrue(combinedDraft.token > secondDraft.token)
            assertTrue(combinedDraft.submitAsVoice)
            assertEquals(combinedDraft, viewModel.uiState.value.pendingVoiceInputDraft)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `late voice continuation becomes a draft and does not steer a replacement run`() = runTest(mainDispatcher) {
        val responseA = CompletableDeferred<String>()
        val responseB = CompletableDeferred<String>()
        val recognitionStarted = CompletableDeferred<Unit>()
        val releaseRecognition = CompletableDeferred<Unit>()
        val recognitionReturned = CompletableDeferred<Unit>()
        val harness = createHarness(
            activeAgentId = AgentId.SKILLS_GRAPH,
            executeBehavior = { input ->
                when (input) {
                    "request A" -> responseA.await()
                    "request B" -> responseB.await()
                    else -> error("Unexpected input: $input")
                }
            },
            submitActiveRunBehavior = { true },
            recognizeBehavior = {
                recognitionStarted.complete(Unit)
                releaseRecognition.await()
                recognitionReturned.complete(Unit)
                LLMResponse.RecognizeResponse(result = listOf("late voice continuation"))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("request A"))
            awaitState(viewModel) { it.isProcessing }

            prepareAudioCapture(viewModel, byteArrayOf(9, 8, 7))
            viewModel.handleEvent(MainEvent.StartListening)
            awaitState(viewModel) { it.isListening }
            viewModel.handleEvent(MainEvent.StopListening)
            awaitDeferred(recognitionStarted)

            responseA.complete("answer A")
            awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { !it.isUser && it.text == "answer A" }
            }

            viewModel.handleEvent(MainEvent.SendChatMessage("request B"))
            awaitState(viewModel) { state ->
                state.isProcessing && state.chatMessages.any { it.isUser && it.text == "request B" }
            }

            releaseRecognition.complete(Unit)
            awaitDeferred(recognitionReturned)
            val finalState = awaitState(viewModel) {
                it.pendingVoiceInputDraft?.text == "late voice continuation"
            }

            assertTrue(finalState.isProcessing)
            assertEquals(listOf("request A", "request B"), harness.executedInputs)
            assertTrue(harness.submittedActiveRunInputs.isEmpty())
            assertFalse(finalState.chatMessages.any { it.text == "late voice continuation" })
            assertFalse(requireNotNull(finalState.pendingVoiceInputDraft).submitAsVoice)
        } finally {
            responseA.complete("answer A")
            responseB.complete("answer B")
            releaseRecognition.complete(Unit)
            harness.clear()
        }
    }

    @Test
    fun `failed recording stop does not route the next capture with stale intent`() = runTest(mainDispatcher) {
        val response = CompletableDeferred<String>()
        val continuation = "continue active request"
        val harness = createHarness(
            activeAgentId = AgentId.SKILLS_GRAPH,
            executeBehavior = { response.await() },
            submitActiveRunBehavior = { true },
            recognizeBehavior = { LLMResponse.RecognizeResponse(result = listOf(continuation)) },
        )

        try {
            val viewModel = harness.viewModel
            val audioRecorder = testAudioRecorder(viewModel)
            advanceUntilIdle()

            audioRecorder.failNextStop()
            viewModel.handleEvent(MainEvent.StartListening)
            awaitState(viewModel) { it.isListening }
            viewModel.handleEvent(MainEvent.StopListening)
            awaitState(viewModel) { !it.isListening }

            viewModel.handleEvent(MainEvent.SendChatMessage("initial request"))
            awaitState(viewModel) { it.isProcessing }

            prepareAudioCapture(viewModel, byteArrayOf(9, 8, 7))
            viewModel.handleEvent(MainEvent.StartListening)
            awaitState(viewModel) { it.isListening }
            viewModel.handleEvent(MainEvent.StopListening)

            awaitState(viewModel) { harness.submittedActiveRunInputs == listOf(continuation) }

            assertEquals(listOf("initial request"), harness.executedInputs)
            assertTrue(viewModel.uiState.value.isProcessing)
        } finally {
            response.complete("initial answer")
            harness.clear()
        }
    }

    @Test
    fun `recording stays busy until asynchronous stop completes`() = runTest(mainDispatcher) {
        val draft = "voice draft"
        val harness = createHarness(
            voiceInputReviewEnabled = true,
            recognizeBehavior = { LLMResponse.RecognizeResponse(result = listOf(draft)) },
        )

        try {
            val viewModel = harness.viewModel
            val audioRecorder = testAudioRecorder(viewModel)
            advanceUntilIdle()

            audioRecorder.deferNextStop()
            prepareAudioCapture(viewModel, byteArrayOf(9, 8, 7))
            viewModel.handleEvent(MainEvent.StartListening)
            awaitState(viewModel) { it.isListening }

            val stopJob = launch { viewModel.handleEvent(MainEvent.StopListening) }
            runCurrent()
            assertEquals(UiAudioRecordingState.Stopping, audioRecorder.recordingState.value)
            assertTrue(viewModel.uiState.value.isListening)

            viewModel.handleEvent(MainEvent.StartListening)
            assertEquals(1, audioRecorder.startCalls)

            audioRecorder.completeDeferredStop()
            stopJob.join()

            val completedState = awaitState(viewModel) {
                !it.isListening && it.pendingVoiceInputDraft?.text == draft
            }
            assertFalse(completedState.isListening)
            assertEquals(1, audioRecorder.startCalls)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `voice hotkey is blocked while Skills run awaits tool review`() = runTest(mainDispatcher) {
        val response = CompletableDeferred<String>()
        val harness = createHarness(
            activeAgentId = AgentId.SKILLS_GRAPH,
            nativeVoiceHotkeyEnabled = true,
            executeBehavior = { response.await() },
        )

        try {
            val viewModel = harness.viewModel
            val audioRecorder = testAudioRecorder(viewModel)
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("initial request"))
            awaitState(viewModel) { it.isProcessing }
            forceUiState(
                viewModel,
                viewModel.uiState.value.copy(
                    isProcessing = false,
                    isAwaitingToolReview = true,
                )
            )

            harness.pressVoiceHotkey(true)

            val blockedMessage = getString(Res.string.voice_error_tool_review_pending)
            val blockedState = awaitState(viewModel) { it.statusMessage == blockedMessage }
            assertFalse(blockedState.isListening)
            assertEquals(0, audioRecorder.startCalls)
        } finally {
            response.complete("final answer")
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
                LLMResponse.RecognizeResponse(result = listOf("final draft"))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            prepareAudioCapture(viewModel, byteArrayOf(9, 9, 9))
            viewModel.handleEvent(MainEvent.StartListening)
            awaitState(viewModel) { it.isListening }
            val stopJob = launch { viewModel.handleEvent(MainEvent.StopListening) }
            recognitionStarted.await()

            viewModel.handleEvent(MainEvent.StartListening)

            val expectedStatus = getString(Res.string.voice_status_processing_input)
            val state = awaitState(viewModel) { it.statusMessage == expectedStatus }
            assertFalse(state.isListening)

            releaseRecognition.complete(Unit)
            stopJob.join()
        } finally {
            releaseRecognition.complete(Unit)
            harness.clear()
        }
    }

    @Test
    fun `next capture works after local macos unavailable recognition`() =
        runTest(mainDispatcher) {
            var recognizeCalls = 0
            val harness = createHarness(
                voiceInputReviewEnabled = true,
                recognizeBehavior = {
                    recognizeCalls += 1
                    if (recognizeCalls == 1) {
                        throw LocalMacOsSpeechUnavailableException("Local macOS unavailable")
                    }
                    LLMResponse.RecognizeResponse(result = listOf("final draft"))
                },
            )

            try {
                val viewModel = harness.viewModel
                advanceUntilIdle()

                recordVoiceInput(viewModel, byteArrayOf(7, 8, 9))

                val unavailableMessage = getString(Res.string.voice_error_local_macos_unavailable)
                val unavailableState = awaitState(viewModel) { it.statusMessage == unavailableMessage }
                assertEquals(unavailableMessage, unavailableState.statusMessage)

                recordVoiceInput(viewModel, byteArrayOf(1, 2, 3))

                val recoveredState = awaitState(viewModel) {
                    it.pendingVoiceInputDraft?.text == "final draft"
                }
                assertEquals("final draft", recoveredState.pendingVoiceInputDraft?.text)
                assertEquals(2, recognizeCalls)
            } finally {
                harness.clear()
            }
        }

    @Test
    fun `too long local macos audio shows specific message without automatic retry and keeps voice input alive`() = runTest(mainDispatcher) {
        var recognizeCalls = 0
        val harness = createHarness(
            voiceInputReviewEnabled = true,
            recognizeBehavior = {
                recognizeCalls += 1
                if (recognizeCalls == 1) {
                    throw LocalMacOsSpeechAudioTooLongException()
                }
                LLMResponse.RecognizeResponse(result = listOf("short draft"))
            },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            recordVoiceInput(viewModel, ByteArray(16_000 * 2 * 45 + 1))

            val tooLongMessage = getString(Res.string.voice_error_local_macos_audio_too_long)
            val tooLongState = awaitState(viewModel) { it.statusMessage == tooLongMessage }
            assertEquals(tooLongMessage, tooLongState.statusMessage)
            assertEquals(1, recognizeCalls)

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(1, recognizeCalls)

            recordVoiceInput(viewModel, byteArrayOf(7, 8, 9))

            val recoveredState = awaitState(viewModel) { it.pendingVoiceInputDraft?.text == "short draft" }
            assertEquals("short draft", recoveredState.pendingVoiceInputDraft?.text)
            assertEquals(2, recognizeCalls)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `scoped MainViewModel graph shares use case instances across voice permission and review flows`() = runTest(mainDispatcher) {
        val harness = createHarness(voiceInputReviewEnabled = true)

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            val chatUseCase = privateField<Any>(viewModel, "chatUseCase")
            val toolModifyReviewUseCase = privateField<Any>(viewModel, "toolModifyReviewUseCase")
            val voiceInputUseCase = privateField<Any>(viewModel, "voiceInputUseCase")
            val speechUseCase = privateField<Any>(viewModel, "speechUseCase")
            val permissionsUseCase = privateField<Any>(viewModel, "permissionsUseCase")

            assertSame(chatUseCase, privateField(voiceInputUseCase, "chatUseCase"))
            assertSame(speechUseCase, privateField(voiceInputUseCase, "speechUseCase"))
            assertSame(permissionsUseCase, privateField(voiceInputUseCase, "permissionsUseCase"))
            assertSame(speechUseCase, privateField(permissionsUseCase, "speechUseCase"))
            assertSame(toolModifyReviewUseCase, privateField(chatUseCase, "toolModifyReviewUseCase"))
        } finally {
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
                    LLMResponse.RecognizeResponse(result = listOf("hello"))
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
    fun `skills graph hides core tool actions during processing`() = runTest(mainDispatcher) {
        val response = CompletableDeferred<String>()
        val harness = createHarness(
            activeAgentId = AgentId.SKILLS_GRAPH,
            executeBehavior = { response.await() },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("hello"))
            awaitState(viewModel) { it.isProcessing }

            harness.sideEffects.emit(
                AgentSideEffect.Fn(
                    FunctionCall(ToolGetSkillsByCategory.NAME, mapOf("category" to "FILES"))
                )
            )
            harness.sideEffects.emit(
                AgentSideEffect.Fn(
                    FunctionCall(ToolGetKnowledge.NAME, mapOf("knowledgeId" to "knowledge-1"))
                )
            )
            runCurrent()

            assertTrue(viewModel.uiState.value.agentActions.isEmpty())
            response.complete("done")
            val finalState = awaitState(viewModel) { !it.isProcessing }
            assertTrue(finalState.chatMessages.last { !it.isUser }.agentActions.isEmpty())
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
            verify(exactly = 1) { harness.speechPlayer.queue(any()) }
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `selecting missing local model opens download prompt in main chat`() = runTest(mainDispatcher) {
        val harness = createHarness(
            qwenChatKey = "qwen-key",
            localAvailableModel = LLMModel.LocalQwen3_4B_Instruct_2507,
            localModelDownloaded = false,
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.UpdateChatModel(LLMModel.LocalQwen3_4B_Instruct_2507.alias))

            val state = awaitState(viewModel) { it.localModelDownloadPrompt?.model == LLMModel.LocalQwen3_4B_Instruct_2507 }
            assertEquals(LLMModel.LocalQwen3_4B_Instruct_2507, state.localModelDownloadPrompt?.model)
            assertEquals(
                listOf(LocalModelProfiles.QWEN3_4B_INSTRUCT_2507.id, LocalEmbeddingProfiles.default().id),
                state.localModelDownloadPrompt?.downloads?.map { it.id },
            )
            assertFalse(state.selectedModel == LLMModel.LocalQwen3_4B_Instruct_2507.alias)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `startup opens download prompt when persisted local model misses linked embeddings`() = runTest(mainDispatcher) {
        val harness = createHarness(
            qwenChatKey = "qwen-key",
            configuredModel = LLMModel.LocalQwen3_4B_Instruct_2507,
            localAvailableModel = LLMModel.LocalQwen3_4B_Instruct_2507,
            localModelDownloaded = true,
            localEmbeddingsDownloaded = false,
        )

        try {
            val state = awaitState(harness.viewModel) {
                it.localModelDownloadPrompt?.model == LLMModel.LocalQwen3_4B_Instruct_2507
            }
            assertEquals(LLMModel.LocalQwen3_4B_Instruct_2507.alias, state.selectedModel)
            assertEquals(
                listOf(LocalEmbeddingProfiles.default().id),
                state.localModelDownloadPrompt?.downloads?.map { it.id },
            )
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `toggle ambient with missing analysis model shows error and does not start listening`() = runTest(mainDispatcher) {
        val ambientModel = LLMModel.LocalQwen3_4B_Instruct_2507
        val localModelUiHost = TestLocalModelUiHost(missingModels = setOf(ambientModel))
        val ambientTranscriptionService = TestAmbientTranscriptionService()
        val harness = createHarness(
            qwenChatKey = "qwen-key",
            ambientAnalysisModel = ambientModel,
            localModelUiHostOverride = localModelUiHost,
            ambientTranscriptionService = ambientTranscriptionService,
        )
        val effectSignal = CompletableDeferred<MainEffect>()
        val effectJob = launch {
            harness.viewModel.effects.collect { effect ->
                if (!effectSignal.isCompleted) effectSignal.complete(effect)
            }
        }

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()
            localModelUiHost.clearCounters()

            viewModel.handleEvent(MainEvent.ToggleAmbientMode)

            val effect = awaitDeferred(effectSignal)
            advanceUntilIdle()

            assertTrue(effect is MainEffect.ShowError)
            assertTrue(effect.message.contains("ambient", ignoreCase = true))
            assertEquals(1, localModelUiHost.downloadPromptCalls)
            assertEquals(listOf(ambientModel), localModelUiHost.downloadPromptModels)
            assertEquals(0, ambientTranscriptionService.startCalls)
            assertFalse(viewModel.uiState.value.ambientMode.enabled)
            assertNull(viewModel.uiState.value.localModelDownloadPrompt)
        } finally {
            effectJob.cancel()
            harness.clear()
        }
    }

    @Test
    fun `toggle ambient starts listening when analysis model is available`() = runTest(mainDispatcher) {
        val ambientModel = LLMModel.LocalQwen3_4B_Instruct_2507
        val localModelUiHost = TestLocalModelUiHost()
        val ambientTranscriptionService = TestAmbientTranscriptionService()
        val harness = createHarness(
            qwenChatKey = "qwen-key",
            ambientAnalysisModel = ambientModel,
            localModelUiHostOverride = localModelUiHost,
            ambientTranscriptionService = ambientTranscriptionService,
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()
            localModelUiHost.clearCounters()

            viewModel.handleEvent(MainEvent.ToggleAmbientMode)

            awaitState(viewModel) { it.ambientMode.listening }

            assertEquals(1, localModelUiHost.downloadPromptCalls)
            assertEquals(listOf(ambientModel), localModelUiHost.downloadPromptModels)
            assertEquals(1, localModelUiHost.schedulePreloadCalls)
            assertEquals(1, ambientTranscriptionService.startCalls)
            assertNull(viewModel.uiState.value.localModelDownloadPrompt)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `toggle ambient stops active listener without checking missing analysis model`() = runTest(mainDispatcher) {
        val ambientModel = LLMModel.LocalQwen3_4B_Instruct_2507
        val localModelUiHost = TestLocalModelUiHost()
        val ambientTranscriptionService = TestAmbientTranscriptionService()
        val harness = createHarness(
            qwenChatKey = "qwen-key",
            ambientAnalysisModel = ambientModel,
            localModelUiHostOverride = localModelUiHost,
            ambientTranscriptionService = ambientTranscriptionService,
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()
            localModelUiHost.clearCounters()

            viewModel.handleEvent(MainEvent.ToggleAmbientMode)
            awaitState(viewModel) { it.ambientMode.listening }

            localModelUiHost.missingModels = setOf(ambientModel)
            viewModel.handleEvent(MainEvent.ToggleAmbientMode)
            awaitState(viewModel) { !it.ambientMode.enabled }

            assertEquals(1, localModelUiHost.downloadPromptCalls)
            assertEquals(1, ambientTranscriptionService.startCalls)
            assertEquals(1, ambientTranscriptionService.stopCalls)
            assertNull(viewModel.uiState.value.localModelDownloadPrompt)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `onCleared stops ambient listener through app scope when viewmodel scope is cancelled`() = runTest(mainDispatcher) {
        val ambientModel = LLMModel.LocalQwen3_4B_Instruct_2507
        val localModelUiHost = TestLocalModelUiHost()
        val ambientTranscriptionService = TestAmbientTranscriptionService()
        val harness = createHarness(
            qwenChatKey = "qwen-key",
            ambientAnalysisModel = ambientModel,
            localModelUiHostOverride = localModelUiHost,
            ambientTranscriptionService = ambientTranscriptionService,
        )

        val viewModel = harness.viewModel
        advanceUntilIdle()

        viewModel.handleEvent(MainEvent.ToggleAmbientMode)
        awaitState(viewModel) { it.ambientMode.listening }

        viewModel.viewModelScope.cancel()
        harness.clear()
        advanceUntilIdle()

        assertEquals(1, ambientTranscriptionService.startCalls)
        assertEquals(1, ambientTranscriptionService.stopCalls)
        assertEquals(1, ambientTranscriptionService.clearCalls)
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

    @Test
    fun `search query builds results and navigation wraps`() = runTest(mainDispatcher) {
        val harness = createHarness()

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            val firstMatch = ChatMessage(text = "alpha target", isUser = true)
            val noMatch = ChatMessage(text = "beta", isUser = false)
            val secondMatch = ChatMessage(text = "gamma TARGET", isUser = false)
            forceUiState(
                viewModel,
                viewModel.uiState.value.copy(
                    chatMessages = listOf(firstMatch, noMatch, secondMatch),
                    chatSearch = ChatSearchState(),
                ),
            )

            viewModel.handleEvent(MainEvent.UpdateChatSearchQuery("target"))

            val initialState = awaitState(viewModel) { state ->
                state.chatSearch.matches.size == 2 &&
                    state.chatSearch.activeMatch?.messageId == firstMatch.id
            }
            assertEquals(listOf(firstMatch.id, secondMatch.id), initialState.chatSearch.matches.map { it.messageId })

            viewModel.handleEvent(MainEvent.SelectNextChatSearchResult)
            val nextState = awaitState(viewModel) { it.chatSearch.currentIndex == 1 }
            assertEquals(secondMatch.id, nextState.chatSearch.activeMatch?.messageId)

            viewModel.handleEvent(MainEvent.SelectNextChatSearchResult)
            val wrappedState = awaitState(viewModel) { it.chatSearch.currentIndex == 0 }
            assertEquals(firstMatch.id, wrappedState.chatSearch.activeMatch?.messageId)

            viewModel.handleEvent(MainEvent.SelectPreviousChatSearchResult)
            val previousState = awaitState(viewModel) { it.chatSearch.currentIndex == 1 }
            assertEquals(secondMatch.id, previousState.chatSearch.activeMatch?.messageId)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `clear context resets awaiting tool review state`() = runTest(mainDispatcher) {
        val harness = createHarness()

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()
            forceUiState(
                viewModel,
                viewModel.uiState.value.copy(
                    isAwaitingToolReview = true,
                    pendingVoiceInputDraft = PendingVoiceInputDraft(
                        text = "voice draft",
                        token = 42L,
                        submitAsVoice = true,
                    ),
                    chatMessages = listOf(
                        ChatMessage(
                            text = "pending review",
                            isUser = false,
                            toolModifyReview = ToolModifyReviewUi(items = emptyList()),
                        )
                    ),
                )
            )

            viewModel.handleEvent(MainEvent.ClearContext)

            val state = awaitState(viewModel) {
                !it.isAwaitingToolReview &&
                    it.chatMessages.isEmpty() &&
                    it.pendingVoiceInputDraft == null
            }
            assertFalse(state.isAwaitingToolReview)
            assertNull(state.pendingVoiceInputDraft)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `new conversation resets awaiting tool review state`() = runTest(mainDispatcher) {
        val harness = createHarness()

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()
            forceUiState(
                viewModel,
                viewModel.uiState.value.copy(
                    isAwaitingToolReview = true,
                    chatMessages = listOf(
                        ChatMessage(
                            text = "pending review",
                            isUser = false,
                            toolModifyReview = ToolModifyReviewUi(items = emptyList()),
                        )
                    ),
                )
            )

            viewModel.handleEvent(MainEvent.RequestNewConversation)
            awaitState(viewModel) { it.showNewChatDialog }
            viewModel.handleEvent(MainEvent.ConfirmNewConversation)

            val state = awaitState(viewModel) {
                !it.isAwaitingToolReview && it.chatMessages.isEmpty() && !it.showNewChatDialog
            }
            assertFalse(state.isAwaitingToolReview)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `confirmed new conversation cleans discarded chat memory and Knowledge`() = runTest(mainDispatcher) {
        val cleanup = RecordingMemoryConversationCleanup()
        val clearedKnowledgeMeta = mutableListOf<ToolInvocationMeta>()
        val knowledgeStore = mockk<ConversationKnowledgeStore>(relaxed = true)
        coEvery { knowledgeStore.clearConversation(capture(clearedKnowledgeMeta)) } returns Unit
        val finishedConversations = mutableListOf<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>()
        val harness = createHarness(
            memoryConversationCleanup = cleanup,
            conversationKnowledgeStore = knowledgeStore,
            conversationFinishedEvents = finishedConversations,
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("remember this only for this chat"))
            awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { it.text == "stub response" }
            }

            viewModel.handleEvent(MainEvent.RequestNewConversation)
            awaitState(viewModel) { it.showNewChatDialog }
            viewModel.handleEvent(MainEvent.ConfirmNewConversation)
            awaitState(viewModel) { it.chatMessages.isEmpty() && !it.showNewChatDialog }
            advanceUntilIdle()

            assertEquals(1, cleanup.cleanedConversationIds.size)
            assertTrue(cleanup.cleanedConversationIds.single().isNotBlank())
            assertEquals(cleanup.cleanedConversationIds.single(), finishedConversations.single().first)
            assertEquals(cleanup.cleanedConversationIds.single(), clearedKnowledgeMeta.single().conversationId)
            assertEquals(ChatConversationCloseReason.NEW_CONVERSATION, finishedConversations.single().third)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `clear context immediately cleans conversation memory and Knowledge`() = runTest(mainDispatcher) {
        val cleanup = RecordingMemoryConversationCleanup()
        val clearedKnowledgeMeta = mutableListOf<ToolInvocationMeta>()
        val knowledgeStore = mockk<ConversationKnowledgeStore>(relaxed = true)
        coEvery { knowledgeStore.clearConversation(capture(clearedKnowledgeMeta)) } returns Unit
        val finishedConversations = mutableListOf<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>()
        val harness = createHarness(
            memoryConversationCleanup = cleanup,
            conversationKnowledgeStore = knowledgeStore,
            conversationFinishedEvents = finishedConversations,
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()
            viewModel.handleEvent(MainEvent.SendChatMessage("retain a Knowledge reference"))
            awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { it.text == "stub response" }
            }

            viewModel.handleEvent(MainEvent.ClearContext)
            awaitState(viewModel) { it.chatMessages.isEmpty() && it.userExpectCloseOnX }
            advanceUntilIdle()

            assertEquals(1, cleanup.cleanedConversationIds.size)
            assertEquals(cleanup.cleanedConversationIds.single(), clearedKnowledgeMeta.single().conversationId)
            assertEquals(ChatConversationCloseReason.CLEAR_CONTEXT, finishedConversations.single().third)
        } finally {
            harness.clear()
        }
    }

    @Test
    fun `view model teardown cleans conversation memory and Knowledge`() = runTest(mainDispatcher) {
        val cleanup = RecordingMemoryConversationCleanup()
        val clearedKnowledgeMeta = mutableListOf<ToolInvocationMeta>()
        val knowledgeStore = mockk<ConversationKnowledgeStore>(relaxed = true)
        coEvery { knowledgeStore.clearConversation(capture(clearedKnowledgeMeta)) } returns Unit
        val finishedConversations = mutableListOf<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>()
        val harness = createHarness(
            memoryConversationCleanup = cleanup,
            conversationKnowledgeStore = knowledgeStore,
            conversationFinishedEvents = finishedConversations,
        )

        val viewModel = harness.viewModel
        advanceUntilIdle()
        viewModel.handleEvent(MainEvent.SendChatMessage("temporary data"))
        awaitState(viewModel) { state ->
            !state.isProcessing && state.chatMessages.any { it.text == "stub response" }
        }

        harness.clear()
        advanceUntilIdle()

        assertEquals(1, cleanup.cleanedConversationIds.size)
        assertEquals(cleanup.cleanedConversationIds.single(), clearedKnowledgeMeta.single().conversationId)
        assertEquals(ChatConversationCloseReason.VIEW_MODEL_CLEARED, finishedConversations.single().third)
    }

    @Test
    fun `stale response clears staged edit broker state`() = runTest(mainDispatcher) {
        val firstResponse = CompletableDeferred<String>()
        val secondResponse = CompletableDeferred<String>()
        lateinit var harness: TestHarness
        val stagedFile = java.nio.file.Files.createTempFile(
            FilesToolUtil.homeDirectory.toPath(),
            "souz-stale-response-",
            ".txt",
        ).toFile().apply {
            writeText("line\n")
        }
        harness = createHarness(
            safeModeEnabled = true,
            executeBehavior = { input ->
                when (input) {
                    "first request" -> {
                        harness.deferredToolModifyPermissionBroker.stageEdit(
                            ToolModifyFile.Input(
                                path = stagedFile.absolutePath,
                                oldString = "line",
                                newString = "LINE",
                            )
                        )
                        firstResponse.await()
                    }

                    "second request" -> secondResponse.await()
                    else -> error("Unexpected input: $input")
                }
            },
            onCancelActiveJob = { /* Simulate stale completion after a non-cooperative cancel. */ },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("first request"))
            awaitState(viewModel) { it.isProcessing && it.chatMessages.any { msg -> msg.text == "first request" } }
            assertTrue(harness.deferredToolModifyPermissionBroker.hasPendingEdits())

            viewModel.handleEvent(MainEvent.SendChatMessage("second request"))
            awaitState(viewModel) { it.isProcessing && it.chatMessages.any { msg -> msg.text == "second request" } }

            firstResponse.complete("stale answer")
            advanceUntilIdle()

            assertFalse(harness.deferredToolModifyPermissionBroker.hasPendingEdits())
            assertNull(harness.deferredToolModifyPermissionBroker.snapshotPendingReview())
            assertFalse(viewModel.uiState.value.chatMessages.any { it.text == "stale answer" })

            secondResponse.complete("second answer")
            val finalState = awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { !it.isUser && it.text == "second answer" }
            }
            assertFalse(finalState.chatMessages.any { it.toolModifyReview != null })
        } finally {
            firstResponse.completeExceptionally(CancellationException("cleanup"))
            secondResponse.completeExceptionally(CancellationException("cleanup"))
            harness.clear()
            stagedFile.delete()
        }
    }

    @Test
    fun `stale failure clears staged edit broker state`() = runTest(mainDispatcher) {
        val firstFailureGate = CompletableDeferred<Unit>()
        val secondResponse = CompletableDeferred<String>()
        lateinit var harness: TestHarness
        val stagedFile = java.nio.file.Files.createTempFile(
            FilesToolUtil.homeDirectory.toPath(),
            "souz-stale-failure-",
            ".txt",
        ).toFile().apply {
            writeText("line\n")
        }
        harness = createHarness(
            safeModeEnabled = true,
            executeBehavior = { input ->
                when (input) {
                    "first request" -> {
                        harness.deferredToolModifyPermissionBroker.stageEdit(
                            ToolModifyFile.Input(
                                path = stagedFile.absolutePath,
                                oldString = "line",
                                newString = "LINE",
                            )
                        )
                        firstFailureGate.await()
                        throw IllegalStateException("stale failure")
                    }

                    "second request" -> secondResponse.await()
                    else -> error("Unexpected input: $input")
                }
            },
            onCancelActiveJob = { /* Simulate stale failure after a non-cooperative cancel. */ },
        )

        try {
            val viewModel = harness.viewModel
            advanceUntilIdle()

            viewModel.handleEvent(MainEvent.SendChatMessage("first request"))
            awaitState(viewModel) { it.isProcessing && it.chatMessages.any { msg -> msg.text == "first request" } }
            assertTrue(harness.deferredToolModifyPermissionBroker.hasPendingEdits())

            viewModel.handleEvent(MainEvent.SendChatMessage("second request"))
            awaitState(viewModel) { it.isProcessing && it.chatMessages.any { msg -> msg.text == "second request" } }

            firstFailureGate.complete(Unit)
            advanceUntilIdle()

            assertFalse(harness.deferredToolModifyPermissionBroker.hasPendingEdits())
            assertNull(harness.deferredToolModifyPermissionBroker.snapshotPendingReview())
            assertFalse(viewModel.uiState.value.chatMessages.any { it.text.contains("stale failure") })

            secondResponse.complete("second answer")
            val finalState = awaitState(viewModel) { state ->
                !state.isProcessing && state.chatMessages.any { !it.isUser && it.text == "second answer" }
            }
            assertFalse(finalState.chatMessages.any { it.toolModifyReview != null })
        } finally {
            secondResponse.completeExceptionally(CancellationException("cleanup"))
            harness.clear()
            stagedFile.delete()
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

    private suspend fun <T> TestScope.awaitDeferred(signal: CompletableDeferred<T>): T {
        val deadlineMs = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadlineMs) {
            if (signal.isCompleted) {
                return signal.await()
            }
            runCurrent()
            withContext(Dispatchers.Default) { yield() }
        }
        error("Timed out waiting for deferred completion")
    }

    private suspend fun TestScope.awaitVoiceRequestStarted(
        viewModel: MainViewModel,
        data: ByteArray,
        predicate: (MainState) -> Boolean,
    ): MainState {
        recordVoiceInput(viewModel, data)
        return awaitState(viewModel, predicate)
    }

    private fun forceUiState(viewModel: MainViewModel, state: MainState) {
        val uiStateField = BaseViewModel::class.java.getDeclaredField("_uiState")
        uiStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val uiState = uiStateField.get(viewModel) as MutableStateFlow<MainState>
        uiState.value = state
    }

    private inline fun <reified T> privateField(instance: Any, name: String): T {
        val field = instance::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance) as T
    }

    private suspend fun TestScope.recordVoiceInput(viewModel: MainViewModel, data: ByteArray) {
        prepareAudioCapture(viewModel, data)
        viewModel.handleEvent(MainEvent.StartListening)
        awaitState(viewModel) { it.isListening }
        viewModel.handleEvent(MainEvent.StopListening)
    }

    private fun prepareAudioCapture(viewModel: MainViewModel, data: ByteArray) {
        testAudioRecorder(viewModel).emitOnStop(data)
    }

    private fun testAudioRecorder(viewModel: MainViewModel): TestAudioRecorder {
        val voiceInputUseCase = privateField<VoiceInputUseCase>(viewModel, "voiceInputUseCase")
        return voiceInputUseCase.audioRecorder as TestAudioRecorder
    }

    private fun createHarness(
        executeBehavior: suspend (String) -> String = { "stub response" },
        submitActiveRunBehavior: suspend (String) -> Boolean = { false },
        onCancelActiveJob: () -> Unit = {},
        needsOnboarding: Boolean = false,
        voiceInputReviewEnabled: Boolean = false,
        nativeVoiceHotkeyEnabled: Boolean = false,
        safeModeEnabled: Boolean = false,
        qwenChatKey: String = "",
        configuredModel: LLMModel = LLMModel.Max,
        ambientAnalysisModel: LLMModel = LLMModel.LocalQwen3_4B_Instruct_2507,
        localAvailableModel: LLMModel? = null,
        localModelDownloaded: Boolean = true,
        localEmbeddingsDownloaded: Boolean = localModelDownloaded,
        localModelUiHostOverride: LocalModelUiHost? = null,
        ambientTranscriptionService: TestAmbientTranscriptionService = TestAmbientTranscriptionService(),
        speechRecognitionProviderOverride: SpeechRecognitionProvider? = null,
        recognizeBehavior: suspend (ByteArray) -> LLMResponse.RecognizeResponse = {
            LLMResponse.RecognizeResponse()
        },
        memoryConversationCleanup: MemoryConversationCleanup = NoopMemoryConversationCleanup,
        conversationKnowledgeStore: ConversationKnowledgeStore = mockk(relaxed = true),
        conversationFinishedEvents: MutableList<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>? = null,
        activeAgentId: AgentId = AgentId.GRAPH,
        supportsActiveRunInput: Boolean = activeAgentId == AgentId.SKILLS_GRAPH,
    ): TestHarness {
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        val sideEffects = MutableSharedFlow<AgentSideEffect>()
        val executedInputs = mutableListOf<String>()
        val submittedActiveRunInputs = mutableListOf<String>()
        every { agentFacade.sideEffects } returns sideEffects
        every { agentFacade.currentContext } returns MutableStateFlow(emptyAgentContext())
        coEvery { agentFacade.cancelActiveJob() } answers { onCancelActiveJob.invoke() }
        coEvery { agentFacade.executeForResult(any(), any()) } coAnswers {
            executedInputs += firstArg<String>()
            AgentExecutionResult(
                output = executeBehavior.invoke(firstArg()),
                context = agentFacade.currentContext.value,
            )
        }
        coEvery { agentFacade.submitToActiveRun(any()) } coAnswers {
            val input = firstArg<String>()
            submittedActiveRunInputs += input
            submitActiveRunBehavior(input)
        }
        every { agentFacade.activeAgentId } returns MutableStateFlow(activeAgentId)
        every { agentFacade.supportsActiveRunInput } returns supportsActiveRunInput
        every { agentFacade.availableAgents } returns listOf(activeAgentId)

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        var gigaModelState = configuredModel
        every { settingsProvider.gigaModel } answers { gigaModelState }
        every { settingsProvider.gigaModel = any() } answers { gigaModelState = firstArg() }
        var ambientAnalysisModelState = ambientAnalysisModel
        every { settingsProvider.ambientAnalysisModel } answers { ambientAnalysisModelState }
        every { settingsProvider.ambientAnalysisModel = any() } answers {
            ambientAnalysisModelState = firstArg()
        }
        every { settingsProvider.contextSize } returns 16_000
        every { settingsProvider.useStreaming } returns false
        every { settingsProvider.regionProfile } returns "ru"
        every { settingsProvider.forbiddenFolders } returns emptyList()
        every { settingsProvider.qwenChatKey } returns qwenChatKey
        every { settingsProvider.regionProfile = any() } just runs
        var embeddingsModelState = ru.souz.llms.EmbeddingsModel.GigaEmbeddings
        every { settingsProvider.embeddingsModel } answers { embeddingsModelState }
        every { settingsProvider.embeddingsModel = any() } answers { embeddingsModelState = firstArg() }
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
            every { localModelStore.isPresent(LocalEmbeddingProfiles.default()) } returns localEmbeddingsDownloaded
            every { localModelStore.modelPath(LocalEmbeddingProfiles.default()) } returns
                File(System.getProperty("java.io.tmpdir"), LocalEmbeddingProfiles.default().ggufFilename).toPath()
        }
        var needsOnboardingState = needsOnboarding
        every { settingsProvider.needsOnboarding } answers { needsOnboardingState }
        every { settingsProvider.needsOnboarding = any() } answers { needsOnboardingState = firstArg<Boolean>() }
        var onboardingCompletedState = false
        every { settingsProvider.onboardingCompleted } answers { onboardingCompletedState }
        every { settingsProvider.onboardingCompleted = any() } answers { onboardingCompletedState = firstArg<Boolean>() }
        every { settingsProvider.safeModeEnabled } returns safeModeEnabled
        var voiceInputReviewEnabledState = voiceInputReviewEnabled
        every { settingsProvider.voiceInputReviewEnabled } answers { voiceInputReviewEnabledState }
        every { settingsProvider.voiceInputReviewEnabled = any() } answers {
            voiceInputReviewEnabledState = firstArg<Boolean>()
        }

        val speechPlayer = mockk<UiSpeechPlayer>(relaxed = true)
        val speakingFlow = MutableStateFlow(false)
        every { speechPlayer.isSpeaking } returns speakingFlow

        val desktopInfoRepository = mockk<BackgroundIndexRefresher>(relaxed = true)
        coEvery { desktopInfoRepository.storeDesktopDataDaily() } returns Unit
        coEvery { desktopInfoRepository.rebuildIndexNow() } returns Unit

        val speechRecognitionProvider = speechRecognitionProviderOverride ?: mockk<SpeechRecognitionProvider>(
            relaxed = true
        ).also {
            every { it.enabled } returns true
            every { it.hasRequiredKey } returns true
            coEvery { it.recognize(any()) } coAnswers {
                recognizeBehavior.invoke(firstArg()).result.joinToString("\n").trim()
            }
        }

        val toolPermissionBroker: ToolPermissionBroker = ImmediateToolPermissionBroker(settingsProvider)
        val deferredToolModifyPermissionBroker =
            DeferredToolModifyPermissionBroker(settingsProvider, createDefaultFilesToolUtil(settingsProvider))

        val telegramBotController = mockk<TelegramControlBot>(relaxed = true)
        val incomingMessages = MutableSharedFlow<TelegramControlIncomingMessage>()
        val cleanCommands = MutableSharedFlow<Unit>()
        every { telegramBotController.incomingMessages } returns incomingMessages
        every { telegramBotController.cleanCommands } returns cleanCommands
        val audioRecorder = TestAudioRecorder()
        val desktopPermissionService = mockk<PermissionPromptService>(relaxed = true)
        var voiceHotkeyCallback: ((Boolean) -> Unit)? = null
        every { desktopPermissionService.isSandboxed } returns false
        every { desktopPermissionService.isHeadless } answers {
            !nativeVoiceHotkeyEnabled && java.awt.GraphicsEnvironment.isHeadless()
        }
        every { desktopPermissionService.registerNativeHook() } returns nativeVoiceHotkeyEnabled
        every { desktopPermissionService.canRegisterNativeHookNow() } returns nativeVoiceHotkeyEnabled
        every { desktopPermissionService.registerVoiceInputHotkey(any(), any()) } answers {
            voiceHotkeyCallback = firstArg()
            return@answers {}
        }
        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        every { tokenLogging.requestContextElement(any()) } returns EmptyCoroutineContext
        every { tokenLogging.currentRequestTokenUsage(any()) } returns LLMResponse.Usage(0, 0, 0, 0)
        every { tokenLogging.sessionTokenUsage() } returns LLMResponse.Usage(0, 0, 0, 0)
        val toolInvokeSkill = mockk<ToolInvokeSkill>(relaxed = true)
        every { toolInvokeSkill.delegatedToolName(any()) } returns null

        val di = DI {
            import(sharedUiMainViewModelUseCasesDiModule())

            bindSingleton<AgentFacade> { agentFacade }
            bindSingleton<SpeechRecognitionProvider> { speechRecognitionProvider }
            bindSingleton<BackgroundIndexRefresher> { desktopInfoRepository }
            bindSingleton<SettingsProvider> { settingsProvider }
            bindSingleton<LlmBuildProfile> { llmBuildProfile }
            bindSingleton { localModelStore }
            bindSingleton { localLlamaRuntime }
            bindSingleton<LocalModelUiHost> {
                localModelUiHostOverride
                    ?: DesktopLocalModelUiHost(localModelStore, localLlamaRuntime, desktopInfoRepository)
            }
            bindSingleton<PathOpener> { NoopPathOpener }
            bindSingleton<ChatCommandInputSource> { DesktopChatCommandInputSource(telegramBotController) }
            bindSingleton<UiSpeechPlayer> { speechPlayer }
            bindSingleton { toolPermissionBroker }
            bindSingleton { deferredToolModifyPermissionBroker }
            bindSingleton<TelegramControlBot> { telegramBotController }
            bindSingleton<UiAudioRecorder> { audioRecorder }
            bindSingleton { createDefaultFilesToolUtil(instance<SettingsProvider>()) }
            bindSingleton { FinderPathExtractor(instance()) }
            bindSingleton<PathPicker> { DesktopPathPicker() }
            bindSingleton<DroppedFilePathExtractor> { DesktopDroppedFilePathExtractor() }
            bindSingleton<AttachmentMetadataProvider> { DesktopAttachmentMetadataProvider() }
            bindSingleton<Set<SelectionApprovalSource>> { emptySet() }
            bindSingleton<TokenLogging> { tokenLogging }
            bindSingleton<ToolInvokeSkill> { toolInvokeSkill }
            bindSingleton { DesktopStructuredLogger() }
            conversationFinishedEvents?.let { finishedEvents ->
                bind<ChatObservabilityTracker>(overrides = true) with scoped(mainViewModelDiScope).singleton {
                    ChatObservabilityTracker(
                        onConversationFinished = { conversationId, metrics, reason ->
                            finishedEvents += Triple(conversationId, metrics, reason)
                        },
                    )
                }
            }
            bindSingleton<MemoryConversationCleanup> { memoryConversationCleanup }
            bindSingleton<ConversationKnowledgeStore> { conversationKnowledgeStore }
            bindSingleton<PermissionPromptService> { desktopPermissionService }
            bindSingleton<kotlinx.coroutines.CoroutineScope> { TestScope(mainDispatcher) }
            bindSingleton<AmbientTranscriptionService> { ambientTranscriptionService }
            bindSingleton { SemanticBlockBuilder() }
            bindSingleton<AmbientBlockAnalyzer> { TestAmbientBlockAnalyzer() }
            bind<VoiceInputController>(overrides = true) with scoped(mainViewModelDiScope).singleton {
                VoiceInputUseCase(
                    audioRecorder = audioRecorder,
                    speechRecognitionProvider = speechRecognitionProvider,
                    chatUseCase = instance(),
                    speechUseCase = instance(),
                    permissionsUseCase = instance(),
                    permissionPromptService = desktopPermissionService,
                )
            }
        }

        val viewModel = createMainViewModel(di)

        return TestHarness(
            viewModel = viewModel,
            isSpeakingFlow = speakingFlow,
            settingsProvider = settingsProvider,
            speechPlayer = speechPlayer,
            incomingMessages = incomingMessages,
            sideEffects = sideEffects,
            deferredToolModifyPermissionBroker = deferredToolModifyPermissionBroker,
            executedInputs = executedInputs,
            submittedActiveRunInputs = submittedActiveRunInputs,
            pressVoiceHotkey = { pressed -> checkNotNull(voiceHotkeyCallback)(pressed) },
        )
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
            model = LLMModel.Max.alias, temperature = 0f, toolsByCategory = emptyMap()
        ), history = emptyList(), activeTools = emptyList(), systemPrompt = ""
    )

    private data class TestHarness(
        val viewModel: MainViewModel,
        val isSpeakingFlow: MutableStateFlow<Boolean>,
        val settingsProvider: SettingsProvider,
        val speechPlayer: UiSpeechPlayer,
        val incomingMessages: MutableSharedFlow<TelegramControlIncomingMessage>,
        val sideEffects: MutableSharedFlow<AgentSideEffect>,
        val deferredToolModifyPermissionBroker: DeferredToolModifyPermissionBroker,
        val executedInputs: List<String>,
        val submittedActiveRunInputs: List<String>,
        val pressVoiceHotkey: (Boolean) -> Unit,
    ) {
        fun clear() {
            val onCleared = MainViewModel::class.java.getDeclaredMethod("onCleared")
            onCleared.isAccessible = true
            onCleared.invoke(viewModel)
        }
    }

    private class RecordingMemoryConversationCleanup : MemoryConversationCleanup {
        val cleanedConversationIds = mutableListOf<String>()

        override suspend fun cleanupConversation(conversationId: String) {
            cleanedConversationIds += conversationId
        }
    }

    private class TestAmbientTranscriptionService : AmbientTranscriptionService {
        private val mutableState = MutableStateFlow<AmbientTranscriptionState>(AmbientTranscriptionState.Stopped)
        var startCalls: Int = 0
        var stopCalls: Int = 0
        var clearCalls: Int = 0
        override val state = mutableState
        override val transcriptEvents = emptyFlow<AmbientTranscriptEvent>()

        override suspend fun availability(locale: String): AmbientSpeechAvailability =
            AmbientSpeechAvailability.Available

        override suspend fun start(locale: String): AmbientSpeechAvailability {
            startCalls += 1
            mutableState.value = AmbientTranscriptionState.Listening(locale)
            return AmbientSpeechAvailability.Available
        }

        override suspend fun stop() {
            stopCalls += 1
            mutableState.value = AmbientTranscriptionState.Stopped
        }

        override suspend fun clearTranscript() {
            clearCalls += 1
        }
    }

    private class TestLocalModelUiHost(
        var missingModels: Set<LLMModel> = emptySet(),
    ) : LocalModelUiHost {
        var downloadPromptCalls: Int = 0
        val downloadPromptModels: MutableList<LLMModel> = mutableListOf()
        var schedulePreloadCalls: Int = 0

        override suspend fun downloadPromptFor(model: LLMModel): LocalModelDownloadPromptUi? {
            downloadPromptCalls += 1
            downloadPromptModels += model
            return if (model in missingModels) {
                LocalModelDownloadPromptUi(
                    model = model,
                    profileId = "test-profile",
                    profileDisplayName = model.displayName,
                    downloads = emptyList(),
                )
            } else {
                null
            }
        }

        override suspend fun startDownload(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            currentJob: Job?,
            prompt: LocalModelDownloadPromptUi?,
            updateDownloadState: suspend (LocalModelDownloadStateUi?) -> Unit,
            onSuccess: suspend (LocalModelDownloadPromptUi) -> Unit,
            onError: suspend (Throwable) -> Unit,
        ): Job? = currentJob

        override suspend fun cancelDownload(
            currentJob: Job?,
            hasActiveDownload: Boolean,
            clearDownloadState: suspend () -> Unit,
            onCancelled: suspend () -> Unit,
        ): Job? {
            clearDownloadState()
            return null
        }

        override fun rebuildIndex(scope: CoroutineScope, dispatcher: CoroutineDispatcher) = Unit

        override fun schedulePreload(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            currentJob: Job?,
            model: LLMModel,
        ): Job? {
            schedulePreloadCalls += 1
            return currentJob
        }

        fun clearCounters() {
            downloadPromptCalls = 0
            downloadPromptModels.clear()
            schedulePreloadCalls = 0
        }
    }

    private class TestAmbientBlockAnalyzer : AmbientBlockAnalyzer {
        override suspend fun analyze(block: AmbientSemanticBlock) = null
    }

    private class TestAudioRecorder : UiAudioRecorder {
        private var audioOnStop: ByteArray? = null
        private var stopFailureMessage: String? = null
        private var deferredStop: CompletableDeferred<ByteArray?>? = null
        var startCalls = 0
            private set
        override val recordingState = MutableStateFlow<UiAudioRecordingState>(UiAudioRecordingState.Idle)

        override suspend fun logState(): Nothing = awaitCancellation()

        override fun start(): Boolean {
            startCalls += 1
            recordingState.value = UiAudioRecordingState.Recording
            return true
        }

        override suspend fun stop(): ByteArray? {
            stopFailureMessage?.let { message ->
                stopFailureMessage = null
                recordingState.value = UiAudioRecordingState.Error(message)
                return null
            }
            deferredStop?.let { stop ->
                recordingState.value = UiAudioRecordingState.Stopping
                val audio = stop.await()
                recordingState.value = UiAudioRecordingState.Idle
                return audio
            }
            val audio = audioOnStop
            audioOnStop = null
            recordingState.value = UiAudioRecordingState.Idle
            return audio
        }

        fun emitOnStop(data: ByteArray) {
            audioOnStop = data
        }

        fun failNextStop(message: String = "Failed to stop recording") {
            stopFailureMessage = message
        }

        fun deferNextStop() {
            deferredStop = CompletableDeferred()
        }

        suspend fun completeDeferredStop() {
            check(recordingState.value == UiAudioRecordingState.Stopping)
            val audio = audioOnStop
            audioOnStop = null
            checkNotNull(deferredStop).complete(audio)
            deferredStop = null
        }
    }
}
