package com.antigravity.client.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.client.AntigravityApp
import com.antigravity.client.audio.AudioPlayer
import com.antigravity.client.audio.AudioRecordingResult
import com.antigravity.client.data.local.OutboxEntity
import com.antigravity.client.domain.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

val DEFAULT_MODELS = listOf(
    ModelOption("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)", "Самые быстрые и точные ответы, высокая логика"),
    ModelOption("gemini-3.8-flash-medium", "Gemini 3.8 Flash (Medium)", "Баланс скорости и рассуждений"),
    ModelOption("gemini-3.8-flash-low", "Gemini 3.8 Flash (Low)", "Максимально быстрые ответы"),
    ModelOption("gemini-3.7-flash-high", "Gemini 3.7 Flash (High)", "Высокая точность и скорость"),
    ModelOption("gemini-3.7-flash-medium", "Gemini 3.7 Flash (Medium)", "Средний уровень размышлений"),
    ModelOption("gemini-3.7-flash-low", "Gemini 3.7 Flash (Low)", "Быстрые ответы"),
    ModelOption("gemini-3.1-pro-high", "Gemini 3.1 Pro (High)", "Расширенные возможности рассуждения"),
    ModelOption("gemini-3.1-pro-low", "Gemini 3.1 Pro (Low)", "Быстрый Pro режим"),
    ModelOption("claude-sonnet-4-6", "Claude Sonnet 4.6 (Thinking)", "Агентное мышление и глубокий кодинг"),
    ModelOption("claude-opus-4-6-thinking", "Claude Opus 4.6 (Thinking)", "Максимальная глубина рассуждений"),
    ModelOption("gpt-oss-120b-medium", "GPT-OSS 120B (Medium)", "Open-source 120B модель"),
    ModelOption("gemini-3.6-flash-high", "Gemini 3.6 Flash (High)", "Стабильная Flash модель")
)

