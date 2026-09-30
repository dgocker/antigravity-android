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
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.client.domain.model.*
import com.antigravity.client.ui.components.*
import com.antigravity.client.ui.theme.*

sealed class ConversationBubble(val key: String) {
    data class User(
        val id: String,
        val text: String,
        val status: MessageDeliveryStatus = MessageDeliveryStatus.DELIVERED
    ) : ConversationBubble("user_$id")

    data class Agent(
        val stepIndex: Int,
        val thinking: String? = null,
        val toolCallsWithResults: List<Pair<ToolCall, String?>> = emptyList(),
        val diffs: List<CodeDiff> = emptyList(),
        val messageText: String? = null,
        val error: String? = null
    ) : ConversationBubble("agent_$stepIndex")
}

fun cleanTextFromSystemNoise(rawText: String): String {
    var text = rawText
    // Remove "The following is a <SYSTEM_MESSAGE> ... </SYSTEM_MESSAGE>" block
    text = text.replace(Regex("""The following is a <SYSTEM_MESSAGE>[\s\S]*?</SYSTEM_MESSAGE>""", RegexOption.IGNORE_CASE), "")
    // Remove standalone <SYSTEM_MESSAGE> ... </SYSTEM_MESSAGE>
    text = text.replace(Regex("""<SYSTEM_MESSAGE>[\s\S]*?</SYSTEM_MESSAGE>""", RegexOption.IGNORE_CASE), "")
    // Remove header/notice lines
    text = text.replace(Regex("""The following is a <SYSTEM_MESSAGE>[^\n]*""", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("""\[Notice\][^\n]*""", RegexOption.IGNORE_CASE), "")
    return text.trim()
}

fun processStepsToBubbles(steps: List<Step>): List<ConversationBubble> {
    val bubbles = mutableListOf<ConversationBubble>()
    var currentAgent: ConversationBubble.Agent? = null

    for (step in steps) {
        val rawContent = step.content?.trim() ?: ""

        // 1. User Message
        if (step.source == "USER_EXPLICIT" || step.type == "USER_INPUT") {
            currentAgent?.let { bubbles.add(it) }
            currentAgent = null

            val userText = step.userPrompt ?: step.content ?: ""
            if (userText.isNotBlank()) {
                bubbles.add(
                    ConversationBubble.User(
                        id = "${step.conversationId}_${step.stepIndex}",
                        text = userText,
                        status = MessageDeliveryStatus.DELIVERED
                    )
                )
            }
            continue
        }

        // Clean out any embedded or standalone system message noise
        val cleanedContent = cleanTextFromSystemNoise(rawContent)

        // 2. Check if this is a raw tool output / execution result
        val isToolOutput = step.type == "GENERIC" && (
            rawContent.startsWith("Created At:") ||
            rawContent.contains("The command exited with code") ||
            rawContent.startsWith("File Path:") ||
            rawContent.startsWith("{\"step_index\":") ||
            step.source == "SYSTEM"
        )

        if (isToolOutput) {
            // Attach output directly to the latest tool call in current agent turn
            if (currentAgent != null && currentAgent.toolCallsWithResults.isNotEmpty()) {
                val list = currentAgent.toolCallsWithResults.toMutableList()
                val lastIdx = list.indexOfLast { it.second == null }
                if (lastIdx != -1) {
                    list[lastIdx] = list[lastIdx].first to rawContent
                } else {
                    list[list.lastIndex] = list.last().first to rawContent
                }
                currentAgent = currentAgent.copy(toolCallsWithResults = list)
            }
            continue
        }

        // If after cleaning system noise there is no content and no tools/diffs/thinking, skip it
        val newTools = step.toolCalls.map { it to (null as String?) }
        val newDiffs = step.diffs
        val newThinking = step.thinking.takeIf { !it.isNullOrBlank() }
        val newError = step.error.takeIf { !it.isNullOrBlank() }
        val newText = cleanedContent.takeIf { it.isNotBlank() }

        if (newTools.isEmpty() && newDiffs.isEmpty() && newThinking == null && newText == null && newError == null) {
            continue
        }

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
    val pendingMessages by viewModel.pendingMessages.collectAsStateWithLifecycle()
    val activeDeltaText by viewModel.activeDeltaText.collectAsStateWithLifecycle()
    val liveActivity by viewModel.liveActivity.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val inputMessage by viewModel.inputMessage.collectAsStateWithLifecycle()
    val quotedSnippet by viewModel.quotedSnippet.collectAsStateWithLifecycle()
    val isCancelling by viewModel.isCancelling.collectAsStateWithLifecycle()
    val errorState by viewModel.errorState.collectAsStateWithLifecycle()
    val isLoadingHistory by viewModel.isLoadingHistory.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val selectedEffort by viewModel.selectedEffort.collectAsStateWithLifecycle()

    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val lastStep = steps.lastOrNull()
    val isLastStepDone = lastStep?.type == "PLANNER_RESPONSE" && lastStep.status == "DONE"
    val isActivityRunning = liveActivity != null && liveActivity?.activity != "idle"
    val isRunning = isActivityRunning || (!isLastStepDone && (conversation?.status?.contains("RUNNING", ignoreCase = true) == true || activeDeltaText.isNotEmpty()))
    val isLiveTurnVisible = isRunning || activeDeltaText.isNotEmpty() || isActivityRunning
    val listState = rememberLazyListState()

    // Combine confirmed steps with optimistic pending messages (instant appearance!)
    val allBubbles = remember(steps, pendingMessages) {
        val list = processStepsToBubbles(steps).toMutableList()
        for (pending in pendingMessages) {
            list.add(
                ConversationBubble.User(
                    id = pending.id,
                    text = pending.text,
                    status = pending.status
                )
            )
        }
        list
    }

    // Pagination: start with 30 most recent messages to prevent lag and scrolling
    var visibleLimit by remember { mutableIntStateOf(30) }
    val displayedBubbles = remember(allBubbles, visibleLimit) {
        if (allBubbles.size > visibleLimit) {
            allBubbles.takeLast(visibleLimit)
        } else {
            allBubbles
        }
    }

    var isInitialScrollDone by remember { mutableStateOf(false) }

    val hasEarlier = allBubbles.size > visibleLimit
    val totalItems = (if (hasEarlier) 1 else 0) + displayedBubbles.size + (if (isLiveTurnVisible) 1 else 0)

    // Instant jump to bottom on initial load, smart auto-scroll on new incoming messages
    LaunchedEffect(totalItems) {
        if (totalItems > 0) {
            if (!isInitialScrollDone) {
                listState.scrollToItem(totalItems - 1)
                isInitialScrollDone = true
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= totalItems - 4) {
                    listState.animateScrollToItem(totalItems - 1)
                }
            }
        }
    }

    // Scroll to bottom when keyboard opens so composer and last message are fully visible
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && totalItems > 0) {
            listState.scrollToItem(totalItems - 1)
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
                            Spacer(modifier = Modifier.width(8.dp))
                            ModelHeaderChip(
                                selectedModelId = selectedModel,
                                selectedEffort = selectedEffort,
                                availableModels = availableModels,
                                onSelectModel = { viewModel.selectModel(it) },
                                onSelectEffort = { viewModel.selectEffort(it) }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    // Top right STOP button removed per user request
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface),
                windowInsets = TopAppBarDefaults.windowInsets
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
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

            // Top subtle loading indicator if history is loading in background and messages are visible
            if (isLoadingHistory && displayedBubbles.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = PrimaryBlue,
                    trackColor = DarkSurface
                )
            }

            if (displayedBubbles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingHistory) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = PrimaryBlue,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Loading conversation history...",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Send,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No messages yet",
                                color = TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Send a prompt below to start working",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                // Steps & Messages list
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // If there are more earlier messages, show "Load earlier messages" button
                    if (allBubbles.size > visibleLimit) {
                        item(key = "load_earlier_btn") {
                            val remaining = allBubbles.size - visibleLimit
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(
                                    onClick = { visibleLimit += 30 },
                                    colors = ButtonDefaults.textButtonColors(contentColor = PrimaryBlue)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Load earlier messages ($remaining more)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    items(displayedBubbles, key = { it.key }) { bubble ->
                        when (bubble) {
                            is ConversationBubble.User -> UserBubble(bubble)
                            is ConversationBubble.Agent -> AgentBubble(
                                bubble = bubble,
                                viewModel = viewModel,
                                onPreviewImage = { previewImageUrl = it }
                            )
                        }
                    }

                    // Live Streaming Delta / Turn Card Item
                    if (isLiveTurnVisible) {
                        item(key = "live_turn_card") {
                            LiveTurnCard(
                                activity = liveActivity,
                                deltaText = activeDeltaText,
                                onCancel = { viewModel.cancelRun() },
                                onPreviewImage = { previewImageUrl = it },
                                resolveServerUrl = { viewModel.getFileRawUrl(it) }
                            )
                        }
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

    // Full screen image preview dialog
    previewImageUrl?.let { url ->
        ImagePreviewDialog(
            imageUrl = url,
            onDismiss = { previewImageUrl = null },
            onDownload = { viewModel.downloadFile(context, url) }
        )
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
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = bubble.text,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (bubble.status) {
                        MessageDeliveryStatus.SENDING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                color = TextPrimary.copy(alpha = 0.6f),
                                strokeWidth = 1.5.dp
                            )
                        }
                        MessageDeliveryStatus.SENT -> {
                            Text(
                                text = "✓",
                                color = TextPrimary.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        MessageDeliveryStatus.DELIVERED -> {
                            Text(
                                text = "✓✓",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        MessageDeliveryStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Failed",
                                tint = ErrorRed,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Failed to send",
                                color = ErrorRed,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

fun extractAttachmentsFromBubble(bubble: ConversationBubble.Agent): List<FileAttachment> {
    val list = mutableListOf<FileAttachment>()
    val seen = mutableSetOf<String>()

    fun add(path: String?, action: String?) {
        if (path.isNullOrBlank()) return
        val clean = path.removePrefix("file://").trim()
        if (clean in seen || clean.length < 3) return
        seen.add(clean)
        val name = clean.substringAfterLast('/')
        val isImg = name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                    name.endsWith(".jpeg", true) || name.endsWith(".webp", true) ||
                    name.endsWith(".gif", true) || name.endsWith(".svg", true)
        list.add(FileAttachment(name = name, path = clean, isImage = isImg, action = action))
    }

    bubble.toolCallsWithResults.forEach { (tool, _) ->
        when (tool.name) {
            "write_to_file", "replace_file_content" -> {
                add(tool.args["TargetFile"] as? String, "File created / modified")
            }
            "generate_image" -> {
                val img = (tool.args["ImageName"] as? String) ?: "generated_image.png"
                add(img, "Generated Image")
            }
            "view_file" -> {
                val p = tool.args["AbsolutePath"] as? String
                if (p != null && (p.endsWith(".png", true) || p.endsWith(".jpg", true) || p.endsWith(".pdf", true) || p.endsWith(".apk", true))) {
                    add(p, "Viewed File")
                }
            }
        }
    }

    bubble.diffs.forEach { diff ->
        add(diff.file, "Modified in diff")
    }

    bubble.messageText?.let { text ->
        val fileRegex = Regex("""\[(.*?)\]\((file:///[^\s)]+|/[^\s)]+)\)""")
        fileRegex.findAll(text).forEach { m ->
            add(m.groupValues[2], "Referenced file")
        }
    }

    return list
}

@Composable
private fun AgentBubble(
    bubble: ConversationBubble.Agent,
    viewModel: ChatViewModel,
    onPreviewImage: (String) -> Unit
) {
    val context = LocalContext.current
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
                            imageVector = if (showThinking) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
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
                                imageVector = AppIcons.Build,
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
                            imageVector = if (showToolsList) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
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

        // File Attachments (generated images, created files, downloaded files)
        val attachments = remember(bubble) { extractAttachmentsFromBubble(bubble) }
        if (attachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.forEach { file ->
                    FileAttachmentCard(
                        file = file,
                        onDownload = { viewModel.downloadFile(context, file.path, file.name) },
                        onClick = if (file.isImage) { { onPreviewImage(viewModel.getFileRawUrl(file.path)) } } else null
                    )
                }
            }
        }

        // Clean Agent Response Text with rich Markdown, clickable links, code blocks, and photos
        if (!bubble.messageText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            MarkdownText(
                markdown = bubble.messageText,
                textColor = TextPrimary,
                onLinkClick = { url ->
                    if (url.startsWith("file://") || url.startsWith("/")) {
                        viewModel.downloadFile(context, url)
                    } else {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open: $url", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onImageClick = onPreviewImage,
                resolveServerUrl = { viewModel.getFileRawUrl(it) }
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
private fun LiveTurnCard(
    activity: LiveActivity?,
    deltaText: String,
    onCancel: () -> Unit,
    onPreviewImage: (String) -> Unit,
    resolveServerUrl: (String) -> String
) {
    Surface(
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: Status indicator + Stop button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val actType = activity?.activity ?: if (deltaText.isNotEmpty()) "generating" else "thinking"
                    when (actType) {
                        "tool_running" -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = AccentGreen,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = activity?.detail?.ifBlank { "Executing tool..." } ?: "Executing tool...",
                                color = AccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        "generating" -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = PrimaryBlue,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Writing response...",
                                color = PrimaryBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = AppIcons.Psychology,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = activity?.detail?.ifBlank { "Thinking..." } ?: "Thinking...",
                                color = WarningOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
                ) {
                    Icon(AppIcons.Close, contentDescription = "Cancel", modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Actively executing tool parameters
            if (activity != null && activity.activity == "tool_running" && activity.parameters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val cmd = activity.parameters["CommandLine"] as? String
                val path = (activity.parameters["TargetFile"] as? String) ?: (activity.parameters["AbsolutePath"] as? String)
                val displayInfo = cmd ?: path
                if (!displayInfo.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(CodeBg)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = displayInfo,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Live streaming tokens
            if (deltaText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownText(
                    markdown = deltaText,
                    textColor = TextPrimary,
                    onImageClick = onPreviewImage,
                    resolveServerUrl = resolveServerUrl
                )
            }
        }
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

                if (message.isNotBlank()) {
                    IconButton(
                        onClick = onSend,
                        modifier = Modifier
                            .size(44.dp)
                            .background(PrimaryBlue, shape = RoundedCornerShape(22.dp))
                    ) {
                        Icon(
                            AppIcons.ArrowUpward,
                            contentDescription = "Send",
                            tint = TextPrimary
                        )
                    }
                } else if (isRunning) {
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
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .size(44.dp)
                            .background(DarkSurfaceVariant, shape = RoundedCornerShape(22.dp))
                    ) {
                        Icon(
                            AppIcons.ArrowUpward,
                            contentDescription = "Send",
                            tint = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHeaderChip(
    selectedModelId: String,
    selectedEffort: String,
    availableModels: List<ModelOption>,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    val currentModel = availableModels.find { it.id == selectedModelId }
    val displayName = currentModel?.name?.substringBefore(" (")
        ?: selectedModelId.removePrefix("gemini-").removePrefix("claude-")

    val effortLabel = when (selectedEffort.lowercase()) {
        "high" -> "High"
        "medium" -> "Med"
        "low" -> "Low"
        else -> ""
    }

    val chipText = if (effortLabel.isNotEmpty()) "$displayName • $effortLabel" else displayName

    Surface(
        color = DarkSurfaceVariant.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable { showSheet = true }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = chipText,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(3.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Select Model & Reasoning",
                tint = TextMuted,
                modifier = Modifier.size(14.dp)
            )
        }
    }

    if (showSheet) {
        ModelSelectionBottomSheet(
            selectedModelId = selectedModelId,
            selectedEffort = selectedEffort,
            availableModels = availableModels,
            onDismiss = { showSheet = false },
            onSelectModel = onSelectModel,
            onSelectEffort = onSelectEffort
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionBottomSheet(
    selectedModelId: String,
    selectedEffort: String,
    availableModels: List<ModelOption>,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = TextMuted)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Модель Antigravity",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
            ) {
                items(availableModels, key = { it.id }) { model ->
                    val isSelected = model.id == selectedModelId
                    Surface(
                        color = if (isSelected) DarkSurfaceVariant else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectModel(model.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.name,
                                    color = if (isSelected) PrimaryBlue else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                if (!model.reasoningLevel.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = model.reasoningLevel,
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                color = DarkSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            Text(
                text = "Способности к размышлениям",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val effortOptions = listOf(
                Triple("high", "Высокие (High)", "Решение сложных проблем, глубокий анализ кода"),
                Triple("medium", "Средние (Medium)", "Сбалансированное рассуждение"),
                Triple("low", "Низкие (Low)", "Быстрые ответы"),
                Triple("off", "Выключено (Off)", "Без предварительных размышлений")
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                effortOptions.forEach { (effortKey, effortTitle, effortDesc) ->
                    val isEffortSelected = effortKey.equals(selectedEffort, ignoreCase = true)
                    Surface(
                        color = if (isEffortSelected) DarkSurfaceVariant else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectEffort(effortKey) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isEffortSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = effortTitle,
                                    color = if (isEffortSelected) PrimaryBlue else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isEffortSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = effortDesc,
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
