package ru.souz.agent.scenarios

import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipInputStream

internal data class ScenarioSkillSet(val bundles: List<SkillBundle>, val sha256: String) {
    companion object {
        fun load(): ScenarioSkillSet {
            val archive = requireNotNull(ScenarioSkillSet::class.java.getResourceAsStream(ARCHIVE_RESOURCE)) {
                "Missing scenario Skill resource $ARCHIVE_RESOURCE."
            }.use { it.readBytes() }
            val grouped = mutableMapOf<String, MutableList<SkillFile>>()
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.startsWith("skills/")) continue
                    val path = entry.name.removePrefix("skills/")
                    val relative = path.substringAfter('/', missingDelimiterValue = "")
                    if (relative.isNotEmpty()) {
                        grouped.getOrPut(path.substringBefore('/')) { mutableListOf() }
                            .add(SkillFile(relative, zip.readBytes()))
                    }
                }
            }
            val bundles = grouped.toSortedMap().mapNotNull { (id, files) ->
                if (files.none { it.normalizedPath == "SKILL.md" }) return@mapNotNull null
                SkillBundle.fromFiles(SkillId(id), files)
            }
            val required = setOf("doctor-appointment", "train-tickets", "afisha-where-to-go-cinema-theatre-concerts-tickets")
            val missing = required - bundles.map { it.skillId.value }.toSet()
            require(missing.isEmpty()) {
                "The Skill archive is missing: ${missing.joinToString()}."
            }
            val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive))
            return ScenarioSkillSet(bundles, sha256)
        }

        private const val ARCHIVE_RESOURCE = "/skills/scenario-set.zip"
    }
}
