package com.dumch.tool.desktop

import com.dumch.tool.*
import org.openqa.selenium.WebDriver
import org.openqa.selenium.safari.SafariDriver
import org.openqa.selenium.safari.SafariOptions
import org.slf4j.LoggerFactory

/**
 * Opens a given URL in Safari using Selenium WebDriver.
 */
class ToolSafariBrowserInterfaction : ToolSetup<ToolSafariBrowserInterfaction.Input> {
    private val l = LoggerFactory.getLogger(ToolSafariBrowserInterfaction::class.java)

    data class Input(
        @InputParamDescription("The url to open, e.g., 'https://www.google.com'")
        val url: String = "https://www.google.com"
    )

    override val name: String = "SafariBrowserInterfaction"
    override val description: String = "Opens a web page in Safari via Selenium WebDriver"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Открой google.com в Safari",
            params = mapOf("url" to "https://www.google.com")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        return try {
            val options = SafariOptions()
            val driver: WebDriver = SafariDriver(options)
            driver.get(input.url)
            "Done"
        } catch (e: Exception) {
            "Error in ToolSafariBrowserInterfaction: ${e.message}".also {
                l.error(it, e)
            }
        }
    }
}

