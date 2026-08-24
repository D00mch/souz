package ru.souz.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.label_codex_polling
import souz.sharedui.generated.resources.label_codex_user_code
import souz.sharedui.generated.resources.label_copy

@Composable
internal fun CodexOAuthUserCode(
    userCode: String,
    textColor: Color,
    secondaryTextColor: Color,
    borderColor: Color,
    onOpenProviderLink: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    Text(
        text = stringResource(Res.string.label_codex_user_code),
        style = MaterialTheme.typography.bodySmall,
        color = secondaryTextColor,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = userCode,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { clipboardManager.setText(AnnotatedString(userCode)) },
            border = BorderStroke(1.dp, borderColor),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(Res.string.label_copy),
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
            )
        }
    }
    Text(
        text = ApiKeyProvider.CODEX.url,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onOpenProviderLink),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(Res.string.label_codex_polling),
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor,
        )
    }
}
