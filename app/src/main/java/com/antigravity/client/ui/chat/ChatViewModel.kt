package com.antigravity.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.client.AntigravityApp
import com.antigravity.client.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    }

    fun sendMessage() {
        val rawText = inputMessage.value.trim()
        if (rawText.isBlank()) return

        val fullMessage = if (!quotedSnippet.value.isNullOrBlank()) {
            "Quoted code:\n```\n${quotedSnippet.value}\n```\n\n$rawText"
        } else {
            rawText
        }

        val conv = conversation.value
        val workspace = conv?.workspace ?: tokenStore.defaultWorkspace

        inputMessage.value = ""
        quotedSnippet.value = null

        val currentBaseStep = steps.value.maxOfOrNull { it.stepIndex } ?: -1
        val pendingId = UUID.randomUUID().toString()
        val pendingMsg = PendingUserMessage(
            id = pendingId,
            text = fullMessage,
            status = MessageDeliveryStatus.SENDING,
            baseStepIndex = currentBaseStep
        )
        // Add immediately to persistent repository store
        repository.addPendingMessage(conversationId, pendingMsg)

        viewModelScope.launch {
            try {
                repository.sendMessage(
                    conversationId = conversationId,
                    workspace = workspace,
                    text = fullMessage,
                    model = selectedModel.value,
                    effort = selectedEffort.value,
                    mode = tokenStore.selectedMode
                )
                // Mark as SENT (reached server queue)
                repository.updatePendingMessageStatus(conversationId, pendingId, MessageDeliveryStatus.SENT)
            } catch (e: Exception) {
                repository.updatePendingMessageStatus(conversationId, pendingId, MessageDeliveryStatus.FAILED)
                errorState.value = "Failed to send message: ${e.localizedMessage ?: e.message}"
            }
        }

        // Active background sync loop: continuously polls step history while agent is running
        // so the screen updates live even if network switches or WebSocket encounters latency
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
}

