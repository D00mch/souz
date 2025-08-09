package com.dumch.audio

fun playText(text: String) {
    val saveEnding = "$text р"
    ProcessBuilder("say", "-r", "230", saveEnding).start().waitFor()
}

fun main() {
    playText("Можешь оценить скорость моей речи, она достаточно быстрая?")
}