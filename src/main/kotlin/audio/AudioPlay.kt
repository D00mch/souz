package com.dumch.audio

fun playText(text: String, speed: Int = 230) {
    val saveEnding = "$text "
    ProcessBuilder("say", "-r", "$speed", saveEnding).start().waitFor()
}

private val random = java.util.Random()

fun playTextRand(speed: Int = 230, vararg texts: String) {
    val text = texts[random.nextInt(texts.size)]
    playText(text, speed)
}

fun main() {
    playText("Можешь оценить скорость моей речи, она достаточно быстрая?")
}