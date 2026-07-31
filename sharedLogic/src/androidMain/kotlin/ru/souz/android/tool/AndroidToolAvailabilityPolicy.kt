package ru.souz.android.tool

import ru.souz.tool.ToolAvailabilityPolicy
import ru.souz.tool.ToolCategory

object AndroidToolAvailabilityPolicy : ToolAvailabilityPolicy {
    val disabledCategories: Set<ToolCategory> = setOf(
        ToolCategory.IMAGE_GENERATION,
        ToolCategory.DESKTOP,
    )

    override fun isCategoryForceDisabled(category: ToolCategory): Boolean =
        category in disabledCategories
}
