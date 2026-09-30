package com.antigravity.client.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.client.data.remote.dto.ArtifactDto
import com.antigravity.client.data.remote.dto.SlashCommandDto
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import com.antigravity.client.audio.AudioPlayer
import com.antigravity.client.domain.model.*
import com.antigravity.client.ui.components.*
import com.antigravity.client.ui.theme.*
import kotlinx.coroutines.launch

sealed class ConversationBubble(val key: String) {
    data class User(
        val id: String,
        val text: String,
        val attachments: List<Attachment> = emptyList(),
        val status: MessageDeliveryStatus = MessageDeliveryStatus.DELIVERED
    ) : ConversationBubble("user_$id")

    data class Agent(
        val stepIndex: Int,
        val conversationId: String = "",
        val thinking: String? = null,
        val toolCallsWithResults: List<Pair<ToolCall, String?>> = emptyList(),
        val diffs: List<CodeDiff> = emptyList(),
        val attachments: List<Attachment> = emptyList(),
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

private val VOICE_TAG_RE = Regex("""\[Голосовое сообщение:\s*([^ ]+)\s*\(([^,\)]+)(?:,\s*(\d+)s)?\)\](?:\s*\nРасшифровка аудио:\s*"([^"]*)")?""", RegexOption.IGNORE_CASE)
private val IMAGE_TAG_RE = Regex("""\[Изображение:\s*([^ ]+)\s*\(([^,]+),\s*(\d+)\s*KB\)\]""", RegexOption.IGNORE_CASE)
private val VIDEO_TAG_RE = Regex("""\[Видео:\s*([^ ]+)\s*\(([^,]+),\s*(\d+)\s*KB\)\]""", RegexOption.IGNORE_CASE)
private val FILE_TAG_RE = Regex("""\[Вложение:\s*([^ ]+)\s*\(([^,]+),\s*(\d+)\s*KB\)\]""", RegexOption.IGNORE_CASE)

fun extractAttachmentsFromRawText(rawText: String?): List<Attachment> {
    if (rawText.isNullOrBlank()) return emptyList()
    val list = mutableListOf<Attachment>()
    VOICE_TAG_RE.findAll(rawText).forEach { m ->
        val path = m.groupValues[1].trim()
        val mime = m.groupValues[2].trim()
        val dur = m.groupValues[3].toIntOrNull()
        val trans = m.groupValues[4].takeIf { it.isNotBlank() }
        list.add(
            Attachment(
                id = path,
                type = AttachmentType.AUDIO,
                fileName = path.substringAfterLast('/'),
                mimeType = mime,
                size = 0L,
                duration = dur,
                remoteUrl = path,
                serverId = path,
                transcription = trans,
                uploadState = AttachmentUploadState.COMPLETED
            )
        )
    }
    IMAGE_TAG_RE.findAll(rawText).forEach { m ->
        val path = m.groupValues[1].trim()
        val mime = m.groupValues[2].trim()
        val sz = (m.groupValues[3].toLongOrNull() ?: 0L) * 1024L
        list.add(
            Attachment(
                id = path,
                type = AttachmentType.IMAGE,
                fileName = path.substringAfterLast('/'),
                mimeType = mime,
                size = sz,
                remoteUrl = path,
                serverId = path,
                uploadState = AttachmentUploadState.COMPLETED
            )
        )
    }
    VIDEO_TAG_RE.findAll(rawText).forEach { m ->
        val path = m.groupValues[1].trim()
        val mime = m.groupValues[2].trim()
        val sz = (m.groupValues[3].toLongOrNull() ?: 0L) * 1024L
        list.add(
            Attachment(
                id = path,
                type = AttachmentType.VIDEO,
                fileName = path.substringAfterLast('/'),
                mimeType = mime,
                size = sz,
                remoteUrl = path,
                serverId = path,
                uploadState = AttachmentUploadState.COMPLETED
            )
        )
    }
    FILE_TAG_RE.findAll(rawText).forEach { m ->
        val name = m.groupValues[1].trim()
        val path = m.groupValues[2].trim()
        val sz = (m.groupValues[3].toLongOrNull() ?: 0L) * 1024L
        list.add(
            Attachment(
                id = path,
                type = AttachmentType.DOCUMENT,
                fileName = name,
                mimeType = "application/octet-stream",
                size = sz,
                remoteUrl = path,
                serverId = path,
                uploadState = AttachmentUploadState.COMPLETED
            )
        )
    }
    return list
}

fun cleanUserPromptText(rawText: String?): String {
    if (rawText.isNullOrBlank()) return ""
    var text = rawText
    // Extract <USER_REQUEST> ... </USER_REQUEST> if wrapped
    val userReqMatch = Regex("""<USER_REQUEST>([\s\S]*?)</USER_REQUEST>""", RegexOption.IGNORE_CASE).find(text)
    if (userReqMatch != null) {
        text = userReqMatch.groupValues[1].trim()
    }
    // Remove <ADDITIONAL_METADATA> ... </ADDITIONAL_METADATA>
    text = text.replace(Regex("""<ADDITIONAL_METADATA>[\s\S]*?</ADDITIONAL_METADATA>""", RegexOption.IGNORE_CASE), "")
    // Remove attachment tags
    text = text.replace(Regex("""\[Голосовое сообщение:[^\]]+\]""", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("""Расшифровка аудио:\s*"[^"]*"""", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("""\[Изображение:[^\]]+\]""", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("""\[Видео:[^\]]+\]""", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("""\[Вложение:[^\]]+\]""", RegexOption.IGNORE_CASE), "")
    // Remove residual XML tags
    text = text.replace(Regex("""</?USER_REQUEST>""", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("""</?ADDITIONAL_METADATA>""", RegexOption.IGNORE_CASE), "")
    return text.trim()
}

fun processStepsToBubbles(
    steps: List<Step>,
    pendingMessages: List<PendingUserMessage> = emptyList(),
    liveTranscriptions: Map<String, String> = emptyMap()
): List<ConversationBubble> {
    val bubbles = mutableListOf<ConversationBubble>()
    var currentAgent: ConversationBubble.Agent? = null

    fun resolveTranscription(att: Attachment): Attachment {
        if (att.type == AttachmentType.AUDIO && att.transcription.isNullOrBlank()) {
            val trans = liveTranscriptions[att.serverId]
                ?: liveTranscriptions[att.remoteUrl]
                ?: liveTranscriptions[att.id]
                ?: (if (att.remoteUrl != null) liveTranscriptions[att.remoteUrl.substringAfterLast('/')] else null)
                ?: liveTranscriptions[att.fileName]
            if (!trans.isNullOrBlank()) return att.copy(transcription = trans)
        }
        return att
    }

    fun linkAgentTranscriptionToUserBubble(agent: ConversationBubble.Agent) {
        val agentText = agent.messageText ?: return
        val transMatch = Regex("""Расшифровка(?: аудио)?:\s*(?:>|\n\s*>|\n)*\s*[«"']?(.*?)[»"']?(?:\n|\r|\(|$)""", RegexOption.IGNORE_CASE).find(agentText)
        val extractedTrans = transMatch?.groupValues?.get(1)?.trim()
        if (!extractedTrans.isNullOrBlank()) {
            val lastUserIdx = bubbles.indexOfLast {
                it is ConversationBubble.User && it.attachments.any { a -> a.type == AttachmentType.AUDIO && a.transcription.isNullOrBlank() }
            }
            if (lastUserIdx != -1) {
                val userBubble = bubbles[lastUserIdx] as ConversationBubble.User
                val updatedAttachments = userBubble.attachments.map { a ->
                    if (a.type == AttachmentType.AUDIO && a.transcription.isNullOrBlank()) {
                        a.copy(transcription = extractedTrans)
                    } else {
                        a
                    }
                }
                bubbles[lastUserIdx] = userBubble.copy(attachments = updatedAttachments)
            }
        }
    }

    for (step in steps) {
        val rawContent = step.content?.trim() ?: ""

        // 1. User Message
        if (step.source == "USER_EXPLICIT" || step.type == "USER_INPUT") {
            currentAgent?.let {
                linkAgentTranscriptionToUserBubble(it)
                bubbles.add(it)
            }
            currentAgent = null

            val rawUserText = step.userPrompt ?: step.content ?: ""
            val userText = cleanUserPromptText(rawUserText)
            val parsedAttachments = if (step.attachments.isNotEmpty()) {
                step.attachments
            } else {
                extractAttachmentsFromRawText(rawUserText)
            }

            if (userText.isNotBlank() || parsedAttachments.isNotEmpty()) {
                val matchedPending = pendingMessages.find { pending ->
                    if (pending.attachments.isNotEmpty() && parsedAttachments.isNotEmpty()) {
                        parsedAttachments.any { sa ->
                            pending.attachments.any { pa ->
                                sa.id == pa.id ||
                                (pa.serverId != null && (pa.serverId == sa.id || pa.serverId == sa.remoteUrl)) ||
                                (sa.remoteUrl != null && (sa.remoteUrl == pa.remoteUrl || sa.remoteUrl == pa.localUri)) ||
                                (sa.fileName == pa.fileName && (pa.size == 0L || sa.size == 0L || sa.size == pa.size))
                            }
                        }
                    } else if (userText.isNotBlank() && pending.text.isNotBlank()) {
                        cleanUserPromptText(pending.text) == userText
                    } else {
                        false
                    }
                }

                val bubbleId = matchedPending?.id ?: "${step.conversationId}_${step.stepIndex}"
                val rawAtts = if (parsedAttachments.isNotEmpty()) parsedAttachments else (matchedPending?.attachments ?: emptyList())
                val finalAttachments = rawAtts.map { resolveTranscription(it) }

                bubbles.add(
                    ConversationBubble.User(
                        id = bubbleId,
                        text = userText,
                        attachments = finalAttachments,
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
        val newAttachments = step.attachments
        val newThinking = step.thinking.takeIf { !it.isNullOrBlank() }
        val newError = step.error.takeIf { !it.isNullOrBlank() }
        val newText = cleanedContent.takeIf { it.isNotBlank() }

        if (newTools.isEmpty() && newDiffs.isEmpty() && newThinking == null && newText == null && newError == null && newAttachments.isEmpty()) {
            continue
        }

        if (currentAgent == null) {
            currentAgent = ConversationBubble.Agent(
                stepIndex = step.stepIndex,
                conversationId = step.conversationId,
                thinking = newThinking,
                toolCallsWithResults = newTools,
                diffs = newDiffs,
                attachments = newAttachments,
                messageText = newText,
                error = newError
            )
        } else {
            val combinedTools = currentAgent.toolCallsWithResults + newTools
            val combinedDiffs = currentAgent.diffs + newDiffs
            val combinedAttachments = currentAgent.attachments + newAttachments
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
                attachments = combinedAttachments,
                messageText = combinedText,
                error = combinedError
            )
        }
    }

    currentAgent?.let {
        linkAgentTranscriptionToUserBubble(it)
        bubbles.add(it)
    }
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
    val liveTranscriptions by viewModel.liveTranscriptions.collectAsStateWithLifecycle()
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
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val slashCommands by viewModel.slashCommands.collectAsStateWithLifecycle()
    val artifacts by viewModel.artifacts.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val isLoadingArtifacts by viewModel.isLoadingArtifacts.collectAsStateWithLifecycle()
    val isLoadingTasks by viewModel.isLoadingTasks.collectAsStateWithLifecycle()

    val showModelSheet by viewModel.showModelSheet.collectAsStateWithLifecycle()
    val showTasksSheet by viewModel.showTasksSheet.collectAsStateWithLifecycle()
    val showArtifactsSheet by viewModel.showArtifactsSheet.collectAsStateWithLifecycle()

    val showEffortSheet by viewModel.showEffortSheet.collectAsStateWithLifecycle()
    val showDiffSheet by viewModel.showDiffSheet.collectAsStateWithLifecycle()
    val diffData by viewModel.diffData.collectAsStateWithLifecycle()
    val isDiffLoading by viewModel.isDiffLoading.collectAsStateWithLifecycle()

    val showRenameDialog by viewModel.showRenameDialog.collectAsStateWithLifecycle()
    val showContextSheet by viewModel.showContextSheet.collectAsStateWithLifecycle()
    val contextData by viewModel.contextData.collectAsStateWithLifecycle()
    val isContextLoading by viewModel.isContextLoading.collectAsStateWithLifecycle()

    val showAgentsSheet by viewModel.showAgentsSheet.collectAsStateWithLifecycle()
    val agentsList by viewModel.agentsList.collectAsStateWithLifecycle()
    val isAgentsLoading by viewModel.isAgentsLoading.collectAsStateWithLifecycle()

    val showSkillsSheet by viewModel.showSkillsSheet.collectAsStateWithLifecycle()
    val skillsList by viewModel.skillsList.collectAsStateWithLifecycle()
    val isSkillsLoading by viewModel.isSkillsLoading.collectAsStateWithLifecycle()

    val showCommandsCatalogSheet by viewModel.showCommandsCatalogSheet.collectAsStateWithLifecycle()
    val activeWorkflowDialog by viewModel.activeWorkflowDialog.collectAsStateWithLifecycle()
    val showClearConfirmDialog by viewModel.showClearConfirmDialog.collectAsStateWithLifecycle()
    val showForkConfirmDialog by viewModel.showForkConfirmDialog.collectAsStateWithLifecycle()
    val showBtwDialog by viewModel.showBtwDialog.collectAsStateWithLifecycle()
    val btwInitialQuery by viewModel.btwInitialQuery.collectAsStateWithLifecycle()

    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    var previewArtifact by remember { mutableStateOf<ArtifactDto?>(null) }
    var previewArtifactContent by remember { mutableStateOf("") }
    var isLoadingArtifactContent by remember { mutableStateOf(false) }

    // Auto-refresh chat history when returning to foreground (e.g. from Termius/terminal)
    LifecycleResumeEffect(Unit) {
        viewModel.refreshChat()
        onPauseOrDispose { }
    }

    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var previewImageName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(toastMessage) {
        toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.toastMessage.value = null
        }
    }

    // Attachment pickers
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraTempUri?.let { uri ->
                viewModel.attachFromUri(uri)
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val photoFile = File(context.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile
                )
                cameraTempUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось открыть камеру: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Требуется разрешение на доступ к камере", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                val photoFile = File(context.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile
                )
                cameraTempUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось открыть камеру: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            viewModel.attachFromUri(uri)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            viewModel.attachFromUri(uri)
        }
    }

    val lastStep = steps.lastOrNull()
    val isActivityRunning = liveActivity != null && liveActivity?.activity != "idle"
    val isSending = pendingMessages.any { it.status == MessageDeliveryStatus.SENDING }
    val isRunning = isActivityRunning || activeDeltaText.isNotEmpty() || isSending
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Combine confirmed steps with optimistic pending messages (instant appearance with stable keys!)
    val allBubbles = remember(steps, pendingMessages, liveTranscriptions) {
        val list = processStepsToBubbles(steps, pendingMessages, liveTranscriptions).toMutableList()
        val processedIds = list.filterIsInstance<ConversationBubble.User>().map { it.id }.toSet()
        for (pending in pendingMessages) {
            if (pending.id !in processedIds) {
                val userText = cleanUserPromptText(pending.text)
                val resolvedPendingAttachments = pending.attachments.map { att ->
                    if (att.type == AttachmentType.AUDIO && att.transcription.isNullOrBlank()) {
                        val trans = liveTranscriptions[att.serverId]
                            ?: liveTranscriptions[att.remoteUrl]
                            ?: liveTranscriptions[att.id]
                            ?: (if (att.remoteUrl != null) liveTranscriptions[att.remoteUrl.substringAfterLast('/')] else null)
                            ?: liveTranscriptions[att.fileName]
                        if (!trans.isNullOrBlank()) att.copy(transcription = trans) else att
                    } else {
                        att
                    }
                }
                if (userText.isNotBlank() || resolvedPendingAttachments.isNotEmpty()) {
                    list.add(
                        ConversationBubble.User(
                            id = pending.id,
                            text = userText,
                            attachments = resolvedPendingAttachments,
                            status = pending.status
                        )
                    )
                }
            }
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

    val lastAgentBubble = displayedBubbles.lastOrNull() as? ConversationBubble.Agent
    val hasFinalizedAgentText = !lastAgentBubble?.messageText.isNullOrBlank()

    // Smooth streaming: preserve delta text until the finalized bubble actually renders its text
    var preservedDeltaText by remember(conversation?.id) { mutableStateOf("") }
    if (activeDeltaText.isNotEmpty()) {
        preservedDeltaText = activeDeltaText
    } else if (hasFinalizedAgentText) {
        preservedDeltaText = ""
    }

    val displayDeltaText = if (hasFinalizedAgentText) "" else (if (activeDeltaText.isNotEmpty()) activeDeltaText else preservedDeltaText)
    val isLiveTurnVisible = (liveActivity != null && liveActivity?.activity != "idle") || displayDeltaText.isNotEmpty()

    var isInitialScrollDone by remember { mutableStateOf(false) }

    val hasEarlier = allBubbles.size > visibleLimit
    val totalItems = (if (hasEarlier) 1 else 0) + displayedBubbles.size + (if (isLiveTurnVisible) 1 else 0)
    var previousTotalItems by remember { mutableIntStateOf(totalItems) }

    val lastBubble = displayedBubbles.lastOrNull()
    val bottomContentKey = remember(
        totalItems,
        lastBubble,
        liveActivity?.activity,
        liveActivity?.detail,
        liveActivity?.parameters?.size,
        displayDeltaText.length / 30
    ) {
        when (lastBubble) {
            is ConversationBubble.Agent -> {
                "${totalItems}_${lastBubble.stepIndex}_${lastBubble.diffs.size}_${lastBubble.toolCallsWithResults.size}_${lastBubble.messageText?.length ?: 0}_${liveActivity?.activity}_${liveActivity?.detail}_${displayDeltaText.length / 30}"
            }
            is ConversationBubble.User -> {
                "${totalItems}_${lastBubble.id}_${lastBubble.status}_${liveActivity?.activity}_${displayDeltaText.length / 30}"
            }
            null -> "$totalItems"
        }
    }

    // Instant jump to bottom on initial load, auto-scroll when new items arrive or live card expands
    LaunchedEffect(bottomContentKey) {
        if (totalItems > 0) {
            if (!isInitialScrollDone) {
                listState.scrollToItem(totalItems - 1)
                isInitialScrollDone = true
                previousTotalItems = totalItems
                return@LaunchedEffect
            }

            if (listState.isScrollInProgress) return@LaunchedEffect

            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@LaunchedEffect

            val lastVisibleIndex = visibleItems.last().index
            val totalCount = layoutInfo.totalItemsCount

            val isNewItemAdded = totalItems > previousTotalItems
            previousTotalItems = totalItems

            // Check if user was looking at the bottom area (within 2 items of bottom)
            val isUserAtBottom = lastVisibleIndex >= totalCount - 2

            if (isUserAtBottom) {
                if (isNewItemAdded) {
                    listState.animateScrollToItem(totalCount - 1)
                }

                // If live action / live tool / streaming card is running at the bottom,
                // auto-follow its expansion so it stays visible above the composer.
                // Never scroll past the top of the bubble when finalizing.
                if (isLiveTurnVisible) {
                    kotlinx.coroutines.delay(35)
                    val updatedLayout = listState.layoutInfo
                    val updatedLast = updatedLayout.visibleItemsInfo.lastOrNull()
                    if (updatedLast != null && updatedLast.index == totalCount - 1) {
                        val overflow = (updatedLast.offset + updatedLast.size) - updatedLayout.viewportEndOffset
                        if (overflow > 0) {
                            val maxAllowedScroll = if (updatedLast.offset > 0) updatedLast.offset.toFloat() else overflow.toFloat() + 24f
                            val scrollAmount = minOf(overflow.toFloat() + 24f, maxAllowedScroll)
                            if (scrollAmount > 0f) {
                                listState.scrollBy(scrollAmount)
                            }
                        }
                    }
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
                    Column(
                        modifier = Modifier.clickable { viewModel.showRenameDialog.value = true }
                    ) {
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
                    IconButton(onClick = { viewModel.showCommandsCatalogSheet.value = true }) {
                        Icon(
                            imageVector = AppIcons.Terminal,
                            contentDescription = "Слэш-команды (/help)",
                            tint = PrimaryBlue
                        )
                    }
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
                            is ConversationBubble.User -> UserBubble(
                                bubble = bubble,
                                audioPlayer = viewModel.audioPlayer,
                                resolveServerUrl = { viewModel.getFileRawUrl(it) },
                                onPreviewImage = { url, name ->
                                    previewImageUrl = url
                                    previewImageName = name
                                },
                                onRetry = { viewModel.retryPendingMessage(bubble.id) }
                            )
                            is ConversationBubble.Agent -> AgentBubble(
                                bubble = bubble,
                                viewModel = viewModel,
                                onPreviewImage = { url, name ->
                                    previewImageUrl = url
                                    previewImageName = name
                                }
                            )
                        }
                    }

                    // Live Streaming Delta / Turn Card Item
                    if (isLiveTurnVisible) {
                        item(key = "live_turn_card") {
                            LiveTurnCard(
                                activity = liveActivity,
                                deltaText = displayDeltaText,
                                onCancel = { viewModel.cancelRun() },
                                onPreviewImage = { previewImageUrl = it; previewImageName = null },
                                resolveServerUrl = { viewModel.getFileRawUrl(it) },
                                onAnswerQuestion = { answer ->
                                    viewModel.sendQuestionAnswer(answer)
                                }
                            )
                        }
                    }
                }
            }

            // Active Agents & Artifacts indicator bar (matching Antigravity CLI footer)
            val activeAgentsCount = tasks.subagents.count { it.state.contains("alive", ignoreCase = true) || it.state.contains("run", ignoreCase = true) }
            val totalAgentsCount = tasks.subagents.size
            if (totalAgentsCount > 0 || artifacts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (totalAgentsCount > 0) {
                        Surface(
                            color = DarkSurfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable {
                                viewModel.loadTasks()
                                viewModel.showTasksSheet.value = true
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (activeAgentsCount > 0) AccentGreen else TextMuted)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (activeAgentsCount > 0) "$activeAgentsCount активных агентов" else "$totalAgentsCount агентов",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "/tasks",
                                    color = PrimaryBlue,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    if (artifacts.isNotEmpty()) {
                        Surface(
                            color = DarkSurfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable {
                                viewModel.loadArtifacts()
                                viewModel.showArtifactsSheet.value = true
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${artifacts.size} артефактов",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "/artifact",
                                    color = PrimaryBlue,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // Telegram Composer area
            TelegramComposer(
                text = inputMessage,
                onTextChange = { viewModel.inputMessage.value = it },
                attachments = pendingAttachments,
                onRemoveAttachment = { viewModel.removePendingAttachment(it) },
                onSend = {
                    val trimmed = inputMessage.trim()
                    if (!viewModel.handleSlashCommand(trimmed)) {
                        viewModel.sendMessage()
                    }
                },
                onVoiceRecorded = { viewModel.attachVoiceNote(it) },
                onPickCamera = { launchCamera() },
                onPickGallery = { galleryLauncher.launch("image/*") },
                onPickFile = { fileLauncher.launch(arrayOf("*/*")) },
                isRunning = isRunning,
                onCancelRun = { viewModel.cancelRun() },
                slashCommands = slashCommands,
                onSlashCommandSelect = { cmd ->
                    viewModel.handleSlashCommand("/${cmd.name}")
                }
            )
        }
    }

    // Full screen image preview dialog
    previewImageUrl?.let { url ->
        ImagePreviewDialog(
            imageUrl = url,
            onDismiss = {
                previewImageUrl = null
                previewImageName = null
            },
            onDownload = { viewModel.downloadFile(context, url, previewImageName) }
        )
    }

    if (showModelSheet) {
        ModelSelectionBottomSheet(
            selectedModelId = selectedModel,
            selectedEffort = selectedEffort,
            availableModels = availableModels,
            onDismiss = { viewModel.showModelSheet.value = false },
            onSelectModel = {
                viewModel.selectModel(it)
                viewModel.showModelSheet.value = false
            },
            onSelectEffort = {
                viewModel.selectEffort(it)
                viewModel.showModelSheet.value = false
            }
        )
    }

    if (showTasksSheet) {
        TasksBottomSheet(
            tasks = tasks,
            isLoading = isLoadingTasks,
            onRefresh = { viewModel.loadTasks() },
            onKillTask = { viewModel.killTask(it) },
            onDismiss = { viewModel.showTasksSheet.value = false }
        )
    }

    if (showArtifactsSheet) {
        ArtifactsBottomSheet(
            artifacts = artifacts,
            isLoading = isLoadingArtifacts,
            onRefresh = { viewModel.loadArtifacts() },
            onSelectArtifact = { art ->
                previewArtifact = art
                isLoadingArtifactContent = true
                coroutineScope.launch {
                    previewArtifactContent = viewModel.getFileContent(art.path)
                    isLoadingArtifactContent = false
                }
            },
            onDownloadArtifact = { art ->
                viewModel.downloadFile(context, art.path, art.fileName)
            },
            onDismiss = { viewModel.showArtifactsSheet.value = false }
        )
    }

    if (showEffortSheet) {
        EffortBottomSheet(
            selectedEffort = selectedEffort,
            onSelectEffort = { effort ->
                viewModel.selectEffort(effort)
                viewModel.showEffortSheet.value = false
            },
            onDismiss = { viewModel.showEffortSheet.value = false }
        )
    }

    if (showDiffSheet) {
        DiffBottomSheet(
            diffData = diffData,
            isLoading = isDiffLoading,
            onRefresh = { viewModel.loadDiff() },
            onDismiss = { viewModel.showDiffSheet.value = false }
        )
    }

    if (showRenameDialog) {
        RenameChatDialog(
            initialTitle = conversation?.title ?: "",
            onConfirm = { newTitle ->
                viewModel.renameChat(newTitle)
                viewModel.showRenameDialog.value = false
            },
            onDismiss = { viewModel.showRenameDialog.value = false }
        )
    }

    if (showContextSheet) {
        ContextUsageBottomSheet(
            contextData = contextData,
            isLoading = isContextLoading,
            onRefresh = { viewModel.loadContext() },
            onDismiss = { viewModel.showContextSheet.value = false }
        )
    }

    if (showAgentsSheet) {
        GenericListBottomSheet(
            title = "Доступные агенты (/agents)",
            items = agentsList,
            isLoading = isAgentsLoading,
            onDismiss = { viewModel.showAgentsSheet.value = false }
        )
    }

    if (showSkillsSheet) {
        GenericListBottomSheet(
            title = "Установленные скиллы (/skills)",
            items = skillsList,
            isLoading = isSkillsLoading,
            onDismiss = { viewModel.showSkillsSheet.value = false }
        )
    }

    if (showCommandsCatalogSheet) {
        CommandsCatalogBottomSheet(
            commands = slashCommands,
            onSelectCommand = { cmd ->
                viewModel.showCommandsCatalogSheet.value = false
                viewModel.handleSlashCommand("/${cmd.name}")
            },
            onDismiss = { viewModel.showCommandsCatalogSheet.value = false }
        )
    }

    activeWorkflowDialog?.let { dialogData ->
        WorkflowPromptDialog(
            data = dialogData,
            onConfirm = { prompt ->
                viewModel.activeWorkflowDialog.value = null
                viewModel.launchWorkflow(dialogData.commandName, prompt)
            },
            onDismiss = { viewModel.activeWorkflowDialog.value = null }
        )
    }

    if (showClearConfirmDialog) {
        ConfirmationDialog(
            title = "Очистить чат (/clear)",
            message = "Вы уверены, что хотите сбросить историю диалога и контекст сессии?",
            confirmText = "Очистить",
            isDestructive = true,
            onConfirm = { viewModel.confirmClearChat() },
            onDismiss = { viewModel.showClearConfirmDialog.value = false }
        )
    }

    if (showForkConfirmDialog) {
        ConfirmationDialog(
            title = "Разветвить сессию (/fork)",
            message = "Создать независимое ответвление текущей сессии с сохранением контекста?",
            confirmText = "Создать Fork",
            isDestructive = false,
            onConfirm = { viewModel.confirmForkChat() },
            onDismiss = { viewModel.showForkConfirmDialog.value = false }
        )
    }

    if (showBtwDialog) {
        WorkflowPromptDialog(
            data = ChatViewModel.WorkflowDialogData(
                commandName = "btw",
                title = "Побочный вопрос (/btw)",
                description = "Задайте быстрый вопрос агенту без сохранения в общую историю диалога.",
                hint = if (btwInitialQuery.isNotBlank()) btwInitialQuery else "Введите ваш вопрос..."
            ),
            onConfirm = { question ->
                viewModel.showBtwDialog.value = false
                viewModel.submitBtwQuestion(question)
            },
            onDismiss = { viewModel.showBtwDialog.value = false }
        )
    }

    previewArtifact?.let { art ->
        ArtifactPreviewDialog(
            artifact = art,
            content = previewArtifactContent,
            isLoading = isLoadingArtifactContent,
            onDismiss = { previewArtifact = null },
            onDownload = {
                viewModel.downloadFile(context, art.path, art.fileName)
            }
        )
    }
}

@Composable
private fun UserBubble(
    bubble: ConversationBubble.User,
    audioPlayer: AudioPlayer,
    resolveServerUrl: (String) -> String,
    onPreviewImage: (String, String?) -> Unit,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = PrimaryBlue,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Attachments
                if (bubble.attachments.isNotEmpty()) {
                    bubble.attachments.forEach { att ->
                        when (att.type) {
                            AttachmentType.AUDIO -> {
                                VoiceMessageCard(
                                    attachment = att,
                                    audioPlayer = audioPlayer,
                                    isUser = true,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                    resolveServerUrl = resolveServerUrl
                                )
                            }
                            AttachmentType.IMAGE -> {
                                val imgModel = att.localUri ?: att.remoteUrl?.let { if (it.startsWith("http")) it else resolveServerUrl(it) }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .padding(bottom = 6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurfaceVariant)
                                        .clickable {
                                            imgModel?.let { onPreviewImage(it, att.fileName) }
                                        }
                                ) {
                                    coil.compose.AsyncImage(
                                        model = imgModel,
                                        contentDescription = att.fileName,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            else -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurfaceVariant.copy(alpha = 0.6f))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (att.type) {
                                            AttachmentType.VIDEO -> AppIcons.PlayArrow
                                            else -> AppIcons.Description
                                        },
                                        contentDescription = null,
                                        tint = TextPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = att.fileName,
                                            color = TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val szKb = if (att.size > 0) "${att.size / 1024} KB" else ""
                                        if (szKb.isNotEmpty()) {
                                            Text(
                                                text = szKb,
                                                color = TextPrimary.copy(alpha = 0.7f),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (bubble.text.isNotBlank()) {
                    Text(
                        text = bubble.text,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (bubble.status) {
                        MessageDeliveryStatus.LOCAL,
                        MessageDeliveryStatus.UPLOADING,
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
                            Row(
                                modifier = Modifier.clickable { onRetry() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Failed",
                                    tint = ErrorRed,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Повторить ↻",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
        val clean = path.removePrefix("file://").trim().trimEnd('.', ',', ';')
        if (clean in seen || clean.length < 3) return
        seen.add(clean)
        val name = clean.substringAfterLast('/')
        val isImg = name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                    name.endsWith(".jpeg", true) || name.endsWith(".webp", true) ||
                    name.endsWith(".gif", true) || name.endsWith(".svg", true)
        list.add(FileAttachment(name = name, path = clean, isImage = isImg, action = action))
    }

    bubble.attachments.forEach { att ->
        val p = att.localUri ?: att.remoteUrl
        if (!p.isNullOrBlank()) {
            add(p, if (att.type == AttachmentType.IMAGE) "Generated Image" else "Attachment")
        }
    }

    bubble.toolCallsWithResults.forEach { (tool, result) ->
        when (tool.name) {
            "generate_image" -> {
                val savedAtMatch = Regex("""(?:Generated image is saved at|Image saved to|saved at|saved to)\s+([^\s\n\r]+)""", RegexOption.IGNORE_CASE).find(result ?: "")
                val savedPath = savedAtMatch?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', ';')
                val imgPath = savedPath ?: (tool.args["ImageName"] as? String)?.let { name ->
                    val clean = name.trim().trim('"', '\'')
                    if (clean.startsWith("/")) clean else "/root/.gemini/antigravity-cli/brain/${bubble.conversationId}/$clean.jpg"
                } ?: "generated_image.png"
                add(imgPath, "Generated Image")
            }
            "view_file" -> {
                val p = tool.args["AbsolutePath"] as? String
                if (p != null && (p.endsWith(".png", true) || p.endsWith(".jpg", true) || p.endsWith(".jpeg", true) || p.endsWith(".webp", true) || p.endsWith(".pdf", true) || p.endsWith(".apk", true) || p.endsWith(".mp4", true))) {
                    add(p, "Viewed File")
                }
            }
        }
    }

    bubble.messageText?.let { text ->
        val fileRegex = Regex("""\[(.*?)\]\((file:///[^\s)]+|/[^\s)]+)\)""")
        fileRegex.findAll(text).forEach { m ->
            val p = m.groupValues[2]
            val isDeliverable = p.endsWith(".apk", true) || p.endsWith(".zip", true) ||
                                p.endsWith(".pdf", true) || p.endsWith(".png", true) ||
                                p.endsWith(".jpg", true) || p.endsWith(".jpeg", true) ||
                                p.endsWith(".webp", true) || p.endsWith(".mp4", true) ||
                                p.endsWith(".tar.gz", true) || p.endsWith(".csv", true)
            if (isDeliverable) {
                add(p, "Referenced file")
            }
        }
    }

    return list
}

@Composable
private fun AgentBubble(
    bubble: ConversationBubble.Agent,
    viewModel: ChatViewModel,
    onPreviewImage: (String, String?) -> Unit
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { file ->
                    if (file.isImage) {
                        ImageAttachmentCard(
                            file = file,
                            imageUrl = viewModel.getFileRawUrl(file.path),
                            onPreviewImage = { url -> onPreviewImage(url, file.name) },
                            onDownload = { viewModel.downloadFile(context, file.path, file.name) }
                        )
                    } else {
                        FileAttachmentCard(
                            file = file,
                            onDownload = { viewModel.downloadFile(context, file.path, file.name) }
                        )
                    }
                }
            }
        }

        // Clean Agent Response Text with rich Markdown, clickable links, code blocks, and photos
        // Deduplicate images if already rendered in attachments
        val displayedMarkdown = remember(bubble.messageText, attachments) {
            val text = bubble.messageText ?: return@remember ""
            val imgPathsOrNames = attachments.filter { it.isImage }.flatMap { listOf(it.name, it.path) }.toSet()
            if (imgPathsOrNames.isEmpty()) {
                text
            } else {
                text.lines().filterNot { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("![") && trimmed.endsWith(")")) {
                        imgPathsOrNames.any { imgId -> trimmed.contains(imgId) }
                    } else {
                        false
                    }
                }.joinToString("\n")
            }
        }

        if (displayedMarkdown.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            MarkdownText(
                markdown = displayedMarkdown,
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
                onImageClick = { onPreviewImage(it, null) },
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
    resolveServerUrl: (String) -> String,
    onAnswerQuestion: (String) -> Unit = {}
) {
    Surface(
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val isAskQuestion = activity != null && (
                activity.toolName == "ask_question" ||
                activity.detail.contains("ask_question", ignoreCase = true) ||
                activity.parameters.containsKey("questions") ||
                activity.parameters.containsKey("question")
            )

            // Header: Status indicator + Stop button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val actType = activity?.activity ?: if (deltaText.isNotEmpty()) "generating" else "thinking"
                    when {
                        isAskQuestion -> {
                            Icon(
                                imageVector = Icons.Default.QuestionAnswer,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Вопрос от агента (требуется ответ)",
                                color = WarningOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        actType == "tool_running" -> {
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
                        actType == "generating" -> {
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

            // Interactive ask_question parameters
            if (isAskQuestion && activity != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val rawQuestions = activity.parameters["questions"] as? List<*>
                val questionsList = mutableListOf<Triple<String, List<String>, Boolean>>()
                if (rawQuestions != null) {
                    for (item in rawQuestions) {
                        if (item is Map<*, *>) {
                            val qText = item["question"]?.toString() ?: ""
                            val opts = (item["options"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                            val multi = item["is_multi_select"] as? Boolean ?: false
                            questionsList.add(Triple(qText, opts, multi))
                        } else if (item is String) {
                            questionsList.add(Triple(item, emptyList(), false))
                        }
                    }
                } else {
                    val singleQ = (activity.parameters["question"] as? String) ?: ""
                    val opts = (activity.parameters["options"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    val multi = activity.parameters["is_multi_select"] as? Boolean ?: false
                    if (singleQ.isNotBlank() || opts.isNotEmpty()) {
                        questionsList.add(Triple(singleQ, opts, multi))
                    }
                }

                if (questionsList.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurfaceVariant)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        questionsList.forEach { (qTitle, opts, _) ->
                            if (qTitle.isNotBlank()) {
                                Text(
                                    text = qTitle,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (opts.isNotEmpty()) {
                                Text(
                                    text = "Нажмите для ответа:",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    opts.forEach { opt ->
                                        Surface(
                                            color = DarkBackground,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.4f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onAnswerQuestion(opt) }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircleOutline,
                                                    contentDescription = null,
                                                    tint = PrimaryBlue,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = opt,
                                                    color = TextPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "Введите ваш ответ в поле сообщения ниже",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            } else if (activity != null && activity.activity == "tool_running" && activity.parameters.isNotEmpty()) {
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
