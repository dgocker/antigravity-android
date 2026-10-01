package com.antigravity.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.client.AntigravityApp
import com.antigravity.client.data.remote.dto.DeviceTokenInfoDto
import com.antigravity.client.domain.model.ConnectionStatus
import com.antigravity.client.domain.model.ModelOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel @JvmOverloads constructor(
    private val app: AntigravityApp = AntigravityApp.instance
) : ViewModel() {

    val tokenStore = app.tokenStore
    private val api = app.networkClient.getApi()
    private val syncEngine = app.syncEngine

    val connectionStatus: StateFlow<ConnectionStatus> = syncEngine.connectionState

    private val _deviceTokens = MutableStateFlow<List<DeviceTokenInfoDto>>(emptyList())
    val deviceTokens: StateFlow<List<DeviceTokenInfoDto>> = _deviceTokens.asStateFlow()

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models.asStateFlow()

    val newGeneratedToken = MutableStateFlow<String?>(null)
    val errorMessage = MutableStateFlow<String?>(null)
    val trustSelfSigned = MutableStateFlow(tokenStore.trustSelfSigned)
    val appLanguage = MutableStateFlow(tokenStore.appLanguage)

    fun setAppLanguage(languageCode: String, context: android.content.Context) {
        tokenStore.appLanguage = languageCode
        appLanguage.value = languageCode
        com.antigravity.client.util.LocaleHelper.applyLocale(context, languageCode)
    }

    fun toggleTrustSelfSigned(trust: Boolean) {
        tokenStore.trustSelfSigned = trust
        trustSelfSigned.value = trust
        app.restartSyncEngine()
    }

    init {
        loadDeviceTokens()
        loadModels()
    }

    fun loadDeviceTokens() {
        viewModelScope.launch {
            try {
                _deviceTokens.value = api.listDeviceTokens()
            } catch (e: Exception) {
                // Ignore if offline
            }
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            try {
                _models.value = api.getModels().map {
                    ModelOption(it.id, it.name, it.reasoningLevel)
                }
            } catch (e: Exception) {
                // Default models if offline
            }
        }
    }

    fun createDeviceToken(deviceName: String) {
        viewModelScope.launch {
            try {
                val resp = api.createDeviceToken(
                    com.antigravity.client.data.remote.dto.DeviceTokenCreateRequestDto(deviceName)
                )
                newGeneratedToken.value = resp.token
                loadDeviceTokens()
            } catch (e: Exception) {
                errorMessage.value = "Failed to create token: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    fun revokeToken(id: Int) {
        viewModelScope.launch {
            try {
                api.revokeDeviceToken(id)
                loadDeviceTokens()
            } catch (e: Exception) {
                errorMessage.value = "Failed to revoke token: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    fun disconnect(onDisconnected: () -> Unit) {
        syncEngine.stop()
        tokenStore.clearCredentials()
        onDisconnected()
    }
}
