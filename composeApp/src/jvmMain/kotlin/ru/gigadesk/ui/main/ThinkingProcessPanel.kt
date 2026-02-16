package ru.gigadesk.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaRequest
import java.text.SimpleDateFormat
import java.util.*
import gigadesk.composeapp.generated.resources.Res
import gigadesk.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun ThinkingProcessPanel(
    history: List<GigaRequest.Message>,
    isProcessing: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Identify the start of the current session (last user request)
    val lastUserIndex = history.indexOfLast { it.role == GigaMessageRole.user }
    val userRequest = if (lastUserIndex >= 0) history[lastUserIndex] else null
    
    // Filter messages after the user request
    val relevantMessages = if (lastUserIndex >= 0 && lastUserIndex < history.size - 1) {
        history.subList(lastUserIndex + 1, history.size)
    } else {
        emptyList()
    }

    // Filter: Assistant messages with content (only text reasoning)
    val assistantMessages = relevantMessages.filter {
        it.role == GigaMessageRole.assistant &&
        it.content.isNotBlank()
    }
    
    // If processing, all assistant messages are reasoning.
    // If done, the last one is the Answer.
    val answerMessage = if (!isProcessing) assistantMessages.lastOrNull() else null
    val reasoningMessages = if (answerMessage != null) {
        assistantMessages.dropLast(1)
    } else {
        assistantMessages
    }

    // Reuse visual style from MainScreen's liquid card but opaque
    val noiseBrush = rememberNoiseBrush()
    
    Box(
        modifier = modifier
            .width(400.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .background(Color.Black) // Solid base
    ) {
        // Opaque background layer with noise
        Canvas(modifier = Modifier.matchParentSize()) {
             drawRect(color = Color(0xFF0F0F0F)) // Deep dark solid background
             drawRect(brush = noiseBrush, alpha = 0.15f) // Subtle noise
             
             // Subtle gradients for "premium" feel
             drawRect(
                 brush = Brush.radialGradient(
                     colors = listOf(Color(0xFF1E1E2E), Color.Transparent),
                     center = Offset(size.width, 0f),
                     radius = size.width
                 ),
                 alpha = 0.3f
             )
        }
        
        // Border
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.White.copy(0.1f), Color.Transparent)
                    ),
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Title
                    Text(
                        text = stringResource(Res.string.thinking_panel_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Close button (MinimalGlassButton style)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(0.05f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(Res.string.action_close),
                        tint = Color.White.copy(0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(0.08f))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // User Request
                if (userRequest != null) {
                    item {
                        ThinkingItem(
                            title = stringResource(Res.string.thinking_user_request),
                            content = userRequest.content,
                            icon = Icons.Rounded.Person,
                            status = ItemStatus.Completed,
                            // Timestamp not available in DTO yet, using mock or current if live
                            timestamp = formatTime(System.currentTimeMillis()) 
                        )
                    }
                }

                // Reasoning
                items(reasoningMessages) { msg ->
                    ThinkingItem(
                        title = stringResource(Res.string.thinking_reasoning),
                        content = msg.content,
                        icon = Icons.Rounded.Psychology,
                        status = ItemStatus.Completed,
                        timestamp = formatTime(System.currentTimeMillis()) 
                    )
                }

                // Answer or Status
                if (answerMessage != null) {
                    item {
                        ThinkingItem(
                            title = stringResource(Res.string.thinking_answer),
                            content = answerMessage.content,
                            icon = Icons.Rounded.ChatBubbleOutline,
                             // Answer is final if we promoted it here
                            status = if (isProcessing) ItemStatus.InProgress else ItemStatus.Completed,
                            timestamp = formatTime(System.currentTimeMillis())
                        )
                    }
                } else if (isProcessing) {
                    item {
                        ThinkingItem(
                            title = stringResource(Res.string.thinking_answer),
                            content = stringResource(Res.string.thinking_generating_answer),
                            icon = Icons.Rounded.ChatBubbleOutline,
                            status = ItemStatus.InProgress,
                            timestamp = stringResource(Res.string.thinking_status_in_progress)
                        )
                    }
                }
            }
        }
    }
}

enum class ItemStatus { Completed, InProgress }

