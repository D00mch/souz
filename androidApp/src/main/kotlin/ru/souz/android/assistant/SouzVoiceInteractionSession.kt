package ru.souz.android.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.android.R
import ru.souz.android.souzAgentRuntime

private const val AUTO_HIDE_DELAY_MS = 5_000L
private const val SPEECH_START_TIMEOUT_MS = 10_000L
private const val HIDE_GRACE_MS = 700L

class SouzVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val coordinator: VoiceAssistantTurnCoordinator by lazy {
        context.souzAgentRuntime.di.direct.instance()
    }

    private lateinit var statusView: TextView
    private lateinit var replyView: TextView
    private var autoHideJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        applyBottomSheetWindow()
    }

    /** Applied on show as well, because the session window is re-laid out when it is shown. */
    private fun applyBottomSheetWindow() {
        window.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.SouzAssistantWindowAnimation)
        }
    }

    override fun onCreateContentView(): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        statusView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        replyView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.LTGRAY)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 12, 12, 14))
                cornerRadius = dp(14).toFloat()
            }
            addView(statusView)
            addView(replyView)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply { setMargins(dp(24), 0, dp(24), dp(24)) }
        }

        return FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(panel)
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        applyBottomSheetWindow()
        scope.coroutineContext.cancelChildren()
        scope.launch { coordinator.state.collect(::render) }
        coordinator.startTurn()
    }

    override fun onHide() {
        autoHideJob?.cancel()
        coordinator.cancelTurn()
        scope.coroutineContext.cancelChildren()
        super.onHide()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun render(state: AssistantTurnState) {
        autoHideJob?.cancel()
        when (state) {
            AssistantTurnState.Idle -> {
                statusView.text = ""
                replyView.text = ""
            }

            AssistantTurnState.Listening -> {
                statusView.text = "Слушаю…"
                replyView.text = ""
            }

            AssistantTurnState.Recognizing -> statusView.text = "Распознаю…"

            is AssistantTurnState.Thinking -> {
                statusView.text = state.request
                replyView.text = "Думаю…"
            }

            is AssistantTurnState.Answered -> {
                statusView.text = state.request
                replyView.text = state.reply
                hideAfterSpeech()
            }

            is AssistantTurnState.Failed -> {
                statusView.text = state.message
                replyView.text = ""
                hideAfter(AUTO_HIDE_DELAY_MS)
            }
        }
    }

    /** Synthesis takes a few seconds, so the overlay must outlive the reply, not a fixed timer. */
    private fun hideAfterSpeech() {
        autoHideJob = scope.launch {
            withTimeoutOrNull(SPEECH_START_TIMEOUT_MS) { coordinator.isSpeaking.first { it } }
            coordinator.isSpeaking.first { !it }
            delay(HIDE_GRACE_MS)
            hide()
        }
    }

    private fun hideAfter(delayMs: Long) {
        autoHideJob = scope.launch {
            delay(delayMs)
            hide()
        }
    }
}
