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
import com.antigravity.client.domain.model.CodeDiff
import com.antigravity.client.domain.model.Step
import com.antigravity.client.domain.model.ToolCall
import com.antigravity.client.ui.components.CodeDiffViewer
import com.antigravity.client.ui.components.ConnectionBadge
import com.antigravity.client.ui.components.ToolCallCard
import com.antigravity.client.ui.theme.*

sealed class ConversationBubble(val key: String) {
    data class User(
        val stepIndex: Int,
        val text: String
    ) : ConversationBubble("user_$stepIndex")

    data class Agent(
        val stepIndex: Int,
        val thinking: String? = null,
        val toolCallsWithResults: List<Pair<ToolCall, String?>> = emptyList(),
        val diffs: List<CodeDiff> = emptyList(),
        val messageText: String? = null,
        val error: String? = null
    ) : ConversationBubble("agent_$stepIndex")
}

fun processStepsToBubbles(steps: List<Step>): List<ConversationBubble> {
    val bubbles = mutableListOf<ConversationBubble>()
    var currentAgent: ConversationBubble.Agent? = null

    for (step in steps) {
        val content = step.content?.trim() ?: ""

        // Filter out purely internal machine notifications and raw transcript line traces
        if (content.startsWith("<SYSTEM_MESSAGE>") ||
            content.startsWith("[Notice] All your subagents") ||
            (content.startsWith("{\"step_index\":") && content.endsWith("}"))
        ) {
            continue
        }

        // 1. User Message
        if (step.source == "USER_EXPLICIT" || step.type == "USER_INPUT") {
            currentAgent?.let { bubbles.add(it) }
            currentAgent = null

            val userText = step.userPrompt ?: step.content ?: ""
            if (userText.isNotBlank()) {
                bubbles.add(ConversationBubble.User(step.stepIndex, userText))
            }
            continue
        }

        // 2. Tool output / execution result
        val isToolOutput = step.type == "GENERIC" && (
            content.startsWith("Created At:") ||
            content.contains("The command exited with code") ||
            content.startsWith("File Path:") ||
            step.source == "SYSTEM"
        )

        if (isToolOutput) {
            // Attach output directly to the latest tool call in current agent turn
            if (currentAgent != null && currentAgent.toolCallsWithResults.isNotEmpty()) {
                val list = currentAgent.toolCallsWithResults.toMutableList()
                val lastIdx = list.indexOfLast { it.second == null }
                if (lastIdx != -1) {
                    list[lastIdx] = list[lastIdx].first to content
                } else {
                    list[list.lastIndex] = list.last().first to content
                }
                currentAgent = currentAgent.copy(toolCallsWithResults = list)
            }
            continue
        }

        // 3. Agent Turn (PLANNER_RESPONSE or actual assistant text)
        val newTools = step.toolCalls.map { it to (null as String?) }
        val newDiffs = step.diffs
        val newThinking = step.thinking.takeIf { !it.isNullOrBlank() }
        val newError = step.error.takeIf { !it.isNullOrBlank() }
        val newText = content.takeIf { it.isNotBlank() && !isToolOutput }

        if (currentAgent == null) {
            currentAgent = ConversationBubble.Agent(
                stepIndex = step.stepIndex,
                thinking = newThinking,
                toolCallsWithResults = newTools,
                diffs = newDiffs,
                messageText = newText,
                error = newError
            )
        } else {
            val combinedTools = currentAgent.toolCallsWithResults + newTools
            val combinedDiffs = currentAgent.diffs + newDiffs
            val combinedThinking = if (currentAgent.thinking.isNullOrBlank()) newThinking else currentAgent.thinking
            val combinedError = if (currentAgent.error.isNullOrBlank()) newError else currentAgent.error
            val combinedText = when {
                currentAgent.messageText.isNullOrBlank() -> newText
                newText.isNullOrBlank() -> currentAgent.messageText
                else -> "${currentAgent.messageText}\n\n$newText"
            }
            currentAgent = currentAgent.copy(
                thinking = combinedThinking,
                toolCallsWithResults = combinedTools,
                diffs = combinedDiffs,
                messageText = combinedText,
                error = combinedError
            )
        }
    }

    currentAgent?.let { bubbles.add(it) }
    return bubbles
}

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

    val bubbles = remember(steps) { processStepsToBubbles(steps) }

    // Auto-scroll to bottom when new items or live delta arrived
    LaunchedEffect(bubbles.size, activeDeltaText.length) {
        val total = bubbles.size + (if (activeDeltaText.isNotEmpty()) 1 else 0)
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
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
                items(bubbles, key = { it.key }) { bubble ->
                    when (bubble) {
                        is ConversationBubble.User -> UserBubble(bubble)
                        is ConversationBubble.Agent -> AgentBubble(bubble)
                    }
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
private fun UserBubble(bubble: ConversationBubble.User) {
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
                text = bubble.text,
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun AgentBubble(bubble: ConversationBubble.Agent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(12.dp)
    ) {
        // Agent Header
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
                text = "Step #${bubble.stepIndex}",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        // Thinking block if available (collapsed by default)
        if (!bubble.thinking.isNullOrBlank()) {
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
                            text = bubble.thinking,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        // Tool Calls: If 3 or more, wrap in a compact collapsible header
        if (bubble.toolCallsWithResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            if (bubble.toolCallsWithResults.size > 2) {
                var showToolsList by remember { mutableStateOf(false) }
                Surface(
                    color = DarkSurfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showToolsList = !showToolsList }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = PrimaryBlue,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Executed ${bubble.toolCallsWithResults.size} tools (${bubble.toolCallsWithResults.map { it.first.name }.distinct().joinToString(", ")})",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = if (showToolsList) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = showToolsList) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        bubble.toolCallsWithResults.forEach { (tool, result) ->
                            ToolCallCard(toolCall = tool, result = result)
                        }
                    }
                }
            } else {
                bubble.toolCallsWithResults.forEach { (tool, result) ->
                    ToolCallCard(toolCall = tool, result = result)
                }
            }
        }

        // Code Diffs
        bubble.diffs.forEach { diff ->
            Spacer(modifier = Modifier.height(8.dp))
            CodeDiffViewer(diff = diff)
        }

        // Clean Agent Response Text
        if (!bubble.messageText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = bubble.messageText,
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        // Error if any
        if (!bubble.error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = ErrorRed.copy(alpha = 0.2f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Error: ${bubble.error}",
                    color = ErrorRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp)
                )
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
            fontSize = 14.sp,
            lineHeight = 20.sp
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
