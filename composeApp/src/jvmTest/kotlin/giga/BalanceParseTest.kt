package giga

import ru.gigadesk.giga.GigaResponse
import com.fasterxml.jackson.module.kotlin.readValue
import ru.gigadesk.giga.gigaJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class BalanceParseTest {
    @Test
    fun parseBalance() {
        val json = """{"balance":[{"usage":"GigaChat","value":42}]}"""
        val resp: GigaResponse.Balance.Ok = gigaJsonMapper.readValue(json)
        assertEquals(42, resp.balance[0].value)
    }
}
