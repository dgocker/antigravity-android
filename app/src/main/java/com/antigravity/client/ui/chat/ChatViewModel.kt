package com.antigravity.client.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.client.AntigravityApp
import com.antigravity.client.data.remote.NetworkClient
import com.antigravity.client.audio.AudioPlayer
import com.antigravity.client.audio.AudioRecordingResult
import com.antigravity.client.data.local.AttachmentEntity
import com.antigravity.client.data.local.OutboxEntity
import com.antigravity.client.domain.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import com.antigravity.client.data.remote.dto.*
import java.io.File
import java.util.UUID

val DEFAULT_SLASH_COMMANDS = listOf(
    SlashCommandDto("model", "Выбор активной нейросети (Gemini Flash, Pro, Claude и др.)", "Модель", "model_picker", "/model"),
    SlashCommandDto("tasks", "Просмотр и управление фоновыми процессами и субагентами", "Инструменты", "tasks", "/tasks"),
    SlashCommandDto("artifact", "Просмотр созданных AI артефактов (планы, отчеты, код)", "Инструменты", "artifacts", "/artifact"),
    SlashCommandDto("diff", "Показать текущий git diff (изменения файлов)", "Инструменты", "insert", "/diff"),
    SlashCommandDto("clear", "Очистить историю текущего диалога", "Диалог", "clear", "/clear"),
    SlashCommandDto("title", "Изменить название текущего чата", "Диалог", "insert", "/title "),
    SlashCommandDto("fork", "Ответвить диалог в новый чат с текущего шага", "Диалог", "insert", "/fork"),
    SlashCommandDto("rewind", "Откатить диалог на предыдущий шаг", "Диалог", "insert", "/rewind"),
    SlashCommandDto("btw", "Задать попутный вопрос без засорения контекста", "Диалог", "insert", "/btw "),
    SlashCommandDto("effort", "Уровень рассуждений модели (low, medium, high)", "Модель", "insert", "/effort "),
    SlashCommandDto("context", "Показать занятый объем контекстного окна и токены", "Модель", "insert", "/context"),
    SlashCommandDto("usage", "Показать статистику расхода токенов и квот", "Модель", "insert", "/usage"),
    SlashCommandDto("credits", "Проверить остаток кредитов и баланса", "Модель", "insert", "/credits"),
    SlashCommandDto("goal", "Автономное достижение цели до победного конца", "Режимы", "insert", "/goal "),
    SlashCommandDto("plan", "Создать подробный план реализации перед кодингом", "Режимы", "insert", "/plan "),
    SlashCommandDto("teamwork-preview", "Запуск мультиагентной команды для масштабных задач", "Режимы", "insert", "/teamwork-preview"),
    SlashCommandDto("grill-me", "Интервью: агент задаст уточняющие вопросы по требованиям", "Режимы", "insert", "/grill-me"),
    SlashCommandDto("boost", "Углубленный анализ задачи с разных точек зрения", "Режимы", "insert", "/boost "),
    SlashCommandDto("browser", "Автоматизация действий и поиск через веб-браузер", "Режимы", "insert", "/browser "),
    SlashCommandDto("schedule", "Запуск задачи по расписанию или таймеру", "Режимы", "insert", "/schedule "),
    SlashCommandDto("learn", "Запомнить правило/инструкцию для будущих сессий", "Режимы", "insert", "/learn "),
    SlashCommandDto("agents", "Список всех доступных специализированных субагентов", "Агенты", "insert", "/agents"),
    SlashCommandDto("skills", "Список подключенных навыков и умений агента", "Агенты", "insert", "/skills"),
    SlashCommandDto("mcp", "Статус серверов MCP (Model Context Protocol)", "Агенты", "insert", "/mcp"),
    SlashCommandDto("help", "Справка по всем возможностям и слэш-командам", "Справка", "help", "/help")
)

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

    val audioPlayer = AudioPlayer(app.applicationContext)

    val isCancelling = MutableStateFlow(false)
    val errorState = MutableStateFlow<String?>(null)
    val isLoadingHistory = MutableStateFlow(true)

    val availableModels = MutableStateFlow<List<ModelOption>>(DEFAULT_MODELS)
    val selectedModel = MutableStateFlow<String>(tokenStore.selectedModel ?: "gemini-3.8-flash-high")
    val selectedEffort = MutableStateFlow<String>(tokenStore.selectedEffort)

    val slashCommands = MutableStateFlow<List<SlashCommandDto>>(DEFAULT_SLASH_COMMANDS)
    val artifacts = MutableStateFlow<List<ArtifactDto>>(emptyList())
    val tasks = MutableStateFlow<TasksResponseDto>(TasksResponseDto())
    val isLoadingArtifacts = MutableStateFlow(false)
    val isLoadingTasks = MutableStateFlow(false)

    fun refreshChat() {
        viewModelScope.launch {
            try {
                repository.fetchStepsHistory(conversationId)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun loadArtifacts() {
        viewModelScope.launch {
            isLoadingArtifacts.value = true
            try {
                artifacts.value = repository.getArtifacts(conversationId)
            } finally {
                isLoadingArtifacts.value = false
            }
        }
    }

    fun loadTasks() {
        viewModelScope.launch {
            isLoadingTasks.value = true
            try {
                tasks.value = repository.getTasks(conversationId)
            } finally {
                isLoadingTasks.value = false
            }
        }
    }

    fun killTask(taskId: String) {
        viewModelScope.launch {
            repository.killTask(conversationId, taskId)
            loadTasks()
        }
    }

    fun sendQuestionAnswer(answerText: String) {
        inputMessage.value = answerText
        sendMessage()
    }

    suspend fun getFileContent(path: String): String {
        return repository.getFileContent(path)
    }

    fun selectModel(modelId: String) {
        selectedModel.value = modelId
        tokenStore.selectedModel = modelId
    }

    fun selectEffort(effort: String) {
        selectedEffort.value = effort
        tokenStore.selectedEffort = effort
    }

    init {
        // Subscribe WebSocket to this active conversation for live transcript streaming
        syncEngine.subscribeToConversation(conversationId)

        // Load available models and slash commands
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

        viewModelScope.launch {
            try {
                val serverCmds = repository.getSlashCommands()
                if (serverCmds.isNotEmpty()) {
                    slashCommands.value = serverCmds
                }
            } catch (e: Exception) {
                // Keep default slash commands
            }
        }

        // Load full step history from server
        viewModelScope.launch {
            try {
                isLoadingHistory.value = true
                repository.deleteCheckpointSteps(conversationId)
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
            attachmentIdsJson = gson.toJson(listOf(voiceAttachment.id)),
            state = "PENDING"
        )

        val attEntity = AttachmentEntity(
            id = voiceAttachment.id,
            conversationId = conversationId,
            messageId = pendingId,
            type = voiceAttachment.type.name,
            fileName = voiceAttachment.fileName,
            mimeType = voiceAttachment.mimeType,
            size = voiceAttachment.size,
            duration = voiceAttachment.duration,
            localUri = voiceAttachment.localUri,
            remoteUrl = voiceAttachment.remoteUrl,
            serverId = voiceAttachment.serverId,
            uploadState = voiceAttachment.uploadState.name,
            transcription = voiceAttachment.transcription
        )

        viewModelScope.launch {
            try {
                repository.saveOutboxItem(outboxItem)
                repository.saveAttachment(attEntity)
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
        repository.addPendingMessage(conversationId, pendingMsg)

        val outboxItem = OutboxEntity(
            id = pendingId,
            conversationId = conversationId,
            text = fullMessage,
            attachmentIdsJson = gson.toJson(currentAttachments.map { it.id }),
            state = "PENDING"
        )

        viewModelScope.launch {
            try {
                repository.saveOutboxItem(outboxItem)
                currentAttachments.forEach { att ->
                    val attEntity = AttachmentEntity(
                        id = att.id,
                        conversationId = conversationId,
                        messageId = pendingId,
                        type = att.type.name,
                        fileName = att.fileName,
                        mimeType = att.mimeType,
                        size = att.size,
                        duration = att.duration,
                        localUri = att.localUri,
                        remoteUrl = att.remoteUrl,
                        serverId = att.serverId,
                        uploadState = att.uploadState.name,
                        transcription = att.transcription
                    )
                    repository.saveAttachment(attEntity)
                }
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
                    val updated = att.copy(
                        serverId = resp.id,
                        remoteUrl = resp.storagePath,
                        uploadState = AttachmentUploadState.COMPLETED
                    )
                    repository.updateAttachmentState(
                        id = att.id,
                        state = AttachmentUploadState.COMPLETED.name,
                        remoteUrl = resp.storagePath,
                        serverId = resp.id
                    )
                    updated
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
                    val attEntities = repository.getAttachmentsForMessage(item.id).firstOrNull() ?: emptyList()
                    val attachments: List<Attachment> = attEntities.map { entity ->
                        Attachment(
                            id = entity.id,
                            conversationId = entity.conversationId,
                            type = when (entity.type) {
                                "IMAGE" -> AttachmentType.IMAGE
                                "VIDEO" -> AttachmentType.VIDEO
                                "AUDIO" -> AttachmentType.AUDIO
                                "DOCUMENT" -> AttachmentType.DOCUMENT
                                else -> AttachmentType.OTHER
                            },
                            fileName = entity.fileName,
                            mimeType = entity.mimeType,
                            size = entity.size,
                            duration = entity.duration,
                            localUri = entity.localUri,
                            remoteUrl = entity.remoteUrl,
                            serverId = entity.serverId,
                            uploadState = try { AttachmentUploadState.valueOf(entity.uploadState) } catch (_: Exception) { AttachmentUploadState.LOCAL },
                            transcription = entity.transcription
                        )
                    }
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
        val baseUrl = NetworkClient.normalizeBaseUrl(tokenStore.serverUrl)
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
        val baseUrl = NetworkClient.normalizeBaseUrl(tokenStore.serverUrl)

        val downloadUrl: String
        val resolvedFileName: String

        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            // Already a full URL (e.g. from ImagePreviewDialog or remote link)
            val uri = android.net.Uri.parse(filePath)
            val pathParam = uri.getQueryParameter("path")
            val targetPath = pathParam ?: uri.lastPathSegment ?: "downloaded_file"
            resolvedFileName = customFilename
                ?: targetPath.substringAfterLast('/').substringBefore('?').ifEmpty { "downloaded_file" }

            val builder = uri.buildUpon()
            if (uri.getQueryParameter("download") == null) {
                builder.appendQueryParameter("download", "true")
            }
            if (uri.getQueryParameter("token") == null) {
                builder.appendQueryParameter("token", token)
            }
            downloadUrl = builder.build().toString()
        } else {
            val cleanPath = filePath.removePrefix("file://").trim().trimEnd('.', ',', ';')
            val encodedPath = java.net.URLEncoder.encode(cleanPath, "UTF-8")
            downloadUrl = "$baseUrl/v1/files/raw?path=$encodedPath&download=true&token=$token"
            resolvedFileName = customFilename ?: cleanPath.substringAfterLast('/').ifEmpty { "downloaded_file" }
        }

        val fileName = resolvedFileName.substringBefore('?').substringBefore('&').ifEmpty { "downloaded_file" }
        android.widget.Toast.makeText(context, "Скачивание начато: $fileName", android.widget.Toast.LENGTH_SHORT).show()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder()
                    .url(downloadUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                val response = app.networkClient.okHttpClient.newCall(req).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Ошибка сервера при скачивании (код $code)", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val body = response.body ?: throw Exception("Пустой ответ от сервера")

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val mime = response.header("Content-Type") ?: "application/octet-stream"
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            body.byteStream().copyTo(out)
                        }
                    } else {
                        throw Exception("Не удалось создать файл в Downloads")
                    }
                } else {
                    val targetDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (!targetDir.exists()) targetDir.mkdirs()
                    val targetFile = java.io.File(targetDir, fileName)
                    targetFile.outputStream().use { out ->
                        body.byteStream().copyTo(out)
                    }
                }

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Файл сохранен в Загрузки: $fileName", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Ошибка скачивания: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
