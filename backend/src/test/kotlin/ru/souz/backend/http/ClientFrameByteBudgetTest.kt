package ru.souz.backend.http

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import ru.souz.llms.runtime.ImageSource

class ClientFrameByteBudgetTest {
    @Test
    fun `weighted ingress permits mixed sizes and releases exactly once`() {
        val budget = ClientFrameByteBudget(30_000)
        val small = assertNotNull(budget.tryReserve(1_000))
        val large = assertNotNull(budget.tryReserve(4_000))
        assertNull(budget.tryReserve(1))
        small.close()
        small.close()
        val replacement = assertNotNull(budget.tryReserve(1_000))
        assertNull(budget.tryReserve(1), "Repeated cleanup must not increase capacity")
        replacement.close()
        large.close()
        assertNotNull(budget.tryReserve(5_000)).close()
        assertNull(budget.tryReserve(Int.MAX_VALUE), "Byte weighting must not overflow")
    }

    @Test
    fun `default process budget accepts one maximum image without allowing eight retained copies`() {
        val budget = ClientFrameByteBudget()
        val maximum = assertNotNull(budget.tryReserve(ImageSource.MAX_ENCODED_BYTES + 4096))
        assertNull(budget.tryReserve(ImageSource.MAX_ENCODED_BYTES))
        assertNotNull(budget.tryReserve(1024)).close()
        maximum.close()
        assertNotNull(budget.tryReserve(ImageSource.MAX_ENCODED_BYTES + 4096)).close()
    }
}
