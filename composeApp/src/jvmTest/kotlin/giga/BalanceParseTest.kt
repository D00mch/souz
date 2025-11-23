package giga

import ru.abledo.giga.GigaResponse
import ru.abledo.giga.objectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals

class BalanceParseTest {
    @Test
    fun parseBalance() {
        val json = """{"balance":[{"usage":"GigaChat","value":42}]}"""
        val resp: GigaResponse.Balance.Ok = objectMapper.readValue(json)
        assertEquals(42, resp.balance[0].value)
    }
}
