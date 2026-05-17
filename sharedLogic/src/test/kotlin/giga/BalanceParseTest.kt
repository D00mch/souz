package giga

import ru.souz.llms.LLMResponse
import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class BalanceParseTest {
    @Test
    fun parseBalance() {
        val json = """{"balance":[{"usage":"GigaChat","value":42}]}"""
        val resp: LLMResponse.Balance.Ok = restJsonMapper.readValue(json)
        assertEquals(42, resp.balance[0].value)
    }
}
