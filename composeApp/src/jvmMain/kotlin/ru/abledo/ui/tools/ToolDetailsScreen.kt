package ru.abledo.ui.tools

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Divider
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.ToolCategory
import ru.abledo.ui.AppTheme
import ru.abledo.ui.glassColors
import ru.abledo.ui.main.GlassCard

private val ToolDetailsWindowSize = DpSize(width = 680.dp, height = 760.dp)

@Composable
fun ToolDetailsScreen(
    category: ToolCategory,
    toolName: String,
    onClose: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
) {
    val di = localDI()
    val viewModel = viewModel { ToolDetailsViewModel(di, category, toolName) }
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ToolDetailsEffect.Saved -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    ToolDetailsScreen(
        state = state,
        onDescriptionChange = { viewModel.send(ToolDetailsEvent.UpdateDescription(it)) },
        onToggleEnabled = { viewModel.send(ToolDetailsEvent.ToggleEnabled(it)) },
        onAddExample = { viewModel.send(ToolDetailsEvent.AddExample) },
        onRemoveExample = { viewModel.send(ToolDetailsEvent.RemoveExample(it)) },
        onExampleRequestChange = { id, value -> viewModel.send(ToolDetailsEvent.UpdateExampleRequest(id, value)) },
        onExampleParamsChange = { id, value -> viewModel.send(ToolDetailsEvent.UpdateExampleParams(id, value)) },
        onSave = { viewModel.send(ToolDetailsEvent.Save) },
        onReset = { viewModel.send(ToolDetailsEvent.ResetToDefault) },
        onResizeRequest = onResizeRequest,
        snackbarHostState = snackbarHostState,
        onClose = onClose,
    )
}

@Composable
fun ToolDetailsScreen(
    state: ToolDetailsState,
    onDescriptionChange: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onAddExample: () -> Unit,
    onRemoveExample: (String) -> Unit,
    onExampleRequestChange: (String, String) -> Unit,
    onExampleParamsChange: (String, String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onResizeRequest: (DpSize) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { onResizeRequest(ToolDetailsWindowSize) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxSize()) {
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Tool details",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.glassColors.textPrimary
                        )
                        Text(
                            text = "Настройте описание и примеры инструмента",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = onReset) {
                            Icon(
                                imageVector = Icons.Rounded.Restore,
                                contentDescription = "Reset to default",
                                tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f)
                            )
                        }
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${state.category?.name} / ${state.toolName}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.glassColors.textPrimary,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = state.enabled,
                            onCheckedChange = onToggleEnabled,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f),
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary
                            ),
                        )
                        Text(
                            text = "Инструмент включен",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.glassColors.textPrimary,
                        )
                    }
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.description,
                    onValueChange = onDescriptionChange,
                    label = { Text("Описание", color = MaterialTheme.colorScheme.onBackground) },
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.4f),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        textColor = MaterialTheme.glassColors.textPrimary,
                    ),
                )

                Divider(color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.15f))

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Примеры",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.glassColors.textPrimary,
                        )
                        Button(onClick = onAddExample) {
                            Text(
                                text = "Добавить пример",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.glassColors.textPrimary,
                            )
                        }
                    }

                    if (state.examples.isEmpty()) {
                        Text(
                            text = "Примеры отсутствуют",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f),
                        )
                    } else {
                        state.examples.forEachIndexed { index, example ->
                            ExampleEditor(
                                index = index + 1,
                                example = example,
                                onRequestChange = { onExampleRequestChange(example.id, it) },
                                onParamsChange = { onExampleParamsChange(example.id, it) },
                                onRemove = { onRemoveExample(example.id) },
                            )
                        }
                    }
                }

                if (state.error != null) {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onReset) {
                        Icon(
                            imageVector = Icons.Rounded.Restore,
                            contentDescription = null,
                            tint = MaterialTheme.glassColors.textPrimary,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = "Сбросить",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.glassColors.textPrimary,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onSave, enabled = !state.isSaving) {
                        Icon(
                            imageVector = Icons.Rounded.Save,
                            contentDescription = null,
                            tint = MaterialTheme.glassColors.textPrimary,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = if (state.isSaving) "Сохранение..." else "Сохранить",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.glassColors.textPrimary,
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun ExampleEditor(
    index: Int,
    example: ToolExampleUi,
    onRequestChange: (String) -> Unit,
    onParamsChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Пример $index",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.glassColors.textPrimary,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Удалить пример",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = example.request,
            onValueChange = onRequestChange,
            label = { Text("Request", color = MaterialTheme.colorScheme.onBackground) },
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.4f),
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.glassColors.textPrimary,
            ),
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = example.paramsJson,
            onValueChange = onParamsChange,
            label = { Text("Params (JSON)", color = MaterialTheme.colorScheme.onBackground) },
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.4f),
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.glassColors.textPrimary,
            ),
        )

        if (example.paramsError != null) {
            Text(
                text = example.paramsError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
            )
        }

        Divider(color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.08f))
    }
}

@Preview
@Composable
private fun ToolDetailsScreenPreview() {
    AppTheme {
        ToolDetailsScreen(
            state = ToolDetailsState(
                category = ToolCategory.FILES,
                toolName = "ReadFile",
                description = "Read a file",
                enabled = true,
                examples = listOf(
                    ToolExampleUi("1", "Read file", "{\"path\": \"/tmp/test.txt\"}")
                ),
                defaultExamples = listOf(FewShotExample("Read file", mapOf("path" to "example.txt"))),
            ),
            onDescriptionChange = {},
            onToggleEnabled = {},
            onAddExample = {},
            onRemoveExample = {},
            onExampleRequestChange = { _, _ -> },
            onExampleParamsChange = { _, _ -> },
            onSave = {},
            onReset = {},
            onResizeRequest = {},
            onClose = {},
        )
    }
}
