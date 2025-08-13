package com.dumch.libs

class MediaKeysNative {
    companion object {
        init {
            loadFromResources()
        }

        private fun loadFromResources() {
            val libName = "libMediaKeys.dylib"
            val url = MediaKeysNative::class.java.classLoader.getResource(libName)
                ?: throw UnsatisfiedLinkError("Native library $libName not found")

            if (url.protocol == "file") {
                System.load(url.toURI().path)
            } else {
                val suffix = if (libName.contains('.')) libName.substring(libName.lastIndexOf('.')) else null
                val temp = kotlin.io.path.createTempFile("MediaKeys", suffix ?: "")
                url.openStream().use { input ->
                    java.nio.file.Files.copy(
                        input,
                        temp,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }
                temp.toFile().deleteOnExit()
                System.load(temp.toAbsolutePath().toString())
            }
        }
    }

    private external fun playPauseNative()
    private external fun nextTrackNative()
    private external fun previousTrackNative()

    // Публичные методы вызывают нативные функции
    fun playPause() = playPauseNative()
    fun nextTrack() = nextTrackNative()
    fun previousTrack() = previousTrackNative()

}
