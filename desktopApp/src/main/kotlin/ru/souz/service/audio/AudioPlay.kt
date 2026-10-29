@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package ru.souz.service.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runInterruptible
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import ru.souz.db.ConfigStore
import ru.souz.tool.config.ToolSoundConfig
import ru.souz.tool.config.ToolSoundConfig.Companion.DEFAULT_SPEED
import ru.souz.ui.host.UiSpeechPlayer
import java.util.Random

private const val SPEED_KEY = ToolSoundConfig.SPEED_KEY

class Say(
    managingDispatcher: CoroutineDispatcher = newSingleThreadContext("say-manage"),
    voiceDispatcher: CoroutineDispatcher = newSingleThreadContext("say"),
    private val startSpeech: (String, Int) -> Process = { text, speed ->
        ProcessBuilder("say", "-r", "$speed", "--", "$text ").start()
    },
) : UiSpeechPlayer {
    private val random = Random()

    private val jobs = ArrayList<Job>()
    private val _isSpeaking = MutableStateFlow(false)
    private val managingScope = CoroutineScope(SupervisorJob() + managingDispatcher)
    private val voiceScope = CoroutineScope(SupervisorJob() + voiceDispatcher)

    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    override fun queue(text: String, speed: Int?): Job {
        val effectiveSpeed = speed ?: ConfigStore.get(SPEED_KEY, DEFAULT_SPEED)
        val job = voiceScope.launch(start = CoroutineStart.LAZY) { playText(text, effectiveSpeed) }
        managingScope.launch {
            jobs.add(job)
            updateSpeakingState()
            job.invokeOnCompletion {
                managingScope.launch {
                    jobs.remove(job)
                    updateSpeakingState()
                }
            }
            job.start()
        }
        return job
    }

    fun queue(text: String) = queue(text, speed = null)

    fun queue(text: String, speed: Int) = queue(text, speed = speed as Int?)

    override fun clearQueue() {
        managingScope.launch {
            jobs.forEach { it.cancel() }
            jobs.clear()
            updateSpeakingState()
        }
    }

    private suspend fun playText(text: String, speed: Int) = runInterruptible {
        val process = startSpeech(text, speed)
        try {
            process.waitFor()
        } finally {
            process.destroyForcibly()
        }
    }

    private fun updateSpeakingState() {
        _isSpeaking.value = jobs.isNotEmpty()
    }

    override fun playTextRand(speed: Int, vararg texts: String) {
        val text = texts[random.nextInt(texts.size)]
        queue(text, speed)
    }

    fun playTextRand(vararg texts: String) = playTextRand(ConfigStore.get(SPEED_KEY, DEFAULT_SPEED), *texts)

    override fun playMacPing() {
        val audio = AudioSystem.getAudioInputStream(File("/System/Library/Sounds/Tink.aiff"))
        val clip = AudioSystem.getClip()
        clip.addLineListener { if (it.type == LineEvent.Type.STOP) clip.close() }
        clip.open(audio)
        clip.start()
    }

    override fun playMacPingMsg() {
        val audio = AudioSystem.getAudioInputStream(File("/System/Library/Sounds/Glass.aiff"))
        val clip = AudioSystem.getClip()
        clip.addLineListener { if (it.type == LineEvent.Type.STOP) clip.close() }
        clip.open(audio)
        clip.start()
    }

    /** Opens system settings with Spoken Content. */
    override fun chooseVoice() {
        ProcessBuilder(
            "open",
            "x-apple.systempreferences:com.apple.preference.universalaccess?SpokenContent"
        ).start()
    }
}

fun main() {
    Say().playMacPing()
//    Say().playText("Можешь оценить скорость моей речи, она достаточно быстрая?")
}