class ChatViewModel(
    val conversationId: String,
    private val app: AntigravityApp = AntigravityApp.instance
) : ViewModel() {

    private val repository = app.chatRepository
    private val syncEngine = app.syncEngine
    val tokenStore = app.tokenStore
    private val gson = Gson()

    val connectionStatus: StateFlow<ConnectionStatus> = syncEngine.connectionState

    val conversation: StateFlow<Conversation?> = repository.getConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val steps: StateFlow<List<Step>> = repository.getSteps(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Optimistic pending messages that persist across screen navigation via singleton repository
    val pendingMessages: StateFlow<List<PendingUserMessage>> = repository
        .observePendingMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Live streaming text accumulator for this specific conversation
    val activeDeltaText: StateFlow<String> = syncEngine.liveDeltas
        .map { it[conversationId] ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Live agent activity (thinking, tool execution) for this specific conversation
    val liveActivity: StateFlow<LiveActivity?> = syncEngine.liveActivity
        .map { it[conversationId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val quotedSnippet = MutableStateFlow<String?>(null)
    val inputMessage = MutableStateFlow("")
    val pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())

    val audioPlayer = AudioPlayer()

    val isCancelling = MutableStateFlow(false)
    val errorState = MutableStateFlow<String?>(null)
    val isLoadingHistory = MutableStateFlow(true)

    val availableModels = MutableStateFlow<List<ModelOption>>(DEFAULT_MODELS)
    val selectedModel = MutableStateFlow<String>(tokenStore.selectedModel ?: "gemini-3.8-flash-high")
    val selectedEffort = MutableStateFlow<String>(tokenStore.selectedEffort)

    fun selectModel(modelId: String) {
        selectedModel.value = modelId
        tokenStore.selectedModel = modelId
    }

    fun selectEffort(effort: String) {
        selectedEffort.value = effort
        tokenStore.selectedEffort = effort
    }

    init {
        // Load available models
        viewModelScope.launch {
            try {
                val serverModels = repository.getAvailableModels()
                if (serverModels.isNotEmpty()) {
                    availableModels.value = serverModels
                }
            } catch (e: Exception) {
                // Keep default models
            }
        }

        // Load full step history from server
        viewModelScope.launch {
            try {
                isLoadingHistory.value = true
                repository.fetchStepsHistory(conversationId)
            } catch (e: Exception) {
                // Ignore, will use cache
            } finally {
                isLoadingHistory.value = false
            }
        }

        // Reconcile pending messages: remove from pending list once confirmed in steps DB
        viewModelScope.launch {
            steps.collect { currentSteps ->
                repository.reconcilePendingMessages(conversationId, currentSteps)
            }
        }

        // Auto retry outbox items on network connection restored
        viewModelScope.launch {
            connectionStatus.collect { status ->
                if (status == ConnectionStatus.CONNECTED) {
                    retryOutboxQueue()
                }
            }
        }
    }

    fun attachFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = app.applicationContext
                val resolver = context.contentResolver
                var fileName = "file_${System.currentTimeMillis()}"
                var sizeBytes = 0L

                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                    }
                }

                val mimeType = resolver.getType(uri) ?: when {
                    fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
                    fileName.endsWith(".png", true) -> "image/png"
                    fileName.endsWith(".webp", true) -> "image/webp"
                    fileName.endsWith(".gif", true) -> "image/gif"
                    fileName.endsWith(".mp4", true) -> "video/mp4"
                    fileName.endsWith(".m4a", true) -> "audio/m4a"
                    fileName.endsWith(".mp3", true) -> "audio/mpeg"
                    fileName.endsWith(".pdf", true) -> "application/pdf"
                    fileName.endsWith(".txt", true) -> "text/plain"
                    fileName.endsWith(".json", true) -> "application/json"
                    fileName.endsWith(".md", true) -> "text/markdown"
                    else -> "application/octet-stream"
                }

                val type = when {
                    mimeType.startsWith("image/") -> AttachmentType.IMAGE
                    mimeType.startsWith("video/") -> AttachmentType.VIDEO
                    mimeType.startsWith("audio/") -> AttachmentType.AUDIO
                    mimeType.startsWith("text/") || mimeType.contains("pdf") || mimeType.contains("json") -> AttachmentType.DOCUMENT
                    else -> AttachmentType.OTHER
                }

                val cacheDir = File(context.cacheDir, "attachments").apply { mkdirs() }
                val cacheFile = File(cacheDir, "${UUID.randomUUID()}_$fileName")
                resolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (sizeBytes <= 0) {
                    sizeBytes = cacheFile.length()
                }

                val att = Attachment(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    type = type,
                    fileName = fileName,
                    mimeType = mimeType,
                    size = sizeBytes,
                    localUri = cacheFile.absolutePath,
                    uploadState = AttachmentUploadState.PENDING
                )
                pendingAttachments.value = pendingAttachments.value + att
            } catch (e: Exception) {
                errorState.value = "Ошибка вложения файла: ${e.message}"
            }
        }
    }

    fun removePendingAttachment(id: String) {
        pendingAttachments.value = pendingAttachments.value.filter { it.id != id }
    }

    fun attachVoiceNote(result: AudioRecordingResult) {
        val att = Attachment(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            type = AttachmentType.AUDIO,
            fileName = result.file.name,
            mimeType = result.mimeType,
            size = result.sizeBytes,
            duration = result.durationSeconds,
            localUri = result.file.absolutePath,
            transcription = result.transcription,
            uploadState = AttachmentUploadState.PENDING
        )
        sendVoiceMessage(att)
    }

    private fun sendVoiceMessage(voiceAttachment: Attachment) {
        val conv = conversation.value
        val workspace = conv?.workspace ?: tokenStore.defaultWorkspace
        val currentBaseStep = steps.value.maxOfOrNull { it.stepIndex } ?: -1
        val pendingId = UUID.randomUUID().toString()
        val pendingMsg = PendingUserMessage(
            id = pendingId,
            text = "",
            attachments = listOf(voiceAttachment),
            status = MessageDeliveryStatus.SENDING,
            baseStepIndex = currentBaseStep
        )
        repository.addPendingMessage(conversationId, pendingMsg)

        val outboxItem = OutboxEntity(
            id = pendingId,
            conversationId = conversationId,
            text = "",
            attachmentsJson = gson.toJson(listOf(voiceAttachment)),
            model = selectedModel.value,
            effort = selectedEffort.value,
            mode = tokenStore.selectedMode,
            state = "PENDING"
        )

        viewModelScope.launch {
            try {
                repository.saveOutboxItem(outboxItem)
                executeSendMessage(pendingId, workspace, "", listOf(voiceAttachment))
            } catch (e: Exception) {
                repository.updatePendingMessageStatus(conversationId, pendingId, MessageDeliveryStatus.FAILED)
                repository.updateOutboxState(pendingId, "FAILED", e.message)
                errorState.value = "Ошибка отправки голосового сообщения: ${e.message}"
            }
        }
    }

    fun sendMessage() {
        val rawText = inputMessage.value.trim()
        val currentAttachments = pendingAttachments.value
        if (rawText.isBlank() && currentAttachments.isEmpty()) return

        val fullMessage = if (!quotedSnippet.value.isNullOrBlank()) {
            "Quoted code:\n```\n${quotedSnippet.value}\n```\n\n$rawText"
        } else {
            rawText
        }

        val conv = conversation.value
        val workspace = conv?.workspace ?: tokenStore.defaultWorkspace

        inputMessage.value = ""
        quotedSnippet.value = null
        pendingAttachments.value = emptyList()

        val currentBaseStep = steps.value.maxOfOrNull { it.stepIndex } ?: -1
        val pendingId = UUID.randomUUID().toString()
        val pendingMsg = PendingUserMessage(
            id = pendingId,
            text = fullMessage,
            attachments = currentAttachments,
            status = MessageDeliveryStatus.SENDING,
            baseStepIndex = currentBaseStep
        )
        // Add immediately to persistent repository store
        repository.addPendingMessage(conversationId, pendingMsg)

        val outboxItem = OutboxEntity(
            id = pendingId,
            conversationId = conversationId,
            text = fullMessage,
            attachmentsJson = gson.toJson(currentAttachments),
            model = selectedModel.value,
            effort = selectedEffort.value,
            mode = tokenStore.selectedMode,
            state = "PENDING"
        )

        viewModelScope.launch {
            try {
                repository.saveOutboxItem(outboxItem)
                executeSendMessage(pendingId, workspace, fullMessage, currentAttachments)
            } catch (e: Exception) {
                repository.updatePendingMessageStatus(conversationId, pendingId, MessageDeliveryStatus.FAILED)
                repository.updateOutboxState(pendingId, "FAILED", e.message)
                errorState.value = "Не удалось отправить сообщение: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    private suspend fun executeSendMessage(
        pendingId: String,
        workspace: String,
        text: String,
        attachments: List<Attachment>
    ) {
        // Upload attachments first if needed
        val uploadedAttachments = attachments.map { att ->
            if (att.serverId != null) {
                att
            } else if (!att.localUri.isNullOrEmpty()) {
                val file = File(att.localUri)
                if (file.exists()) {
                    val resp = repository.uploadAttachment(
                        file = file,
                        mimeType = att.mimeType,
                        conversationId = conversationId,
                        transcription = att.transcription,
                        duration = att.duration
                    )
                    att.copy(
                        serverId = resp.id,
                        remoteUrl = resp.storagePath,
                        uploadState = AttachmentUploadState.COMPLETED
                    )
                } else {
                    att
                }
            } else {
                att
            }
        }

        // Send message to server
        repository.sendMessage(
            conversationId = conversationId,
            workspace = workspace,
            text = text,
            attachments = uploadedAttachments,
            model = selectedModel.value,
            effort = selectedEffort.value,
            mode = tokenStore.selectedMode
        )

        // Mark outbox completed & update delivery status
        repository.deleteOutboxItem(pendingId)
        repository.updatePendingMessageStatus(conversationId, pendingId, MessageDeliveryStatus.SENT)

        triggerBackgroundSyncLoop()
    }

    fun retryPendingMessage(pendingId: String) {
        val msg = pendingMessages.value.find { it.id == pendingId } ?: return
        repository.updatePendingMessageStatus(conversationId, pendingId, MessageDeliveryStatus.SENDING)
        val conv = conversation.value
        val workspace = conv?.workspace ?: tokenStore.defaultWorkspace

        viewModelScope.launch {
            try {
                executeSendMessage(pendingId, workspace, msg.text, msg.attachments)
            } catch (e: Exception) {
                repository.updatePendingMessageStatus(conversationId, pendingId, MessageDeliveryStatus.FAILED)
                repository.updateOutboxState(pendingId, "FAILED", e.message)
                errorState.value = "Ошибка повторной отправки: ${e.message}"
            }
        }
    }

    private fun retryOutboxQueue() {
        viewModelScope.launch {
            try {
                val outboxItems = repository.getPendingOutboxItems().firstOrNull() ?: emptyList()
                val convItems = outboxItems.filter { it.conversationId == conversationId }
                for (item in convItems) {
                    val attachments: List<Attachment> = item.attachmentsJson?.let {
                        try {
                            val listType = object : TypeToken<List<Attachment>>() {}.type
                            gson.fromJson(it, listType) ?: emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    } ?: emptyList()
                    val conv = conversation.value
                    val workspace = conv?.workspace ?: tokenStore.defaultWorkspace
                    try {
                        executeSendMessage(item.id, workspace, item.text, attachments)
                    } catch (e: Exception) {
                        repository.updateOutboxState(item.id, "FAILED", e.message)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun triggerBackgroundSyncLoop() {
        viewModelScope.launch {
            var checks = 0
            while (checks < 90) {
                kotlinx.coroutines.delay(2000)
                checks++
                val isTurnRunning = (conversation.value?.status?.contains("RUNNING", ignoreCase = true) == true) ||
                                    activeDeltaText.value.isNotEmpty() ||
                                    (liveActivity.value != null && liveActivity.value?.activity != "idle")

                try {
                    repository.fetchStepsHistory(conversationId)
                } catch (_: Exception) {}

                if (!isTurnRunning && checks > 2) {
                    break
                }
            }
        }
    }

    fun cancelRun() {
        isCancelling.value = true
        viewModelScope.launch {
            try {
                repository.cancelRun(conversationId)
            } catch (e: Exception) {
                errorState.value = "Failed to cancel run: ${e.localizedMessage ?: e.message}"
            } finally {
                isCancelling.value = false
            }
        }
    }

    fun setQuote(code: String) {
        quotedSnippet.value = code
    }

    fun clearQuote() {
        quotedSnippet.value = null
    }

    fun clearError() {
        errorState.value = null
    }

    fun getFileRawUrl(filePath: String): String {
        val token = tokenStore.getToken() ?: ""
        var baseUrl = tokenStore.serverUrl.trim().trimEnd('/')
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://$baseUrl"
        }
        val cleanPath = filePath.removePrefix("file://")
        val encodedPath = java.net.URLEncoder.encode(cleanPath, "UTF-8")
        return "$baseUrl/v1/files/raw?path=$encodedPath&token=$token"
    }

    fun downloadFile(context: android.content.Context, filePath: String, customFilename: String? = null) {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            android.widget.Toast.makeText(context, "Authentication token missing", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        var baseUrl = tokenStore.serverUrl.trim().trimEnd('/')
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://$baseUrl"
        }
        val cleanPath = filePath.removePrefix("file://")
        val encodedPath = java.net.URLEncoder.encode(cleanPath, "UTF-8")
        val downloadUrl = "$baseUrl/v1/files/raw?path=$encodedPath&download=true&token=$token"
        val fileName = customFilename ?: cleanPath.substringAfterLast('/').ifEmpty { "downloaded_file" }

        try {
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(downloadUrl)).apply {
                setTitle(fileName)
                setDescription("Downloading $fileName from server")
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)
            android.widget.Toast.makeText(context, "Скачивание начато: $fileName", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Ошибка загрузки: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
