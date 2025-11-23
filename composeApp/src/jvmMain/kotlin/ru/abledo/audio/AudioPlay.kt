package ru.abledo.audio

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import ru.abledo.db.ConfigStore
import ru.abledo.tool.config.ToolSoundConfig
import ru.abledo.tool.config.ToolSoundConfig.Companion.DEFAULT_SPEED
import java.util.Random

private const val SPEED_KEY = ToolSoundConfig.SPEED_KEY

private var sayProcess: Process? = null

fun playText(text: String, speed: Int = ConfigStore.get(SPEED_KEY, DEFAULT_SPEED)) {
    stopPlayText()
    val saveEnding = "$text "
    sayProcess = ProcessBuilder("say", "-r", "$speed", saveEnding).start()
    sayProcess?.waitFor()
}

fun stopPlayText() {
    sayProcess?.destroyForcibly()
    sayProcess = null
}

private val random = Random()

fun playTextRand(speed: Int = ConfigStore.get(SPEED_KEY, DEFAULT_SPEED), vararg texts: String) {
    val text = texts[random.nextInt(texts.size)]
    playText(text, speed)
}

fun playMacPing() {
    val audio = AudioSystem.getAudioInputStream(File("/System/Library/Sounds/Tink.aiff"))
    val clip = AudioSystem.getClip()
    clip.addLineListener { if (it.type == LineEvent.Type.STOP) clip.close() }
    clip.open(audio)
    clip.start()
}

fun main() {
    playMacPing()
//    playText("Можешь оценить скорость моей речи, она достаточно быстрая?")
}