@Composable
fun ThinkingItem(
    title: String,
    content: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    status: ItemStatus,
    timestamp: String
) {
    val containerColor = Color(0xFF1E212B) // Slightly lighter dark
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
             // Timeline line logic could be added here but keeping it simple first
             Column(
                 horizontalAlignment = Alignment.CenterHorizontally,
                 modifier = Modifier.width(32.dp)
             ) {
                 Box(
                     modifier = Modifier
                         .size(32.dp)
                         .clip(RoundedCornerShape(8.dp))
                         .background(Color.White.copy(0.1f)),
                     contentAlignment = Alignment.Center
                 ) {
                     Icon(
                         imageVector = icon,
                         contentDescription = null,
                         tint = Color.White.copy(0.9f),
                         modifier = Modifier.size(16.dp)
                     )
                 }
                 // Vertical line connector could be here
                 Spacer(Modifier.height(4.dp))
                 Box(Modifier.width(1.dp).weight(1f).background(Color.White.copy(0.1f)))
             }
             
             Spacer(Modifier.width(12.dp))
             
             Column(modifier = Modifier.weight(1f)) {
                 Box(
                     modifier = Modifier
                         .fillMaxWidth()
                         .clip(RoundedCornerShape(12.dp))
                         .background(containerColor)
                         .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(12.dp))
                         .padding(12.dp)
                 ) {
                     Column {
                         Row(
                             modifier = Modifier.fillMaxWidth(),
                             horizontalArrangement = Arrangement.SpaceBetween,
                             verticalAlignment = Alignment.CenterVertically
                         ) {
                             Text(
                                 text = title,
                                 style = MaterialTheme.typography.titleSmall,
                                 color = Color.White,
                                 fontWeight = FontWeight.Bold
                             )
                             Text(
                                 text = timestamp,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = Color.White.copy(0.3f),
                                 fontSize = 10.sp
                             )
                         }
                         
                         Spacer(Modifier.height(8.dp))
                         

                         val parts = remember(content) { ru.gigadesk.ui.common.parseMarkdownContent(content) }
                         val codeStyle = MaterialTheme.typography.bodyMedium.copy(
                             fontFamily = FontFamily.Monospace,
                             color = Color(0xFFE0E0E0),
                             fontSize = 12.sp
                         )

                         Column {
                             parts.forEach { part ->
                                 when (part) {
                                     is ru.gigadesk.ui.common.MarkdownPart.TextContent -> {
                                         Text(
                                             text = part.content,
                                             style = MaterialTheme.typography.bodyMedium,
                                             color = Color.White.copy(0.8f),
                                             lineHeight = 18.sp,
                                             overflow = TextOverflow.Ellipsis
                                         )
                                     }
                                     is ru.gigadesk.ui.common.MarkdownPart.CodeContent -> {
                                         ru.gigadesk.ui.common.CodeBlockWithCopy(
                                             code = part.code,
                                             language = part.language,
                                             style = codeStyle
                                         )
                                     }
                                 }
                             }
                         }
                         
                         Spacer(Modifier.height(12.dp))
                         
                         Row(verticalAlignment = Alignment.CenterVertically) {
                             if (status == ItemStatus.Completed) {
                                 Icon(
                                     imageVector = Icons.Rounded.CheckCircleOutline,
                                     contentDescription = null,
                                     tint = Color.Gray,
                                     modifier = Modifier.size(14.dp)
                                 )
                                  Spacer(Modifier.width(4.dp))
                                  Text(
                                     text = stringResource(Res.string.thinking_status_completed),
                                     style = MaterialTheme.typography.bodySmall,
                                     color = Color.Gray,
                                     fontSize = 11.sp
                                 )
                             } else {
                                  CircularProgressIndicator(
                                      modifier = Modifier.size(12.dp),
                                      color = Color.Gray,
                                      strokeWidth = 1.dp
                                  )
                                  Spacer(Modifier.width(4.dp))
                                  Text(
                                     text = stringResource(Res.string.thinking_status_in_progress),
                                     style = MaterialTheme.typography.bodySmall,
                                     color = Color.Gray,
                                     fontSize = 11.sp
                                 )
                             }
                         }
                     }
                 }
                 Spacer(Modifier.height(16.dp))
             }
        }
    }
}

private fun formatTime(millis: Long): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(millis))
}

@Composable
fun rememberNoiseBrush(): androidx.compose.ui.graphics.Brush {
    return remember {
        val size = 256
        val imageBitmap = androidx.compose.ui.graphics.ImageBitmap(size, size)
        val canvas = androidx.compose.ui.graphics.Canvas(imageBitmap)
        val paint = androidx.compose.ui.graphics.Paint().apply {
            color = Color.White
            alpha = 0.2f
        }
        val rnd = Random(System.currentTimeMillis())

        for (x in 0 until size) {
            for (y in 0 until size) {
                if (rnd.nextFloat() > 0.8f) {
                    canvas.drawRect(androidx.compose.ui.geometry.Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f), paint)
                }
            }
        }
        val shader = androidx.compose.ui.graphics.ImageShader(imageBitmap, androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated)
        androidx.compose.ui.graphics.ShaderBrush(shader)
    }
}
