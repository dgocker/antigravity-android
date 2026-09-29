package com.antigravity.client.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.client.domain.model.Step
import com.antigravity.client.ui.components.CodeDiffViewer
import com.antigravity.client.ui.components.ConnectionBadge
import com.antigravity.client.ui.components.RunStatusBadge
import com.antigravity.client.ui.components.ToolCallCard
import com.antigravity.client.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val activeDeltaText by viewModel.activeDeltaText.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val inputMessage by viewModel.inputMessage.collectAsStateWithLifecycle()
    val quotedSnippet by viewModel.quotedSnippet.collectAsStateWithLifecycle()
    val isCancelling by viewModel.isCancelling.collectAsStateWithLifecycle()
    val errorState by viewModel.errorState.collectAsStateWithLifecycle()

    val isRunning = conversation?.status?.contains("RUNNING", ignoreCase = true) == true || activeDeltaText.isNotEmpty()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new steps or live delta arrived
    LaunchedEffect(steps.size, activeDeltaText.length) {
        if (steps.isNotEmpty() || activeDeltaText.isNotEmpty()) {
            val totalItems = steps.size + (if (activeDeltaText.isNotEmpty()) 1 else 0)
            if (totalItems > 0) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = conversation?.title ?: "Chat",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = conversation?.workspace?.substringAfterLast('/') ?: "",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            ConnectionBadge(status = connectionStatus)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    if (isRunning || isCancelling) {
                        Button(
                            onClick = { viewModel.cancelRun() },
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                            enabled = !isCancelling
                        ) {
                            Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isCancelling) "Stopping..." else "Stop", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Error banner
            errorState?.let { err ->
                Surface(
                    color = ErrorRed.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = err, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = ErrorRed)
                        }
                    }
                }
            }

            // Steps & Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(steps, key = { "${it.conversationId}_${it.stepIndex}" }) { step ->
                    StepItem(step = step)
                }

                // Live Streaming Delta Item
                if (activeDeltaText.isNotEmpty()) {
                    item(key = "live_stream_delta") {
                        LiveStreamBubble(text = activeDeltaText)
                    }
                }
            }

            // Composer area
            Composer(
                message = inputMessage,
                onMessageChange = { viewModel.inputMessage.value = it },
                quotedSnippet = quotedSnippet,
                onClearQuote = { viewModel.clearQuote() },
                onSend = { viewModel.sendMessage() },
                isRunning = isRunning,
                onCancel = { viewModel.cancelRun() }
            )
        }
    }
}

@Composable
private fun StepItem(step: Step) {
    if (step.source == "USER_EXPLICIT" || step.type == "USER_INPUT") {
        // User message bubble (Right aligned)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = PrimaryBlue,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    text = step.userPrompt ?: step.content ?: "",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    } else {
        // Agent Step (Left aligned)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.SmartToy,
                        contentDescription = "Agent",
                        tint = PrimaryBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Agent",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Step #${step.stepIndex}",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            // Thinking block if available
            if (!step.thinking.isNullOrBlank()) {
                var showThinking by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = DarkSurfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showThinking = !showThinking },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.Psychology, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Thinking", color = WarningOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Icon(
                                imageVector = if (showThinking) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        AnimatedVisibility(visible = showThinking) {
                            Text(
                                text = step.thinking,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            // Tool Calls
            step.toolCalls.forEach { tool ->
                Spacer(modifier = Modifier.height(8.dp))
                ToolCallCard(toolCall = tool)
            }

            // Code Diffs
            step.diffs.forEach { diff ->
                Spacer(modifier = Modifier.height(8.dp))
                CodeDiffViewer(diff = diff)
            }

            // Step Content
            if (!step.content.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = step.content,
                    color = TextPrimary,
                    fontSize = 14.sp
                )
            }

            // Error if any
            if (!step.error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = ErrorRed.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Error: ${step.error}",
                        color = ErrorRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStreamBubble(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = PrimaryBlue,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Agent is responding live...",
                color = PrimaryBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun Composer(
    message: String,
    onMessageChange: (String) -> Unit,
    quotedSnippet: String?,
    onClearQuote: () -> Unit,
    onSend: () -> Unit,
    isRunning: Boolean,
    onCancel: () -> Unit
) {
    Surface(
        color = DarkSurface,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Quoted code preview
            quotedSnippet?.let { quote ->
                Surface(
                    color = DarkSurfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(AppIcons.FormatQuote, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = quote.take(120).replace('\n', ' '),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearQuote, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove quote", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = onMessageChange,
                    placeholder = { Text("Message agent...", color = TextMuted) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    minLines = 1,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = DarkSurfaceVariant,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground
                    )
                )

                if (isRunning) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(44.dp)
                            .background(ErrorRed, shape = RoundedCornerShape(22.dp))
                    ) {
                        Icon(AppIcons.Stop, contentDescription = "Cancel", tint = TextPrimary)
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = message.isNotBlank(),
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (message.isNotBlank()) PrimaryBlue else DarkSurfaceVariant,
                                shape = RoundedCornerShape(22.dp)
                            )
                    ) {
                        Icon(
                            AppIcons.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (message.isNotBlank()) TextPrimary else TextMuted
                        )
                    }
                }
            }
        }
    }
}
