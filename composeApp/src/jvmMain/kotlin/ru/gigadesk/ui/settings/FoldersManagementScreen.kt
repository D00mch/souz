package ru.gigadesk.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.apache.batik.svggen.SVGCSSStyler.style
import org.kodein.di.compose.localDI
import ru.gigadesk.ui.components.LabeledTextField
import ru.gigadesk.ui.glassColors
import ru.gigadesk.ui.main.RealLiquidGlassCard

@Composable
fun FoldersManagementScreen(
    onClose: () -> Unit,
) {
    val di = localDI()
    val viewModel = viewModel { FoldersManagementViewModel(di) }
    val state = viewModel.uiState.collectAsState().value

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FoldersManagementEffect.CloseScreen -> onClose()
            }
        }
    }

    FoldersManagementScreen(
        state = state,
        onFoldersChange = { folders ->
            viewModel.send(FoldersManagementEvent.InputForbiddenFolders(folders))
        },
        onClose = { viewModel.send(FoldersManagementEvent.CloseScreen) }
    )
}

@Composable
private fun FoldersManagementScreen(
    state: FoldersManagementState,
    onFoldersChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Запретные папки",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.glassColors.textPrimary
                        )
                        Text(
                            text = """
                                Укажите по одному пути на строку. Эти папки будут исключены из работы агента. 
                                Всё вне домашней директории запрещено по умолчанию.
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f)
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Назад",
                            tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f)
                        )
                    }
                }

                LabeledTextField(
                    label = "Список папок",
                    value = state.forbiddenFoldersInput,
                    onValueChange = onFoldersChange,
                    modifier = Modifier.fillMaxSize(),
                    singleLine = false
                )
            }
        }
    }
}
