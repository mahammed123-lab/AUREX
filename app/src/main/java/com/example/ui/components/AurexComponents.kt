package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.JarvisViewModel
import com.example.ui.JarvisThread
import com.example.ui.JarvisChatMessage
import com.example.ui.JarvisEvent
import com.example.ui.theme.AurexColors
import com.example.ui.theme.AurexMotion

fun Modifier.aurexGlass(
    isDarkMode: Boolean,
    shape: Shape = RoundedCornerShape(20.dp),
    strong: Boolean = false
): Modifier = composed {
    val fill = if (strong) AurexColors.glassFillStrong(isDarkMode) else AurexColors.glassFill(isDarkMode)
    this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    if (isDarkMode) Color.White.copy(alpha = if (strong) 0.10f else 0.065f) else Color.White.copy(alpha = if (strong) 0.92f else 0.78f),
                    fill
                )
            )
        )
        .drawBehind {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDarkMode) 0.10f else 0.55f),
                        Color.Transparent
                    )
                ),
                size = size,
                topLeft = Offset.Zero,
                cornerRadius = CornerRadius(0f)
            )
        }
        .border(
            BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        AurexColors.glassBorder(isDarkMode),
                        Color.White.copy(alpha = if (isDarkMode) 0.05f else 0.30f)
                    )
                )
            ),
            shape
        )
}

fun Modifier.aurexShimmerBorder(
    enabled: Boolean = true,
    cornerRadius: Dp = 20.dp,
    color: Color = AurexColors.Emerald
): Modifier = composed {
    if (!enabled) return@composed this
    val transition = rememberInfiniteTransition(label = "aurexShimmer")
    val progress by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )
    this.border(
        BorderStroke(
            1.dp,
            Brush.linearGradient(
                0f to color.copy(alpha = 0.05f),
                (progress * 0.5f).coerceIn(0f, 1f) to color.copy(alpha = 0.30f),
                progress.coerceIn(0f, 1f) to color.copy(alpha = 0.65f),
                ((progress + 0.5f).coerceAtMost(1f)) to color.copy(alpha = 0.05f)
            )
        ),
        RoundedCornerShape(cornerRadius)
    )
}

@Composable
fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
    hoveredScale: Float = 1.02f
): Float {
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val target = when {
        isPressed -> pressedScale
        isHovered -> hoveredScale
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = AurexMotion.snappySpring(),
        label = "aurexPressScale"
    )
    return scale
}

@Composable
private fun rememberEntrance(delayMs: Int = 0, fromY: Float = 18f): Float {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(
            1f,
            animationSpec = tween(380, delayMillis = delayMs, easing = FastOutSlowInEasing)
        )
    }
    return entrance.value
}

@Composable
fun AurexGlassCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    isDarkMode: Boolean,
    accentColor: Color = AurexColors.Emerald,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource)
    val isPressed by interactionSource.collectIsPressedAsState()
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.35f else 0.12f,
        animationSpec = AurexMotion.normal(),
        label = "cardGlow"
    )
    val entrance = rememberEntrance()

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale * (0.97f + 0.03f * entrance)
                scaleY = scale * (0.97f + 0.03f * entrance)
                alpha = entrance
            }
            .clip(RoundedCornerShape(20.dp))
            .background(AurexColors.glassFill(isDarkMode))
            .drawBehind {
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = glowAlpha), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.15f),
                        radius = size.width
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())
                )
            }
            .border(BorderStroke(1.dp, AurexColors.BorderSheen), RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .animateContentSize(animationSpec = AurexMotion.smoothSpring())
            .padding(14.dp)
    ) {
        content()
    }
}

