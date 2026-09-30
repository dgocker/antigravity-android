package com.antigravity.client.security

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class TokenStore(
    context: Context,
    private val keystoreManager: KeystoreManager = KeystoreManager(context)
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agy_secure_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENCRYPTED_TOKEN = "enc_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_SELECTED_EFFORT = "selected_effort"
        private const val KEY_SELECTED_MODE = "selected_mode"
        private const val KEY_LAST_SEQ = "last_received_seq"
        private const val KEY_DEFAULT_WORKSPACE = "default_workspace"
        private const val KEY_TRUST_SELF_SIGNED = "trust_self_signed"

        const val DEFAULT_SERVER_URL = "http://127.0.0.1:8765"
        const val DEFAULT_WORKSPACE = "/root/agy-workspaces"
    }

    fun saveToken(token: String) {
        val encrypted = keystoreManager.encrypt(token.trim())
        prefs.edit().putString(KEY_ENCRYPTED_TOKEN, encrypted).apply()
    }

    fun getToken(): String? {
        val encrypted = prefs.getString(KEY_ENCRYPTED_TOKEN, null) ?: return null
        val decrypted = keystoreManager.decrypt(encrypted)
        return if (decrypted.isNotBlank()) decrypted else null
    }

    fun hasToken(): Boolean {
        return !getToken().isNullOrBlank()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_ENCRYPTED_TOKEN)
            .remove(KEY_LAST_SEQ)
            .apply()
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var defaultWorkspace: String
        get() = prefs.getString(KEY_DEFAULT_WORKSPACE, DEFAULT_WORKSPACE) ?: DEFAULT_WORKSPACE
        set(value) = prefs.edit().putString(KEY_DEFAULT_WORKSPACE, value.trim()).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "Android Phone") ?: "Android Phone"
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    var selectedModel: String?
        get() = prefs.getString(KEY_SELECTED_MODEL, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL, value).apply()

    var selectedEffort: String
        get() = prefs.getString(KEY_SELECTED_EFFORT, "high") ?: "high"
        set(value) = prefs.edit().putString(KEY_SELECTED_EFFORT, value).apply()

    var selectedMode: String
        get() = prefs.getString(KEY_SELECTED_MODE, "accept-edits") ?: "accept-edits"
        set(value) = prefs.edit().putString(KEY_SELECTED_MODE, value).apply()

    var lastReceivedSeq: Long
        get() = prefs.getLong(KEY_LAST_SEQ, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SEQ, value).apply()

    var trustSelfSigned: Boolean
        get() = prefs.getBoolean(KEY_TRUST_SELF_SIGNED, false)
        set(value) = prefs.edit().putBoolean(KEY_TRUST_SELF_SIGNED, value).apply()
}
