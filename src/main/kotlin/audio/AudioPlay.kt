package com.dumch.audio

fun playText(text: String) {
    val saveEnding = "$text р"
    ProcessBuilder("say", saveEnding).start().waitFor()
}

fun main() {
    playText("Привет, мир!")
}