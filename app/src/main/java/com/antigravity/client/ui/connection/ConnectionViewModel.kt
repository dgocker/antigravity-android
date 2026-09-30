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

class ConnectionViewModel @JvmOverloads constructor(
    private val app: AntigravityApp = AntigravityApp.instance
) : ViewModel() {

    private val tokenStore = app.tokenStore

    val serverUrl = MutableStateFlow(tokenStore.serverUrl)
    val token = MutableStateFlow(tokenStore.getToken() ?: "")
    val deviceName = MutableStateFlow(tokenStore.deviceName)
    val trustSelfSigned = MutableStateFlow(tokenStore.trustSelfSigned)

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
                tokenStore.trustSelfSigned = trustSelfSigned.value

                // Re-initialize network client with new settings & SSL configuration
                val client = app.recreateNetworkClient()
                val testApi = client.getApi()
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
                val msg = e.localizedMessage ?: e.message ?: "Unknown error"
                val helpfulMsg = when {
                    msg.contains("Trust anchor", ignoreCase = true) ||
                    msg.contains("CertPathValidatorException", ignoreCase = true) ||
                    msg.contains("SSLPeerUnverifiedException", ignoreCase = true) ||
                    (msg.contains("Hostname", ignoreCase = true) && msg.contains("not verified", ignoreCase = true)) ->
                        "SSL certificate verification failed. If using self-signed cert or raw IP, enable 'Trust self-signed SSL'."
                    msg.contains("CLEARTEXT", ignoreCase = true) ->
                        "Cleartext HTTP is not permitted. Use https:// or verify network security configuration."
                    msg.contains("Unable to parse TLS packet header", ignoreCase = true) ->
                        "Порт ожидает обычный HTTP (без SSL). Укажите http:// вместо https:// или используйте HTTPS-порт сервера (например, 8444)."
                    else -> "Connection error: $msg"
                }
                _uiState.value = ConnectionUiState.Error(helpfulMsg)
            }
        }
    }
}
