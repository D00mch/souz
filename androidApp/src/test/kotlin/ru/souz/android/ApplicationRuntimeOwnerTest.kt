package ru.souz.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ApplicationRuntimeOwnerTest {
    @Test
    fun `activity recreation reuses one application runtime and closes it once`() {
        var creations = 0
        var closes = 0
        val owner = ApplicationRuntimeOwner {
            creations += 1
            AutoCloseable { closes += 1 }
        }

        val firstActivityRuntime = owner.runtime
        val recreatedActivityRuntime = owner.runtime
        owner.close()
        owner.close()

        assertSame(firstActivityRuntime, recreatedActivityRuntime)
        assertEquals(1, creations)
        assertEquals(1, closes)
    }
}