@Composable
fun JarvisMessagesTab(
    viewModel: JarvisViewModel,
    threads: List<JarvisThread>,
    activeThreadId: String?,
    threadMessages: Map<String, List<JarvisChatMessage>>,
    isDarkMode: Boolean,
    onThreadClick: (String) -> Unit
) {
    if (threads.isEmpty()) {
        val breathe = rememberInfiniteTransition(label = "emptyBreathe")
        val iconScale by breathe.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "iconBreathe"
        )
        val entrance = rememberEntrance()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.graphicsLayer { alpha = entrance }
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(AurexColors.Emerald.copy(alpha = 0.16f), Color.Transparent)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = "No messages",
                        tint = AurexColors.Emerald.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(30.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No messages from JARVIS yet",
                    fontSize = 14.sp,
                    color = AurexColors.textSecondary(isDarkMode),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Autopilot approvals and questions appear here",
                    fontSize = 12.sp,
                    color = AurexColors.textTertiary(isDarkMode)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(threads, key = { it.threadId }) { thread ->
            val isActive = activeThreadId == thread.threadId
            val messages = threadMessages[thread.threadId] ?: emptyList()
            val lastMessage = messages.lastOrNull()
            val unreadCount = messages.count { it.role == "assistant" && !it.isRead }
            val entrance = rememberEntrance()
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val pressScale by animateFloatAsState(
                targetValue = if (pressed) 0.97f else 1f,
                animationSpec = AurexMotion.snappySpring(),
                label = "threadPress"
            )
            val containerColor by animateColorAsState(
                targetValue = when {
                    isActive -> AurexColors.Emerald.copy(alpha = if (isDarkMode) 0.14f else 0.10f)
                    pressed -> Color.White.copy(alpha = if (isDarkMode) 0.06f else 0.5f)
                    else -> Color.White.copy(alpha = if (isDarkMode) 0.035f else 0.75f)
                },
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                label = "threadBg"
            )
            val borderColor by animateColorAsState(
                targetValue = if (isActive) AurexColors.Emerald.copy(alpha = 0.65f) else Color.Transparent,
                animationSpec = tween(240),
                label = "threadBorder"
            )
            val badgeScale by animateFloatAsState(
                targetValue = if (unreadCount > 0) 1f else 0.01f,
                animationSpec = AurexMotion.popSpring(),
                label = "badgePop"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                        alpha = entrance
                        translationY = (1f - entrance) * 16f
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(containerColor)
                    .border(BorderStroke(1.5.dp, borderColor), RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onThreadClick(thread.threadId) }
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(AurexColors.Emerald.copy(alpha = 0.15f))
                                .border(
                                    BorderStroke(1.dp, AurexColors.Emerald.copy(alpha = 0.40f)),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (thread.title.lowercase().firstOrNull()) {
                                    '?' -> Icons.Default.Help
                                    'c' -> Icons.Default.Call
                                    else -> Icons.Default.Chat
                                },
                                contentDescription = thread.title,
                                tint = AurexColors.Emerald,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = thread.title,
                                    fontSize = 14.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = AurexColors.textPrimary(isDarkMode),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (unreadCount > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .graphicsLayer {
                                                scaleX = badgeScale
                                                scaleY = badgeScale
                                            }
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(AurexColors.Emerald, AurexColors.EmeraldDeep)
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0A0A0A),
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = lastMessage?.content ?: "No messages",
                                fontSize = 12.sp,
                                color = AurexColors.textSecondary(isDarkMode),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (lastMessage != null) {
                            Text(
                                text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(lastMessage.timestamp)),
                                fontSize = 10.sp,
                                color = AurexColors.textTertiary(isDarkMode),
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }

                    if (isActive) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(
                            color = if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            messages.forEach { msg ->
                                JarvisMessageBubble(
                                    message = msg,
                                    isDarkMode = isDarkMode,
                                    showAvatar = msg.role == "assistant"
                                )
                            }
                            JarvisMessageCompose(
                                viewModel = viewModel,
                                threadId = thread.threadId,
                                isDarkMode = isDarkMode
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JarvisMessageCompose(
    viewModel: JarvisViewModel,
    threadId: String,
    isDarkMode: Boolean
) {
    var messageText by remember { mutableStateOf("") }
    val isEnabled = messageText.trim().isNotEmpty()
    val sendScale = remember { Animatable(1f) }
    val sendColor by animateColorAsState(
        targetValue = if (isEnabled) AurexColors.Emerald else Color.Gray.copy(alpha = 0.35f),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "sendColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.TextField(
            value = messageText,
            onValueChange = { messageText = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .height(40.dp),
            singleLine = false,
            maxLines = 4,
            placeholder = {
                Text(
                    "Type a message...",
                    fontSize = 14.sp,
                    color = AurexColors.textTertiary(isDarkMode)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.White,
                unfocusedContainerColor = if (isDarkMode) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.8f),
                focusedTextColor = if (isDarkMode) Color.White else Color.Black,
                unfocusedTextColor = if (isDarkMode) Color.White else Color.Black,
                cursorColor = AurexColors.Emerald,
                focusedIndicatorColor = AurexColors.Emerald,
                unfocusedIndicatorColor = if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f),
                disabledContainerColor = if (isDarkMode) Color.White.copy(alpha = 0.02f) else Color.White.copy(alpha = 0.5f),
                disabledTextColor = Color.Gray
            ),
            shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            keyboardActions = KeyboardActions(onDone = {
                if (isEnabled) {
                    viewModel.sendReply(threadId, messageText.trim())
                    messageText = ""
                }
            })
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .graphicsLayer {
                    scaleX = sendScale.value
                    scaleY = sendScale.value
                }
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(sendColor, sendColor.copy(alpha = 0.82f))
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (isEnabled) {
                        viewModel.sendReply(threadId, messageText.trim())
                        messageText = ""
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Send",
                tint = Color(0xFF0A0A0A),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun JarvisMessageBubble(
    message: JarvisChatMessage,
    isDarkMode: Boolean,
    showAvatar: Boolean = true
) {
    val isFromJarvis = message.role == "assistant"
    val entrance = rememberEntrance(fromY = 10f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entrance
                translationY = (1f - entrance) * 10f
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isFromJarvis) Arrangement.Start else Arrangement.End
    ) {
        if (isFromJarvis && showAvatar) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AurexColors.Emerald.copy(alpha = 0.16f))
                    .border(BorderStroke(1.dp, AurexColors.Emerald.copy(alpha = 0.30f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "JARVIS",
                    tint = AurexColors.Emerald,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        } else if (isFromJarvis) {
            Spacer(modifier = Modifier.width(36.dp))
        }

        Column(
            horizontalAlignment = if (isFromJarvis) Alignment.Start else Alignment.End,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        when {
                            isFromJarvis && isDarkMode -> Color.White.copy(alpha = 0.08f)
                            isFromJarvis -> AurexColors.Emerald.copy(alpha = 0.12f)
                            else -> AurexColors.Emerald.copy(alpha = if (isDarkMode) 0.25f else 0.18f)
                        }
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isFromJarvis) Color.White.copy(alpha = if (isDarkMode) 0.10f else 0.0f)
                            else AurexColors.Emerald.copy(alpha = 0.30f)
                        ),
                        RoundedCornerShape(16.dp)
                    )
            ) {
                Text(
                    text = message.content,
                    fontSize = 13.sp,
                    color = when {
                        isFromJarvis && isDarkMode -> Color.White.copy(alpha = 0.9f)
                        isFromJarvis -> Color(0xFF0F172A)
                        else -> Color.White
                    },
                    lineHeight = 20.sp
                )
            }

            if (isFromJarvis && message.kind == "ask") {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    TextButton(
                        onClick = { },
                        colors = ButtonDefaults.textButtonColors(contentColor = AurexColors.Emerald)
                    ) {
                        Text("Approve", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = { },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                    ) {
                        Text("Dismiss", fontWeight = FontWeight.Medium, fontSize = 11.sp)
                    }
                }
            }
        }

        if (!isFromJarvis) {
            Spacer(modifier = Modifier.width(36.dp))
        }
    }
}

@Composable
fun JarvisCallsTab(
    viewModel: JarvisViewModel,
    events: List<JarvisEvent>,
    isDarkMode: Boolean
) {
    val callEvents = events.filter { it.kind == "call" || it.call }

    if (callEvents.isEmpty()) {
        val breathe = rememberInfiniteTransition(label = "callsBreathe")
        val iconScale by breathe.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "callIconBreathe"
        )
        val entrance = rememberEntrance()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.graphicsLayer { alpha = entrance }
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(AurexColors.Blue.copy(alpha = 0.16f), Color.Transparent)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "No calls",
                        tint = AurexColors.Blue.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(30.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No incoming calls from JARVIS",
                    fontSize = 14.sp,
                    color = AurexColors.textSecondary(isDarkMode),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Urgent autopilot approvals will ring here",
                    fontSize = 12.sp,
                    color = AurexColors.textTertiary(isDarkMode)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(callEvents, key = { it.id }) { event ->
            val isIncoming = event.call || event.kind == "call"
            val entrance = rememberEntrance()
            val pulse = rememberInfiniteTransition(label = "callPulse")
            val dotAlpha by pulse.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dotAlpha"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .graphicsLayer {
                        alpha = entrance
                        translationY = (1f - entrance) * 16f
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = if (isDarkMode) 0.05f else 0.85f))
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isIncoming) AurexColors.Red.copy(alpha = 0.5f)
                            else Color.White.copy(alpha = if (isDarkMode) 0.08f else 0.06f)
                        ),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .graphicsLayer { alpha = if (isIncoming) dotAlpha else 1f }
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    if (isIncoming) AurexColors.Red else AurexColors.Emerald
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isIncoming) "Incoming Call" else "Outgoing Call",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AurexColors.textPrimary(isDarkMode)
                                )
                                if (isIncoming) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(AurexColors.Red, Color(0xFFF97316))
                                                )
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "RINGING",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = event.body.ifBlank { event.title },
                                fontSize = 12.sp,
                                color = AurexColors.textSecondary(isDarkMode),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date((event.ts * 1000).toLong())),
                            fontSize = 11.sp,
                            color = AurexColors.textTertiary(isDarkMode)
                        )
                        if (isIncoming) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val answerInteraction = remember { MutableInteractionSource() }
                                val answerPressed by answerInteraction.collectIsPressedAsState()
                                val answerScale by animateFloatAsState(
                                    targetValue = if (answerPressed) 0.92f else 1f,
                                    animationSpec = AurexMotion.snappySpring(),
                                    label = "answerScale"
                                )
                                val declineInteraction = remember { MutableInteractionSource() }
                                val declinePressed by declineInteraction.collectIsPressedAsState()
                                val declineScale by animateFloatAsState(
                                    targetValue = if (declinePressed) 0.92f else 1f,
                                    animationSpec = AurexMotion.snappySpring(),
                                    label = "declineScale"
                                )
                                Box(
                                    modifier = Modifier
                                        .width(70.dp)
                                        .height(32.dp)
                                        .graphicsLayer {
                                            scaleX = answerScale
                                            scaleY = answerScale
                                        }
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(AurexColors.Emerald, AurexColors.EmeraldDeep)
                                            )
                                        )
                                        .clickable(
                                            interactionSource = answerInteraction,
                                            indication = null
                                        ) { },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Answer", fontSize = 11.sp, color = Color(0xFF0A0A0A), fontWeight = FontWeight.SemiBold)
                                }
                                Box(
                                    modifier = Modifier
                                        .width(70.dp)
                                        .height(32.dp)
                                        .graphicsLayer {
                                            scaleX = declineScale
                                            scaleY = declineScale
                                        }
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(AurexColors.Red, Color(0xFFDC2626))
                                            )
                                        )
                                        .clickable(
                                            interactionSource = declineInteraction,
                                            indication = null
                                        ) { },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Decline", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
