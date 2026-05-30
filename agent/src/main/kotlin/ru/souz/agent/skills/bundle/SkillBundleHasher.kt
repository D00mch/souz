package ru.souz.agent.skills.bundle

import java.security.MessageDigest

object SkillBundleHasher {
    fun hash(bundle: SkillBundle): String {
        val finalDigest = MessageDigest.getInstance("SHA-256")
        bundle.files
            .sortedBy { it.normalizedPath }
            .forEach { file ->
                val contentHash = MessageDigest.getInstance("SHA-256")
                    .digest(file.content)
                    .toHex()
                finalDigest.update(file.normalizedPath.toByteArray(Charsets.UTF_8))
                finalDigest.update('\n'.code.toByte())
                finalDigest.update(contentHash.toByteArray(Charsets.UTF_8))
                finalDigest.update('\n'.code.toByte())
            }
        return finalDigest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
