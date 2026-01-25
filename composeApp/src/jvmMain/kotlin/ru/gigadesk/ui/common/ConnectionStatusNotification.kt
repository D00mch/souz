package ru.gigadesk.ui.common

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.gigadesk.ui.main.RealLiquidGlassCard

private const val NOTIFICATION_DISPLAY_DURATION_MS = 2500L
private val NotificationWarningColor = Color(0xFFFF5252)
private val NotificationCyanColor = Color(0xFF00E5FF)

@Composable
fun ConnectionStatusNotification(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {}
) {
    var isVisible by remember { mutableStateOf(false) }

    // Logic to show notification when offline
    LaunchedEffect(isOnline) {
        if (!isOnline) {
            isVisible = true
            delay(NOTIFICATION_DISPLAY_DURATION_MS)
            isVisible = false
        } else {
            // Immediately hide if back online
            isVisible = false
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .height(40.dp)
        ) {
             // Reuse the glass card style but strictly sized
            RealLiquidGlassCard(
                modifier = Modifier.wrapContentSize(),
                isWindowFocused = true, // Force focused state for visibility
                cornerRadius = 20.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiOff,
                        contentDescription = null,
                        tint = NotificationWarningColor,
                        modifier = Modifier.size(16.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = "No internet connection",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Unobtrusive close button
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable { 
                                isVisible = false
                                onDismiss() 
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}
