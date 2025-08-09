package com.dumch.audio

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

private var sayProcess: Process? = null

fun playText(text: String, speed: Int = 230) {
    stopPlayText()
    val saveEnding = "$text "
    sayProcess = ProcessBuilder("say", "-r", "$speed", saveEnding).start()
    sayProcess?.waitFor()
}

fun stopPlayText() {
    sayProcess?.destroyForcibly()
    sayProcess = null
}

private val random = java.util.Random()

fun playTextRand(speed: Int = 230, vararg texts: String) {
    val text = texts[random.nextInt(texts.size)]
    playText(text, speed)
}

val audio = AudioSystem.getAudioInputStream(File("/System/Library/Sounds/Tink.aiff"))
val clip = AudioSystem.getClip()

fun playMacPing() {
    clip.addLineListener { if (it.type == LineEvent.Type.STOP) clip.close() }
    clip.open(audio)
    clip.start()
}

fun main() {
    playMacPing()
//    playText("Можешь оценить скорость моей речи, она достаточно быстрая?")
}