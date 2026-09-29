package com.antigravity.client.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.client.AntigravityApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ConnectionUiState {
    object Idle : ConnectionUiState()
    object Loading : ConnectionUiState()
    data class Success(val gatewayVersion: String) : ConnectionUiState()
    data class Error(val message: String) : ConnectionUiState()
}

class ConnectionViewModel(
    private val app: AntigravityApp = AntigravityApp.instance
) : ViewModel() {

    private val tokenStore = app.tokenStore

    val serverUrl = MutableStateFlow(tokenStore.serverUrl)
    val token = MutableStateFlow(tokenStore.getToken() ?: "")
    val deviceName = MutableStateFlow(tokenStore.deviceName)

    private val _uiState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Idle)
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    fun testAndConnect(onSuccess: () -> Unit) {
        val url = serverUrl.value.trim()
        val rawToken = token.value.trim()
        val name = deviceName.value.trim()

        if (url.isEmpty()) {
            _uiState.value = ConnectionUiState.Error("Server URL cannot be empty")
            return
        }
        if (rawToken.isEmpty()) {
            _uiState.value = ConnectionUiState.Error("Device token cannot be empty")
            return
        }

        _uiState.value = ConnectionUiState.Loading

        viewModelScope.launch {
            try {
                // Save temporarily to test
                tokenStore.serverUrl = url
                tokenStore.saveToken(rawToken)
                tokenStore.deviceName = name

                // Re-initialize network client
                val testApi = app.networkClient.getApi()
                val health = testApi.getHealth()

                if (health.status == "ok" || health.status == "healthy") {
                    _uiState.value = ConnectionUiState.Success(health.gateway)
                    app.restartSyncEngine()
                    onSuccess()
                } else {
                    _uiState.value = ConnectionUiState.Error("Unexpected health status: ${health.status}")
                }
            } catch (e: retrofit2.HttpException) {
                val errorMsg = when (e.code()) {
                    401 -> "Authentication failed: invalid or revoked token"
                    403 -> "Access forbidden"
                    404 -> "Gateway endpoint not found"
                    else -> "Server returned HTTP ${e.code()}"
                }
                _uiState.value = ConnectionUiState.Error(errorMsg)
            } catch (e: Exception) {
                _uiState.value = ConnectionUiState.Error("Connection error: ${e.localizedMessage ?: e.message}")
            }
        }
    }
}
