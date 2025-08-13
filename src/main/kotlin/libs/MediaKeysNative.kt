package com.dumch.libs

class MediaKeysNative {
    companion object {
        init {
            System.loadLibrary("MediaKeys")
        }
    }

    private external fun sendMediaKey(keyCode: Int)

    // Константы для медиа-клавиш
    object KeyCodes {
        const val PLAY: Int = 16
        const val NEXT: Int = 17
        const val PREV: Int = 18
    }

    // Публичные методы
    fun playPause() = sendMediaKey(KeyCodes.PLAY)
    fun nextTrack() = sendMediaKey(KeyCodes.NEXT)
    fun previousTrack() = sendMediaKey(KeyCodes.PREV)

}