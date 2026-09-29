package com.antigravity.client.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.client.AntigravityApp
import com.antigravity.client.domain.model.ConnectionStatus
import com.antigravity.client.domain.model.Conversation
import com.antigravity.client.domain.model.ModelOption
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatsViewModel @JvmOverloads constructor(
    private val app: AntigravityApp = AntigravityApp.instance
) : ViewModel() {

    private val repository = app.chatRepository
    private val syncEngine = app.syncEngine
    val tokenStore = app.tokenStore

    val connectionStatus: StateFlow<ConnectionStatus> = syncEngine.connectionState

    val searchQuery = MutableStateFlow("")

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelOption>>(emptyList())
    val availableModels: StateFlow<List<ModelOption>> = _availableModels.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val conversations: StateFlow<List<Conversation>> = searchQuery
        .debounce(200)
        .flatMapLatest { query ->
            if (query.isBlank()) {
                repository.getConversations()
            } else {
                repository.searchConversations(query)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refresh()
        loadModels()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.refreshConversations()
            } catch (e: Exception) {
                // Keep local cached Room data even if offline
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            try {
                _availableModels.value = repository.getAvailableModels()
            } catch (e: Exception) {
                // If offline, default models
                _availableModels.value = listOf(
                    ModelOption("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)", "High"),
                    ModelOption("gemini-3.7-flash-low", "Gemini 3.7 Flash (Low)", "Low"),
                    ModelOption("claude-sonnet-4-6", "Claude Sonnet 4.6 (Thinking)", "Dynamic Thinking")
                )
            }
        }
    }

    fun createChat(
        workspace: String,
        message: String,
        model: String?,
        effort: String?,
        mode: String?,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val resp = repository.createChat(
                    workspace = workspace.trim().ifEmpty { tokenStore.defaultWorkspace },
                    message = message.trim(),
                    model = model,
                    effort = effort,
                    mode = mode
                )
                onSuccess(resp.conversationId)
            } catch (e: Exception) {
                _actionError.value = "Failed to create chat: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    fun clearActionError() {
        _actionError.value = null
    }
}
