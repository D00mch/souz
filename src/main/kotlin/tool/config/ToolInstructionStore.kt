package com.dumch.tool.config

import com.dumch.db.DesktopInfoRepository
import com.dumch.db.StorredData
import com.dumch.db.StorredType
import com.dumch.tool.FewShotExample
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ReturnParameters
import com.dumch.tool.ReturnProperty
import com.dumch.tool.ToolSetup
import kotlinx.coroutines.runBlocking

class ToolInstructionStore(
    private val config: ConfigStore,
    private val repo: DesktopInfoRepository,
): ToolSetup<ToolInstructionStore.Input> {

    data class Input(
        @InputParamDescription("Instruction name")
        val name: String,
        @InputParamDescription("Instruction action")
        val action: String
    )

    override val name: String = "InstructionStore"
    override val description: String = "Stores instructions that will be available in the future and will be provided as user message"
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Сохрани инструкцию, что слово Поиск должна открывать Safari",
            params = mapOf("name" to "Поиск", "action" to "Открыть Safari")
        ),
        FewShotExample(
            request = "Сохрани инструкцию, что фраза Покажи брата должно открывать фотографию Дяди степана",
            params = mapOf("name" to "Покажи брата", "action" to "Открыть фотографию Дяди степана")
        ),
        FewShotExample(
            request = "Запомни инструкцию, чтобы слово ”Скрин” делало скриншот экрана и отправляло его в GigaChat",
            params = mapOf("name" to "скрин", "action" to "Сделать скриншот экрана и отправить его в GigaChat")
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "Operation status"))
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val currentInstructions = config.get<ArrayList<Input>>(INSTUCTIONS_KEY, ArrayList())
        currentInstructions.add(input)

        val dbInstructions = buildInstruction(input.name, input.action)
        repo.storeDesktopInfo(listOf(StorredData(dbInstructions, StorredType.INSTRUCTIONS)))

        config.put(INSTUCTIONS_KEY, currentInstructions)
        return "Instruction stored"
    }


    companion object {
        const val INSTUCTIONS_KEY = "INSTRUCTIONS"

        fun buildInstruction(name: String, action: String): String {
            return "Когда я говорю: `$name`, выполняй инструкцию: $action"
        }
    }
}