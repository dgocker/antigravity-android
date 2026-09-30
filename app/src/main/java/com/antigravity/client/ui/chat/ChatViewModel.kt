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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import com.antigravity.client.data.remote.dto.*
import java.io.File
import java.util.UUID

val DEFAULT_SLASH_COMMANDS = listOf(
    SlashCommandDto("effort", "Уровень рассуждений модели (low, medium, high, off)", "Модель", "effort", "/effort high"),
    SlashCommandDto("model", "Выбор активной нейросети (Gemini Flash, Pro, Claude и др.)", "Модель", "model", "/model"),
    SlashCommandDto("context", "Показать занятый объем контекстного окна и токены", "Модель", "context", "/context"),
    SlashCommandDto("usage", "Показать статистику расхода токенов и квот", "Модель", "usage", "/usage"),
    SlashCommandDto("credits", "Проверить остаток кредитов и баланса", "Модель", "credits", "/credits"),
    SlashCommandDto("tasks", "Просмотр и управление фоновыми процессами и субагентами", "Инструменты", "tasks", "/tasks"),
    SlashCommandDto("artifact", "Просмотр созданных AI артефактов (планы, отчеты, код)", "Инструменты", "artifacts", "/artifact"),
    SlashCommandDto("artifacts", "Список всех созданных артефактов текущего диалога", "Инструменты", "artifacts", "/artifacts"),
    SlashCommandDto("diff", "Показать текущий git diff (изменения файлов)", "Инструменты", "diff", "/diff"),
    SlashCommandDto("clear", "Очистить историю текущего диалога", "Диалог", "clear", "/clear"),
    SlashCommandDto("title", "Изменить название текущего чата", "Диалог", "title", "/title Новое название"),
    SlashCommandDto("fork", "Ответвить диалог в новый чат с текущего шага", "Диалог", "fork", "/fork"),
    SlashCommandDto("rewind", "Откатить диалог на предыдущий шаг", "Диалог", "rewind", "/rewind"),
    SlashCommandDto("btw", "Задать попутный вопрос без засорения контекста", "Диалог", "btw", "/btw Что означает этот флаг?"),
    SlashCommandDto("goal", "Автономное достижение цели до победного конца", "Режимы", "workflow", "/goal Достичь цели..."),
    SlashCommandDto("plan", "Создать подробный план реализации перед кодингом", "Режимы", "workflow", "/plan Спланировать..."),
    SlashCommandDto("teamwork-preview", "Запуск мультиагентной команды для масштабных задач", "Режимы", "workflow", "/teamwork-preview"),
    SlashCommandDto("grill-me", "Интервью: агент задаст уточняющие вопросы по требованиям", "Режимы", "workflow", "/grill-me"),
    SlashCommandDto("boost", "Углубленный анализ задачи с разных точек зрения", "Режимы", "workflow", "/boost "),
    SlashCommandDto("browser", "Автоматизация действий и поиск через веб-браузер", "Режимы", "workflow", "/browser "),
    SlashCommandDto("schedule", "Запуск задачи по расписанию или таймеру", "Режимы", "workflow", "/schedule "),
    SlashCommandDto("learn", "Запомнить правило/инструкцию для будущих сессий", "Режимы", "workflow", "/learn "),
    SlashCommandDto("agents", "Список всех доступных специализированных субагентов", "Агенты", "agents", "/agents"),
    SlashCommandDto("skills", "Список подключенных навыков и умений агента", "Агенты", "skills", "/skills"),
    SlashCommandDto("mcp", "Статус серверов MCP (Model Context Protocol)", "Агенты", "mcp", "/mcp"),
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

    // Live transcriptions pushed by gateway/agent
    val liveTranscriptions: StateFlow<Map<String, String>> = syncEngine.liveTranscriptions

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
            try {
                tasks.value = repository.getTasks(conversationId)
            } catch (e: Exception) {
                // ignore
            }
            try {
                artifacts.value = repository.getArtifacts(conversationId)
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

    data class WorkflowDialogData(
        val commandName: String,
        val title: String,
        val description: String,
        val hint: String = "Введите текст или спецификацию задачи..."
    )

    val showEffortSheet = MutableStateFlow(false)
    val showModelSheet = MutableStateFlow(false)
    val showTasksSheet = MutableStateFlow(false)
    val showArtifactsSheet = MutableStateFlow(false)
    val showDiffSheet = MutableStateFlow(false)
    val diffData = MutableStateFlow<DiffResponseDto?>(null)
    val isDiffLoading = MutableStateFlow(false)

    val showRenameDialog = MutableStateFlow(false)
    val showContextSheet = MutableStateFlow(false)
    val contextData = MutableStateFlow<ContextResponseDto?>(null)
    val isContextLoading = MutableStateFlow(false)

    val showAgentsSheet = MutableStateFlow(false)
    val agentsList = MutableStateFlow<List<GenericItemDto>>(emptyList())
    val isAgentsLoading = MutableStateFlow(false)

    val showSkillsSheet = MutableStateFlow(false)
    val skillsList = MutableStateFlow<List<GenericItemDto>>(emptyList())
    val isSkillsLoading = MutableStateFlow(false)

    val showCommandsCatalogSheet = MutableStateFlow(false)
    val activeWorkflowDialog = MutableStateFlow<WorkflowDialogData?>(null)
    val showClearConfirmDialog = MutableStateFlow(false)
    val showForkConfirmDialog = MutableStateFlow(false)
    val showBtwDialog = MutableStateFlow(false)
    val btwInitialQuery = MutableStateFlow("")

    val toastMessage = MutableStateFlow<String?>(null)

    fun confirmClearChat() {
        viewModelScope.launch {
            inputMessage.value = "/clear"
            sendMessage()
            toastMessage.value = "Контекст чата очищен"
        }
    }

    fun confirmForkChat() {
        viewModelScope.launch {
            inputMessage.value = "/fork"
            sendMessage()
            toastMessage.value = "Сессия разветвлена"
        }
    }

    fun submitBtwQuestion(question: String) {
        if (question.isNotBlank()) {
            inputMessage.value = "/btw ${question.trim()}"
            sendMessage()
        }
    }

    fun loadDiff() {
        viewModelScope.launch {
            isDiffLoading.value = true
            try {
                diffData.value = repository.getDiff(conversationId)
            } finally {
                isDiffLoading.value = false
            }
        }
    }

    fun renameChat(newTitle: String) {
        viewModelScope.launch {
            val success = repository.renameChat(conversationId, newTitle)
            if (success) {
                toastMessage.value = "Чат переименован: $newTitle"
                refreshChat()
            }
        }
    }

    fun loadContext() {
        viewModelScope.launch {
            isContextLoading.value = true
            try {
                contextData.value = repository.getChatContext(conversationId)
            } finally {
                isContextLoading.value = false
            }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            isAgentsLoading.value = true
            try {
                agentsList.value = repository.getAgents()
            } finally {
                isAgentsLoading.value = false
            }
        }
    }

    fun loadSkills() {
        viewModelScope.launch {
            isSkillsLoading.value = true
            try {
                skillsList.value = repository.getSkills()
            } finally {
                isSkillsLoading.value = false
            }
        }
    }

    fun launchWorkflow(commandName: String, prompt: String) {
        val trimmed = prompt.trim()
        val fullPrompt = when (commandName) {
            "goal" -> "/goal $trimmed"
            "plan" -> "/plan $trimmed"
            "teamwork-preview" -> if (trimmed.isEmpty()) "/teamwork-preview" else "/teamwork-preview $trimmed"
            "grill-me" -> if (trimmed.isEmpty()) "/grill-me" else "/grill-me $trimmed"
            "boost" -> "/boost $trimmed"
            "browser" -> "/browser $trimmed"
            "schedule" -> "/schedule $trimmed"
            "learn" -> "/learn $trimmed"
            else -> "/$commandName $trimmed"
        }
        inputMessage.value = fullPrompt
        sendMessage()
    }

    fun handleSlashCommand(rawCommandText: String): Boolean {
        val trimmed = rawCommandText.trim()
        if (!trimmed.startsWith("/")) return false

        val parts = trimmed.removePrefix("/").split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val args = if (parts.size > 1) parts[1].trim() else ""

        when (cmd) {
            "model" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    selectModel(args)
                    toastMessage.value = "Модель: $args"
                } else {
                    showModelSheet.value = true
                }
                return true
            }
            "tasks" -> {
                inputMessage.value = ""
                loadTasks()
                showTasksSheet.value = true
                return true
            }
            "artifact", "artifacts" -> {
                inputMessage.value = ""
                loadArtifacts()
                showArtifactsSheet.value = true
                return true
            }
            "effort" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    val validEffort = when (args.lowercase()) {
                        "high", "высокий" -> "high"
                        "medium", "средний" -> "medium"
                        "low", "низкий" -> "low"
                        "off", "выкл" -> "off"
                        else -> null
                    }
                    if (validEffort != null) {
                        selectEffort(validEffort)
                        toastMessage.value = "Уровень рассуждений: ${validEffort.uppercase()}"
                    } else {
                        showEffortSheet.value = true
                    }
                } else {
                    showEffortSheet.value = true
                }
                return true
            }
            "diff" -> {
                inputMessage.value = ""
                loadDiff()
                showDiffSheet.value = true
                return true
            }
            "title", "rename" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    renameChat(args)
                } else {
                    showRenameDialog.value = true
                }
                return true
            }
            "context", "usage", "credits" -> {
                inputMessage.value = ""
                loadContext()
                showContextSheet.value = true
                return true
            }
            "clear" -> {
                inputMessage.value = ""
                showClearConfirmDialog.value = true
                return true
            }
            "fork" -> {
                inputMessage.value = ""
                showForkConfirmDialog.value = true
                return true
            }
            "btw" -> {
                inputMessage.value = ""
                btwInitialQuery.value = args
                showBtwDialog.value = true
                return true
            }
            "agents" -> {
                inputMessage.value = ""
                loadAgents()
                showAgentsSheet.value = true
                return true
            }
            "skills" -> {
                inputMessage.value = ""
                loadSkills()
                showSkillsSheet.value = true
                return true
            }
            "help" -> {
                inputMessage.value = ""
                showCommandsCatalogSheet.value = true
                return true
            }
            "goal" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    selectEffort("high")
                    launchWorkflow("goal", args)
                } else {
                    activeWorkflowDialog.value = WorkflowDialogData(
                        commandName = "goal",
                        title = "Автономная цель (/goal)",
                        description = "Агент будет решать задачу автономно до полного достижения цели без остановки.",
                        hint = "Опишите цель (например: протестировать и исправить все ошибки сборки)..."
                    )
                }
                return true
            }
            "plan" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    launchWorkflow("plan", args)
                } else {
                    activeWorkflowDialog.value = WorkflowDialogData(
                        commandName = "plan",
                        title = "Составление плана (/plan)",
                        description = "Агент составит пошаговый архитектурный план перед кодингом.",
                        hint = "Опишите функционал или изменения для планирования..."
                    )
                }
                return true
            }
            "teamwork-preview" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    launchWorkflow("teamwork-preview", args)
                } else {
                    activeWorkflowDialog.value = WorkflowDialogData(
                        commandName = "teamwork-preview",
                        title = "Мультиагентная команда (/teamwork-preview)",
                        description = "Запуск параллельной команды агентов (исследователь, разработчик, ревьюер).",
                        hint = "Опишите задачу для мультиагентной команды..."
                    )
                }
                return true
            }
            "grill-me" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    launchWorkflow("grill-me", args)
                } else {
                    activeWorkflowDialog.value = WorkflowDialogData(
                        commandName = "grill-me",
                        title = "Интервью по требованиям (/grill-me)",
                        description = "Агент задаст серию уточняющих вопросов, чтобы детально прояснить требования.",
                        hint = "Тема интервью или область требований..."
                    )
                }
                return true
            }
            "boost" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    launchWorkflow("boost", args)
                } else {
                    activeWorkflowDialog.value = WorkflowDialogData(
                        commandName = "boost",
                        title = "Глубокий анализ (/boost)",
                        description = "Углубленный анализ задачи с разных перспектив с максимальной строгостью.",
                        hint = "Опишите проблему или тему анализа..."
                    )
                }
                return true
            }
            "browser" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    launchWorkflow("browser", args)
                } else {
                    activeWorkflowDialog.value = WorkflowDialogData(
                        commandName = "browser",
                        title = "Веб-браузер (/browser)",
                        description = "Автоматизация поиска и чтения документации через веб.",
                        hint = "Что нужно найти или изучить в браузере?..."
                    )
                }
                return true
            }
            "schedule" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    launchWorkflow("schedule", args)
                } else {
                    activeWorkflowDialog.value = WorkflowDialogData(
                        commandName = "schedule",
                        title = "Запуск по расписанию (/schedule)",
                        description = "Запуск периодической задачи или разового таймера.",
                        hint = "например: every 10m check server health..."
                    )
                }
                return true
            }
            "learn" -> {
                inputMessage.value = ""
                if (args.isNotEmpty()) {
                    launchWorkflow("learn", args)
                } else {
                    activeWorkflowDialog.value = WorkflowDialogData(
                        commandName = "learn",
                        title = "Запомнить правило (/learn)",
                        description = "Агент сохранит указанное правило для всех будущих сессий.",
                        hint = "Например: Всегда форматировать код через ktlint..."
                    )
                }
                return true
            }
        }
        return false
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

        // Load tasks and artifacts
        loadTasks()
        loadArtifacts()

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
