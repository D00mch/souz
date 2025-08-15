package tool.desktop

import com.dumch.tool.BadInputException
import com.dumch.tool.desktop.ToolCreateNewBrowserTab
import com.dumch.tool.desktop.ToolCreateNote
import com.dumch.tool.desktop.ToolCollectButtons
import com.dumch.tool.ToolRunBashCommand
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

class ToolValidationTest {
    @Test
    fun `create new browser tab rejects blank url`() {
        assertThrows<BadInputException> {
            ToolCreateNewBrowserTab(ToolRunBashCommand).invoke(
                ToolCreateNewBrowserTab.Input("")
            )
        }
    }

    @Test
    fun `collect buttons rejects invalid number`() {
        assertThrows<BadInputException> {
            ToolCollectButtons(ToolRunBashCommand).invoke(
                ToolCollectButtons.Input("abc")
            )
        }
    }

    @Test
    fun `create note rejects blank text`() {
        assertThrows<BadInputException> {
            ToolCreateNote(ToolRunBashCommand).invoke(
                ToolCreateNote.Input("")
            )
        }
    }
}
