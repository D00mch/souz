package ru.souz.ui.settings

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.kodein.di.compose.localDI
import ru.souz.ui.AppTheme
import ru.souz.ui.glassColors
import ru.souz.ui.main.RealLiquidGlassCard
import org.jetbrains.compose.resources.stringResource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

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
        onBrowseFolder = { viewModel.send(FoldersManagementEvent.BrowseFolder) },
        onRemoveFolder = { path ->
            viewModel.send(FoldersManagementEvent.RemoveForbiddenFolder(path))
        },
        onClose = { viewModel.send(FoldersManagementEvent.CloseScreen) }
    )
}

@Composable
private fun FoldersManagementScreen(
    state: FoldersManagementState,
    onBrowseFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onClose: () -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused
    val accent = MaterialTheme.colorScheme.primary

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
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.folders_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.95f),
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(
                        onClick = onBrowseFolder,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = accent.copy(alpha = 0.18f),
                            contentColor = accent
                        ),
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(Res.string.button_browse),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.back),
                            tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.85f)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    if (state.forbiddenFolders.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = Color(0x1AFFFFFF),
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = Color(0x2EFFFFFF),
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                    .padding(horizontal = 18.dp, vertical = 22.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.folder_list_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }

                    items(state.forbiddenFolders, key = { it.path }) { item ->
                        ForbiddenFolderCard(
                            item = item,
                            accentColor = accent,
                            onRemove = { onRemoveFolder(item.path) }
                        )
                    }
                }

                Text(
                    text = stringResource(Res.string.hint_folders_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun ForbiddenFolderCard(
    item: ForbiddenFolderItem,
    accentColor: Color,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0x2D1E2531),
                        Color(0x52232D3A)
                    )
                ),
                shape = shape
            )
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = accentColor.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(16.dp)
                )
                .border(
                    width = 1.dp,
                    color = accentColor.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(30.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.95f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.path,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace
            )
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(34.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .background(
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp)
                )
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.tooltip_remove_folder),
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Preview
@Composable
private fun FoldersManagementScreenPreview() {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0E11)) {
            FoldersManagementScreen(
                state = FoldersManagementState(
                    forbiddenFolders = listOf(
                        ForbiddenFolderItem(title = "System", path = "/System"),
                        ForbiddenFolderItem(title = "Applications", path = "/Applications"),
                        ForbiddenFolderItem(title = "Library", path = "/Library"),
                    )
                ),
                onBrowseFolder = {},
                onRemoveFolder = {},
                onClose = {}
            )
        }
    }
}
