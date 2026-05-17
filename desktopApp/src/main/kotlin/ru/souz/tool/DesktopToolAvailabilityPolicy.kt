package ru.souz.tool

import ru.souz.service.telegram.TelegramAuthStep
import ru.souz.service.telegram.TelegramPlatformSupport
import ru.souz.service.telegram.TelegramService

class DesktopToolAvailabilityPolicy(
    private val telegramService: TelegramService,
    private val telegramPlatformSupport: TelegramPlatformSupport,
) : ToolAvailabilityPolicy {
    override fun isCategoryForceDisabled(category: ToolCategory): Boolean =
        when (category) {
            ToolCategory.TELEGRAM ->
                !telegramPlatformSupport.isSupported() ||
                    telegramService.authState.value.step != TelegramAuthStep.READY

            else -> false
        }
}
