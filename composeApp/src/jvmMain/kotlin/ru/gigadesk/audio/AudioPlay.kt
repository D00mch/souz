@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package ru.gigadesk.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.tool.config.ToolSoundConfig
import ru.gigadesk.tool.config.ToolSoundConfig.Companion.DEFAULT_SPEED
import java.util.Random

private const val SPEED_KEY = ToolSoundConfig.SPEED_KEY

class Say {
    private var sayProcess: Process? = null
    private val random = Random()

    private val jobs = ArrayList<Job>()
    private val managingScope = CoroutineScope(SupervisorJob() + newSingleThreadContext("say-manage"))
    private val voiceScope = CoroutineScope(SupervisorJob() + newSingleThreadContext("say"))

    fun queue(text: String, speed: Int = ConfigStore.get(SPEED_KEY, DEFAULT_SPEED)) = managingScope.launch {
        val j = voiceScope.launch { playText(text, speed) }
        jobs.add(j)
    }

    fun clearQueue() = managingScope.launch {
        jobs.forEach { it.cancel() }
        jobs.clear()
        stopPlayText()
    }

    /** Global Voice command. Only one speech is possible at the time. Use [stopPlayText] to stop the current one */
    private fun playText(text: String, speed: Int = ConfigStore.get(SPEED_KEY, DEFAULT_SPEED)) {
        stopPlayText()
        val saveEnding = "$text "
        sayProcess = ProcessBuilder("say", "-r", "$speed", "--", saveEnding).start()
        sayProcess?.waitFor()
    }

    /** Stops the speech started by [playText] */
    private fun stopPlayText() {
        sayProcess?.destroyForcibly()
        sayProcess = null
    }

    fun playTextRand(speed: Int = ConfigStore.get(SPEED_KEY, DEFAULT_SPEED), vararg texts: String) {
        val text = texts[random.nextInt(texts.size)]
        queue(text, speed)
    }

    fun playMacPing() {
        val audio = AudioSystem.getAudioInputStream(File("/System/Library/Sounds/Tink.aiff"))
        val clip = AudioSystem.getClip()
        clip.addLineListener { if (it.type == LineEvent.Type.STOP) clip.close() }
        clip.open(audio)
        clip.start()
    }

    /** Opens system settings with Spoken Content. */
    fun chooseVoice() {
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
