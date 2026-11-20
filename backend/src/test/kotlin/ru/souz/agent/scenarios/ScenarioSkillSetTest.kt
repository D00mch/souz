package ru.souz.agent.scenarios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioSkillSetTest {
    @Test
    fun `bundled archive is available with the full catalog and scenario scripts`() {
        val bundleFiles = ScenarioSkillSet.load().bundles.associate { bundle ->
            bundle.skillId.value to bundle.files.map { it.normalizedPath }.toSet()
        }

        assertEquals(31, bundleFiles.size)
        assertTrue("scripts/doctor.sh" in bundleFiles.getValue("doctor-appointment"))
        assertTrue("scripts/trains.sh" in bundleFiles.getValue("train-tickets"))
        assertTrue("scripts/afisha.sh" in bundleFiles.getValue("afisha-where-to-go-cinema-theatre-concerts-tickets"))
        assertTrue("ring-health" in bundleFiles)
    }
}
