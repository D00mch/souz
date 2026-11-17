package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class VoiceRecognitionModelTest {
    @Test
    fun `retired speech selections resolve to Polza and serialize with the new identity`() {
        for (value in listOf("SaluteSpeech", " salute-speech ", "SALUTESPEECH", "PolzaGigaAm", "ai-sage/gigaam-v3")) {
            assertEquals(VoiceRecognitionModel.PolzaGigaAm, findVoiceRecognitionModel(value))
        }
        assertEquals(VoiceRecognitionModel.PolzaGigaAm, restJsonMapper.readValue<VoiceRecognitionModel>("\"SaluteSpeech\""))
        assertEquals(VoiceRecognitionProvider.POLZA, restJsonMapper.readValue<VoiceRecognitionProvider>("\"SALUTE_SPEECH\""))
        assertEquals("\"PolzaGigaAm\"", restJsonMapper.writeValueAsString(VoiceRecognitionModel.PolzaGigaAm))
        assertFalse(VoiceRecognitionModel.entries.any { it.name == "SaluteSpeech" })
        assertNull(findVoiceRecognitionModel("unknown"))
        assertNull(findVoiceRecognitionModel(" "))
    }
}
