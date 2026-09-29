package com.antigravity.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.client.AntigravityApp
import com.antigravity.client.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

    // Live streaming text accumulator for this specific conversation
    val activeDeltaText: StateFlow<String> = syncEngine.liveDeltas
        .map { it[conversationId] ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val quotedSnippet = MutableStateFlow<String?>(null)
    val inputMessage = MutableStateFlow("")

    val isCancelling = MutableStateFlow(false)
    val errorState = MutableStateFlow<String?>(null)

    init {
        // Load full step history from server
        viewModelScope.launch {
            try {
                repository.fetchStepsHistory(conversationId)
            } catch (e: Exception) {
                // Ignore, will use Room cache
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

        viewModelScope.launch {
            try {
                repository.sendMessage(
                    conversationId = conversationId,
                    workspace = workspace,
                    text = fullMessage,
                    model = tokenStore.selectedModel,
                    effort = tokenStore.selectedEffort,
                    mode = tokenStore.selectedMode
                )
            } catch (e: Exception) {
                errorState.value = "Failed to send message: ${e.localizedMessage ?: e.message}"
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
}
